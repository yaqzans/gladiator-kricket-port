"""HD versions of the game's images: Real-ESRGAN (anime 6B model, 4x) on the CPU with PyTorch.

Colour and transparency are upscaled separately. Before upscaling the colour, transparent
pixels are filled with the nearest opaque colour, so sprite edges do not pick up dark fringes.

usage: python tools/upscale.py <weights .pth> <in dir> <out dir> [file ...]
weights: RealESRGAN_x4plus_anime_6B.pth from github.com/xinntao/Real-ESRGAN releases v0.2.2.4
"""
import sys
import time
from pathlib import Path

import numpy as np
import torch
from PIL import Image
from torch import nn
from torch.nn import functional as F


class ResidualDenseBlock(nn.Module):
    def __init__(self, nf=64, gc=32):
        super().__init__()
        self.conv1 = nn.Conv2d(nf, gc, 3, 1, 1)
        self.conv2 = nn.Conv2d(nf + gc, gc, 3, 1, 1)
        self.conv3 = nn.Conv2d(nf + 2 * gc, gc, 3, 1, 1)
        self.conv4 = nn.Conv2d(nf + 3 * gc, gc, 3, 1, 1)
        self.conv5 = nn.Conv2d(nf + 4 * gc, nf, 3, 1, 1)
        self.lrelu = nn.LeakyReLU(0.2, inplace=True)

    def forward(self, x):
        x1 = self.lrelu(self.conv1(x))
        x2 = self.lrelu(self.conv2(torch.cat((x, x1), 1)))
        x3 = self.lrelu(self.conv3(torch.cat((x, x1, x2), 1)))
        x4 = self.lrelu(self.conv4(torch.cat((x, x1, x2, x3), 1)))
        x5 = self.conv5(torch.cat((x, x1, x2, x3, x4), 1))
        return x5 * 0.2 + x


class RRDB(nn.Module):
    def __init__(self, nf, gc=32):
        super().__init__()
        self.rdb1 = ResidualDenseBlock(nf, gc)
        self.rdb2 = ResidualDenseBlock(nf, gc)
        self.rdb3 = ResidualDenseBlock(nf, gc)

    def forward(self, x):
        return self.rdb3(self.rdb2(self.rdb1(x))) * 0.2 + x


class RRDBNet(nn.Module):
    def __init__(self, nf=64, nb=6, gc=32):
        super().__init__()
        self.conv_first = nn.Conv2d(3, nf, 3, 1, 1)
        self.body = nn.Sequential(*[RRDB(nf, gc) for _ in range(nb)])
        self.conv_body = nn.Conv2d(nf, nf, 3, 1, 1)
        self.conv_up1 = nn.Conv2d(nf, nf, 3, 1, 1)
        self.conv_up2 = nn.Conv2d(nf, nf, 3, 1, 1)
        self.conv_hr = nn.Conv2d(nf, nf, 3, 1, 1)
        self.conv_last = nn.Conv2d(nf, 3, 3, 1, 1)
        self.lrelu = nn.LeakyReLU(0.2, inplace=True)

    def forward(self, x):
        feat = self.conv_first(x)
        feat = feat + self.conv_body(self.body(feat))
        feat = self.lrelu(self.conv_up1(F.interpolate(feat, scale_factor=2, mode="nearest")))
        feat = self.lrelu(self.conv_up2(F.interpolate(feat, scale_factor=2, mode="nearest")))
        return self.conv_last(self.lrelu(self.conv_hr(feat)))


def load_model(weights):
    net = RRDBNet()
    state = torch.load(weights, map_location="cpu", weights_only=True)
    net.load_state_dict(state.get("params_ema", state), strict=True)
    return net.eval()


@torch.no_grad()
def run(net, rgb):
    """rgb: HxWx3 float 0..1 -> 4H x 4W x 3."""
    # pad by reflection so the network sees context at the borders, then crop
    pad = 4
    t = torch.from_numpy(rgb.transpose(2, 0, 1)).unsqueeze(0).float()
    t = F.pad(t, (pad, pad, pad, pad), mode="replicate")
    out = net(t).clamp(0, 1)[0].numpy().transpose(1, 2, 0)
    return out[pad * 4:-pad * 4, pad * 4:-pad * 4]


def fill_transparent(rgb, alpha):
    """Spread opaque colours into transparent pixels (a few passes of neighbour averaging)."""
    rgb = rgb.copy()
    known = alpha > 0.5
    if known.all() or not known.any():
        return rgb
    for _ in range(12):
        acc = np.zeros_like(rgb)
        cnt = np.zeros(alpha.shape)
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                k = np.roll(np.roll(known, dy, 0), dx, 1)
                acc += np.roll(np.roll(rgb * known[..., None], dy, 0), dx, 1)
                cnt += k
        grow = (~known) & (cnt > 0)
        rgb[grow] = acc[grow] / cnt[grow][:, None]
        known = known | grow
        if known.all():
            break
    return rgb


def upscale_file(net, src, dst):
    im = Image.open(src).convert("RGBA")
    a = np.asarray(im).astype(np.float32) / 255
    rgb, alpha = a[..., :3], a[..., 3]
    out_rgb = run(net, fill_transparent(rgb, alpha))
    if (alpha < 1).any():
        out_a = run(net, np.repeat(alpha[..., None], 3, 2)).mean(2)
        # keep cut-out sprites cut-out: harden the soft alpha the network makes
        out_a = np.clip((out_a - 0.5) * 3 + 0.5, 0, 1)
    else:
        out_a = np.ones(out_rgb.shape[:2], np.float32)
    out = np.dstack([out_rgb, out_a])
    Image.fromarray((out * 255 + 0.5).astype(np.uint8), "RGBA").save(dst, optimize=True)


def main():
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    torch.set_num_threads(max(1, torch.get_num_threads()))
    net = load_model(sys.argv[1])
    src_dir, dst_dir = Path(sys.argv[2]), Path(sys.argv[3])
    dst_dir.mkdir(parents=True, exist_ok=True)
    files = [src_dir / f for f in sys.argv[4:]] or sorted(src_dir.glob("*.png"))
    for f in files:
        t = time.time()
        upscale_file(net, f, dst_dir / f.name)
        print(f"{f.name}: {time.time() - t:.1f}s", flush=True)


if __name__ == "__main__":
    main()
