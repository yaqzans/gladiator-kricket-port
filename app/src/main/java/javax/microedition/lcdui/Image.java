package javax.microedition.lcdui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;

/**
 * Immutable images loaded from the original PNGs, which ship as Java resources. When an HD
 * version exists (resources /hd/..., 4x, made by tools/upscale.py), drawing uses that instead;
 * all game coordinates stay in the original pixel units.
 */
public class Image {
    /** HD images are this many times the original size. */
    public static final int HD_SCALE = 4;

    private static final HashMap<String, Image> cache = new HashMap<>();

    final Bitmap bitmap;
    /** 4x version or null. */
    final Bitmap hd;
    private final String name;

    private Image(Bitmap bitmap, Bitmap hd, String name) {
        this.bitmap = bitmap;
        this.hd = hd;
        this.name = name;
    }

    /** The resource path it was loaded from; saved matches store images by this name. */
    public String name() {
        return name;
    }

    public static synchronized Image createImage(String name) throws IOException {
        Image img = cache.get(name);
        if (img != null) {
            return img;
        }
        Bitmap b = decode(name, false);
        if (b == null) {
            throw new IOException("missing image " + name);
        }
        Bitmap hd = decode("/hd" + name, android.os.Build.VERSION.SDK_INT >= 28);
        if (hd != null && (hd.getWidth() != b.getWidth() * HD_SCALE || hd.getHeight() != b.getHeight() * HD_SCALE)) {
            hd = null;   // not a matching 4x version: ignore it
        }
        img = new Image(b, hd, name);
        cache.put(name, img);
        return img;
    }

    /** gpuOnly: a hardware bitmap (Android 9+), kept in GPU memory only; HD art is ~100 MB. */
    private static Bitmap decode(String name, boolean gpuOnly) throws IOException {
        InputStream in = Image.class.getResourceAsStream(name);
        if (in == null) {
            return null;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inScaled = false;
        opts.inPreferredConfig = gpuOnly ? Bitmap.Config.HARDWARE : Bitmap.Config.ARGB_8888;
        try {
            return BitmapFactory.decodeStream(in, null, opts);
        } finally {
            in.close();
        }
    }

    public int getWidth() {
        return bitmap.getWidth();
    }

    public int getHeight() {
        return bitmap.getHeight();
    }
}
