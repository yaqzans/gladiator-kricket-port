package javax.microedition.lcdui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;

/** Immutable images loaded from the original PNGs, which ship as Java resources. */
public class Image {
    private static final HashMap<String, Image> cache = new HashMap<>();

    final Bitmap bitmap;
    private final String name;

    private Image(Bitmap bitmap, String name) {
        this.bitmap = bitmap;
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
        InputStream in = Image.class.getResourceAsStream(name);
        if (in == null) {
            throw new IOException("missing image " + name);
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inScaled = false;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b;
        try {
            b = BitmapFactory.decodeStream(in, null, opts);
        } finally {
            in.close();
        }
        if (b == null) {
            throw new IOException("cannot decode " + name);
        }
        img = new Image(b, name);
        cache.put(name, img);
        return img;
    }

    public int getWidth() {
        return bitmap.getWidth();
    }

    public int getHeight() {
        return bitmap.getHeight();
    }
}
