"""Redraw the menu label images (menuText, menuTextSel, gameLevel, gameLevelSel) in HD.

The AI upscale garbles their small serif letters ("New Gamo"). This keeps the upscaled panel
shapes, blacks out the old letters and writes each label fresh in Cinzel Bold (SIL Open Font
License, github.com/google/fonts ofl/cinzel) at 4x.

usage: python tools/menu_labels.py <Cinzel.ttf> <original images dir> <hd images dir>
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

K = 4
ROW = 19   # each label is one 19-pixel row of the original strip
LABELS = {
    "menuText.png": (["Continue", "New Game", "Load Game", "Setting", "Help", "About Us", "Exit"], False),
    "menuTextSel.png": (["Continue", "New Game", "Load Game", "Setting", "Help", "About Us", "Exit"], True),
    "gameLevel.png": (["Select Game", "Match - 01", "Match - 02", "Match - 03", "Match - 04"], False),
    "gameLevelSel.png": (["Select Game", "Match - 01", "Match - 02", "Match - 03", "Match - 04"], True),
}
NORMAL = (214, 214, 214, 255)      # the original grey (114) brightened so it reads on a phone
SELECTED = (255, 104, 26, 255)     # the original orange


def main():
    font_path, orig_dir, hd_dir = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])
    for name, (labels, selected) in LABELS.items():
        orig = Image.open(orig_dir / name).convert("RGBA")
        hd = Image.open(hd_dir / name).convert("RGBA")
        # where the old letters were: any non-black opaque pixel, grown a little, at 4x
        letters = Image.new("L", orig.size, 0)
        op, lp = orig.load(), letters.load()
        for y in range(orig.height):
            for x in range(orig.width):
                r, g, b, a = op[x, y]
                if a > 0 and max(r, g, b) > 20:
                    lp[x, y] = 255
        mask = letters.resize(hd.size, Image.NEAREST).filter(ImageFilter.MaxFilter(9))
        # only inside the panel: never paint over the transparent edge (descenders stuck out)
        inside = hd.getchannel("A").point(lambda a: 255 if a > 128 else 0)
        mask = Image.composite(mask, Image.new("L", hd.size, 0), inside)
        black = Image.new("RGBA", hd.size, (0, 0, 0, 255))
        hd = Image.composite(black, hd, mask)
        # trim to the original panel outline: the upscaler leaves stray bits of old descenders
        outline = orig.getchannel("A").resize(hd.size, Image.BICUBIC).point(lambda a: 255 if a > 128 else 0)
        alpha = Image.composite(hd.getchannel("A"), Image.new("L", hd.size, 0), outline)
        hd.putalpha(alpha)

        draw = ImageDraw.Draw(hd)
        font = ImageFont.truetype(font_path, 44)
        font.set_variation_by_name("Bold")
        for i, text in enumerate(labels):
            cy = (i * ROW + ROW / 2) * K
            cx = hd.width / 2
            draw.text((cx, cy + 3), text, font=font, fill=(0, 0, 0, 200), anchor="mm")   # shadow
            draw.text((cx, cy), text, font=font, fill=SELECTED if selected else NORMAL, anchor="mm")
        hd.save(hd_dir / name, optimize=True)
        print(f"{name}: {len(labels)} labels")


if __name__ == "__main__":
    main()
