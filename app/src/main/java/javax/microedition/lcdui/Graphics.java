package javax.microedition.lcdui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * MIDP Graphics on top of an Android Canvas that draws into the fixed 240x320 frame.
 * Only the calls the game makes are here (see kb/code-map.md section 10).
 */
public class Graphics {
    public static final int HCENTER = 1;
    public static final int VCENTER = 2;
    public static final int LEFT = 4;
    public static final int RIGHT = 8;
    public static final int TOP = 16;
    public static final int BOTTOM = 32;
    public static final int BASELINE = 64;

    // drawRegion transforms, values from MIDP's game.Sprite
    private static final int TRANS_NONE = 0;
    private static final int TRANS_MIRROR_ROT180 = 1;
    private static final int TRANS_MIRROR = 2;
    private static final int TRANS_ROT180 = 3;
    private static final int TRANS_MIRROR_ROT270 = 4;
    private static final int TRANS_ROT90 = 5;
    private static final int TRANS_ROT270 = 6;
    private static final int TRANS_MIRROR_ROT90 = 7;

    private final Canvas canvas;
    private final int baseSave;
    private final Paint paint = new Paint();
    private final Rect src = new Rect();
    private final Rect dst = new Rect();
    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private int tx;
    private int ty;

    public Graphics(Bitmap target) {
        canvas = new Canvas(target);
        baseSave = canvas.save();
        // nearest neighbour everywhere, the art is pixel art
        paint.setAntiAlias(false);
        paint.setFilterBitmap(false);
        paint.setDither(false);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF000000);
    }

    /** MIDP resets translation and clip before every paint() call. */
    public void reset(int width, int height) {
        tx = 0;
        ty = 0;
        canvas.restoreToCount(baseSave);
        canvas.save();
        canvas.clipRect(0, 0, width, height);
        paint.setColor(0xFF000000);
    }

    public void translate(int x, int y) {
        tx += x;
        ty += y;
    }

    public int getTranslateX() {
        return tx;
    }

    public int getTranslateY() {
        return ty;
    }

    public void setColor(int rgb) {
        paint.setColor(0xFF000000 | rgb);
    }

    public void setColor(int r, int g, int b) {
        paint.setColor(0xFF000000 | ((r & 255) << 16) | ((g & 255) << 8) | (b & 255));
    }

    /** MIDP setClip replaces the clip; it does not intersect with the old one. */
    public void setClip(int x, int y, int w, int h) {
        canvas.restoreToCount(baseSave);
        canvas.save();
        canvas.clipRect(x + tx, y + ty, x + tx + w, y + ty + h);
    }

    public void fillRect(int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        canvas.drawRect(x + tx, y + ty, x + tx + w, y + ty + h, paint);
    }

    /** MIDP outlines cover (w + 1) x (h + 1) pixels. */
    public void drawRect(int x, int y, int w, int h) {
        if (w < 0 || h < 0) {
            return;
        }
        fillRect(x, y, w + 1, 1);
        fillRect(x, y + h, w + 1, 1);
        fillRect(x, y, 1, h + 1);
        fillRect(x + w, y, 1, h + 1);
    }

    /** Bresenham, so both end points are drawn like on the phone. */
    public void drawLine(int x1, int y1, int x2, int y2) {
        int dx = Math.abs(x2 - x1);
        int dy = -Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            fillRect(x1, y1, 1, 1);
            if (x1 == x2 && y1 == y2) {
                return;
            }
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x1 += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y1 += sy;
            }
        }
    }

    public void drawImage(Image img, int x, int y, int anchor) {
        Bitmap b = img.bitmap;
        int w = b.getWidth();
        int h = b.getHeight();
        x += tx - anchorDx(anchor, w);
        y += ty - anchorDy(anchor, h);
        canvas.drawBitmap(b, x, y, paint);
    }

    public void drawRegion(Image img, int sx, int sy, int w, int h, int transform,
                           int x, int y, int anchor) {
        if (w <= 0 || h <= 0) {
            return;
        }
        // transforms 4..7 swap width and height (the 90 / 270 degree ones)
        boolean swap = transform >= 4;
        int dw = swap ? h : w;
        int dh = swap ? w : h;
        int dx = x + tx - anchorDx(anchor, dw);
        int dy = y + ty - anchorDy(anchor, dh);

        if (transform == TRANS_NONE) {
            src.set(sx, sy, sx + w, sy + h);
            dst.set(dx, dy, dx + w, dy + h);
            canvas.drawBitmap(img.bitmap, src, dst, paint);
            return;
        }

        // affine map from region coords (u, v) to output coords: x' = a*u + b*v + c, y' = d*u + e*v + f
        float a, bb, c, d, e, f;
        switch (transform) {
            case TRANS_ROT90:          a = 0;  bb = -1; c = h; d = 1;  e = 0;  f = 0; break;
            case TRANS_ROT180:         a = -1; bb = 0;  c = w; d = 0;  e = -1; f = h; break;
            case TRANS_ROT270:         a = 0;  bb = 1;  c = 0; d = -1; e = 0;  f = w; break;
            case TRANS_MIRROR:         a = -1; bb = 0;  c = w; d = 0;  e = 1;  f = 0; break;
            case TRANS_MIRROR_ROT180:  a = 1;  bb = 0;  c = 0; d = 0;  e = -1; f = h; break;
            case TRANS_MIRROR_ROT90:   a = 0;  bb = -1; c = h; d = -1; e = 0;  f = w; break;
            case TRANS_MIRROR_ROT270:  a = 0;  bb = 1;  c = 0; d = 1;  e = 0;  f = 0; break;
            default:
                throw new IllegalArgumentException("transform " + transform);
        }
        values[0] = a;  values[1] = bb; values[2] = c;
        values[3] = d;  values[4] = e;  values[5] = f;
        values[6] = 0;  values[7] = 0;  values[8] = 1;
        matrix.setValues(values);
        matrix.preTranslate(-sx, -sy);
        matrix.postTranslate(dx, dy);

        // the whole sheet goes through the matrix, so clip to the output box
        canvas.save();
        canvas.clipRect(dx, dy, dx + dw, dy + dh);
        canvas.drawBitmap(img.bitmap, matrix, paint);
        canvas.restore();
    }

    private static int anchorDx(int anchor, int w) {
        if ((anchor & HCENTER) != 0) {
            return w / 2;
        }
        if ((anchor & RIGHT) != 0) {
            return w;
        }
        return 0;
    }

    private static int anchorDy(int anchor, int h) {
        if ((anchor & VCENTER) != 0) {
            return h / 2;
        }
        if ((anchor & BOTTOM) != 0) {
            return h;
        }
        return 0;
    }
}
