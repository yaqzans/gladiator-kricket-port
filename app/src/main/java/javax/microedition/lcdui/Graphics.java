package javax.microedition.lcdui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * MIDP Graphics for the game. Every call is recorded (into an android.graphics.Picture) in the
 * game's own pixel units, and the screen replays the recording at the phone's full resolution
 * on the GPU. Images are drawn from their HD versions, text in a real font, so the result is
 * sharp at any size instead of a scaled-up 240-pixel-wide picture.
 *
 * Only the calls the game makes are here (kb/code-map.md section 10).
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

    private Canvas canvas;
    private int baseSave;
    private final Paint fill = new Paint();
    private final Paint hdPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pixelPaint = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private int tx;
    private int ty;
    /** Where this frame drew each menu panel (menupanel.png), in canvas units: taps hit these. */
    private final java.util.ArrayList<RectF> panels = new java.util.ArrayList<>();
    private int color = 0xFF000000;

    // the original screen is 240x320; on a taller phone menus sit in a 240x320 frame centred
    // vertically (offsetY) while full-screen backgrounds and fills cover the whole canvas
    private int canvasW, canvasH, offsetY;

    public Graphics() {
        fill.setAntiAlias(false);
        fill.setStyle(Paint.Style.FILL);
        pixelPaint.setFilterBitmap(false);
        hdPaint.setAntiAlias(true);
        text.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        text.setTextAlign(Paint.Align.LEFT);
    }

    /** Start a frame: MIDP resets translation and clip before every paint() call. */
    public void begin(Canvas c, int width, int height, int offsetY) {
        canvas = c;
        panels.clear();
        canvasW = width;
        canvasH = height;
        this.offsetY = offsetY;
        baseSave = canvas.save();
        canvas.clipRect(0, 0, width, height);
        canvas.drawColor(0xFF000000);
        tx = 0;
        ty = offsetY;
        canvas.save();
        setColor(0);
    }

    /** The menu panels drawn this frame, top to bottom in drawing order. */
    public RectF[] panelRects() {
        return panels.toArray(new RectF[0]);
    }

    public void end() {
        canvas.restoreToCount(baseSave);
        canvas = null;
    }

    public void translate(int x, int y) {
        tx += x;
        ty += y;
    }

    public int getTranslateX() {
        return tx;
    }

    public int getTranslateY() {
        return ty - offsetY;
    }

    public void setColor(int rgb) {
        color = 0xFF000000 | rgb;
        fill.setColor(color);
    }

    public void setColor(int r, int g, int b) {
        setColor(((r & 255) << 16) | ((g & 255) << 8) | (b & 255));
    }

    /** MIDP setClip replaces the clip; it does not intersect with the old one. */
    public void setClip(int x, int y, int w, int h) {
        canvas.restoreToCount(baseSave + 1);
        canvas.save();
        canvas.clipRect(x + tx, y + ty, x + tx + w, y + ty + h);
    }

    public void fillRect(int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int l = x + tx, t = y + ty;
        // a fill of the whole original screen (a coloured screen background) covers the phone screen;
        // a black one (logos, intro, loading) gets the menu artwork, dimmed, so no screen is blank
        if (offsetY > 0 && t == offsetY && l <= 0 && h >= 320 && w >= canvasW) {
            if ((color & 0xFFFFFF) == 0 && drawBackdrop()) {
                return;
            }
            canvas.drawRect(0, 0, canvasW, canvasH, fill);
            return;
        }
        canvas.drawRect(l, t, l + w, t + h, fill);
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
        int w = img.getWidth();
        int h = img.getHeight();
        float l = x + tx - anchorDx(anchor, w);
        float t = y + ty - anchorDy(anchor, h);
        // a full-screen background of the original phone: cover the whole phone screen with it
        if (offsetY > 0 && w >= 240 && h >= 300 && Math.round(t) == offsetY && Math.round(l) <= 0) {
            float s = Math.max(canvasW / (float) w, canvasH / (float) h);
            float cw = w * s, ch = h * s;
            dst.set((canvasW - cw) / 2, (canvasH - ch) / 2, (canvasW + cw) / 2, (canvasH + ch) / 2);
            drawWhole(img, dst);
            return;
        }
        dst.set(l, t, l + w, t + h);
        drawWhole(img, dst);
    }

    private Image backdrop;

    private boolean drawBackdrop() {
        try {
            if (backdrop == null) {
                backdrop = Image.createImage("/menuBg.png");
            }
        } catch (java.io.IOException e) {
            return false;
        }
        float s = Math.max(canvasW / (float) backdrop.getWidth(), canvasH / (float) backdrop.getHeight());
        float cw = backdrop.getWidth() * s, ch = backdrop.getHeight() * s;
        dst.set((canvasW - cw) / 2, (canvasH - ch) / 2, (canvasW + cw) / 2, (canvasH + ch) / 2);
        drawWhole(backdrop, dst);
        canvas.drawColor(0xD9000000);   // dimmed to a dark, textured background
        return true;
    }

    private void drawWhole(Image img, RectF to) {
        if (img.hd != null) {
            canvas.drawBitmap(img.hd, null, to, hdPaint);
        } else {
            canvas.drawBitmap(img.bitmap, null, to, pixelPaint);
        }
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
        if ("/menupanel.png".equals(img.name())) {
            panels.add(new RectF(dx, dy, dx + dw, dy + dh));
        }
        Bitmap bmp = img.hd != null ? img.hd : img.bitmap;
        int k = img.hd != null ? Image.HD_SCALE : 1;
        Paint p = img.hd != null ? hdPaint : pixelPaint;

        if (transform == TRANS_NONE) {
            src.set(sx * k, sy * k, (sx + w) * k, (sy + h) * k);
            dst.set(dx, dy, dx + w, dy + h);
            canvas.drawBitmap(bmp, src, dst, p);
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
        matrix.preScale(1f / k, 1f / k);      // bitmap pixels -> original pixel units
        matrix.postTranslate(dx, dy);

        // the whole sheet goes through the matrix, so clip to the output box
        canvas.save();
        canvas.clipRect(dx, dy, dx + dw, dy + dh);
        canvas.drawBitmap(bmp, matrix, p);
        canvas.restore();
    }

    /**
     * Text in a real font instead of the 9-pixel bitmap font, stretched to exactly the bitmap
     * font's width so every line, wrap and right-aligned label lands where it always did.
     * white: the white font (f.png), otherwise the dark grey one (Font_Gray.png).
     */
    public boolean drawGameText(String s, int x, int y, boolean white, int width, int cellHeight) {
        if (s.length() == 0 || width <= 0) {
            return true;
        }
        float size = cellHeight * 1.18f;
        text.setTextSize(size);
        text.setTextScaleX(1f);
        float natural = text.measureText(s);
        text.setTextScaleX(Math.min(1.25f, width / natural));
        float base = y + ty + cellHeight * 0.86f;
        float left = x + tx;
        if (white) {
            text.setColor(0xCC000000);       // soft dark edge so white text reads on any art
            text.setShadowLayer(cellHeight * 0.18f, 0, cellHeight * 0.06f, 0xCC000000);
            canvas.drawText(s, left, base, text);
            text.clearShadowLayer();
            text.setColor(0xFFFFFFFF);
        } else {
            text.setColor(0xFF3A3A3A);
        }
        canvas.drawText(s, left, base, text);
        return true;
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
