package gk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.util.SparseArray;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import java.lang.reflect.Field;

/**
 * Draws the 240x320 frame scaled to the phone width (sharp pixels, proportions kept)
 * and a strip of touch buttons in the space underneath.
 *
 * Touch on the game picture:
 *  - during a match: FIRE the moment the finger goes down (bat swing timing matters)
 *  - elsewhere: tap = FIRE, or the left soft key on screens that only take that key;
 *    swipe = arrow keys; bottom corners = the soft keys whose labels are drawn there.
 */
public class GameView extends View {
    private static final int STATE_PLAYING = 8;
    private static final int SUB_STATE_SCOREBOARD = 11;

    private final Paint pixelPaint = new Paint();
    private final Paint buttonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect(0, 0, Host.WIDTH, Host.HEIGHT);
    private final Rect gameRect = new Rect();
    private final Button[] buttons = new Button[7];
    private final SparseArray<Integer> pointerKey = new SparseArray<>();
    private float density;
    private int insetLeft, insetTop, insetRight, insetBottom;

    // the game picture is one touch target with gesture handling
    private int gamePointer = -1;
    private float downX, downY;
    private int gameKeyDown;

    private static Field screenField, subStateField;

    private static final class Button {
        final int key;
        final String label;
        final RectF rect = new RectF();
        final boolean round;
        boolean down;

        Button(int key, String label, boolean round) {
            this.key = key;
            this.label = label;
            this.round = round;
        }
    }

    public GameView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        pixelPaint.setFilterBitmap(false);
        pixelPaint.setAntiAlias(false);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        buttons[0] = new Button(Host.KEY_LSK, "OK", false);
        buttons[1] = new Button(Host.KEY_RSK, "BACK", false);
        buttons[2] = new Button(Host.KEY_UP, "▲", false);
        buttons[3] = new Button(Host.KEY_DOWN, "▼", false);
        buttons[4] = new Button(Host.KEY_LEFT, "◀", false);
        buttons[5] = new Button(Host.KEY_RIGHT, "▶", false);
        buttons[6] = new Button(Host.KEY_FIRE, "HIT", true);
        setBackgroundColor(Color.BLACK);
    }

    // ---- layout -----------------------------------------------------------

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        // keep clear of notches / punch holes and any bar that is still showing
        insetLeft = insets.getSystemWindowInsetLeft();
        insetTop = insets.getSystemWindowInsetTop();
        insetRight = insets.getSystemWindowInsetRight();
        insetBottom = insets.getSystemWindowInsetBottom();
        if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutout cut = insets.getDisplayCutout();
            if (cut != null) {
                insetLeft = Math.max(insetLeft, cut.getSafeInsetLeft());
                insetTop = Math.max(insetTop, cut.getSafeInsetTop());
                insetRight = Math.max(insetRight, cut.getSafeInsetRight());
                insetBottom = Math.max(insetBottom, cut.getSafeInsetBottom());
            }
        }
        layoutAll(getWidth(), getHeight());
        invalidate();
        return insets;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        layoutAll(w, h);
    }

    private void layoutAll(int w, int h) {
        if (w == 0 || h == 0) {
            return;
        }
        int left = insetLeft, top = insetTop;
        int availW = w - insetLeft - insetRight;
        int availH = h - insetTop - insetBottom;

        // the game gets the full width unless that leaves under a quarter of the height for buttons
        float minStrip = availH * 0.25f;
        float scale = Math.min(availW / (float) Host.WIDTH, (availH - minStrip) / Host.HEIGHT);
        int gw = Math.round(Host.WIDTH * scale);
        int gh = Math.round(Host.HEIGHT * scale);
        int gx = left + (availW - gw) / 2;
        gameRect.set(gx, top, gx + gw, top + gh);

        // button strip: soft keys on top, d-pad bottom left, HIT bottom right
        float sTop = gameRect.bottom;
        float sBottom = top + availH;
        float sH = sBottom - sTop;
        float pad = 8 * density;
        float softH = Math.min(sH * 0.25f, 52 * density);
        float softW = availW * 0.3f;
        buttons[0].rect.set(left + pad, sTop + pad, left + softW, sTop + softH);
        buttons[1].rect.set(left + availW - softW, sTop + pad, left + availW - pad, sTop + softH);

        float areaTop = sTop + softH + pad;
        float areaH = sBottom - areaTop - pad;
        float half = availW / 2f;
        float pad3 = Math.min(half - 2 * pad, areaH);
        float cell = pad3 / 3f;
        float cx = left + half / 2f;
        float cy = areaTop + areaH / 2f;
        float g = 3 * density;
        setCell(buttons[2], cx - cell / 2, cy - 1.5f * cell, cell, g);
        setCell(buttons[3], cx - cell / 2, cy + 0.5f * cell, cell, g);
        setCell(buttons[4], cx - 1.5f * cell, cy - cell / 2, cell, g);
        setCell(buttons[5], cx + 0.5f * cell, cy - cell / 2, cell, g);

        float r = Math.min(half - 2 * pad, areaH) * 0.42f;
        float fx = left + half + half / 2f;
        buttons[6].rect.set(fx - r, cy - r, fx + r, cy + r);

        textPaint.setTextSize(Math.max(14 * density, softH * 0.4f));
    }

    private static void setCell(Button b, float x, float y, float size, float gap) {
        b.rect.set(x + gap, y + gap, x + size - gap, y + size - gap);
    }

    // ---- drawing ----------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        synchronized (Host.front) {
            c.drawBitmap(Host.front, src, gameRect, pixelPaint);
        }
        for (Button b : buttons) {
            buttonPaint.setColor(b.down ? 0xFF6A6A6A : 0xFF2E2E2E);
            if (b.round) {
                c.drawOval(b.rect, buttonPaint);
            } else {
                c.drawRoundRect(b.rect, 10 * density, 10 * density, buttonPaint);
            }
            float ty = b.rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2;
            c.drawText(b.label, b.rect.centerX(), ty, textPaint);
        }
    }

    // ---- touch ------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        int idx = ev.getActionIndex();
        int id = ev.getPointerId(idx);
        float x = ev.getX(idx);
        float y = ev.getY(idx);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                onDown(id, x, y);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                onUp(id, x, y);
                break;
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < ev.getPointerCount(); i++) {
                    onUp(ev.getPointerId(i), Float.NaN, Float.NaN);
                }
                break;
            default:
                break;
        }
        return true;
    }

    private void onDown(int id, float x, float y) {
        for (Button b : buttons) {
            if (b.rect.contains(x, y)) {
                b.down = true;
                pointerKey.put(id, b.key);
                Host.press(b.key);
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                invalidate();
                return;
            }
        }
        if (gameRect.contains((int) x, (int) y) && gamePointer == -1) {
            gamePointer = id;
            downX = x;
            downY = y;
            gameKeyDown = 0;
            if (isBatting()) {
                gameKeyDown = Host.KEY_FIRE;
                Host.press(Host.KEY_FIRE);
            }
        }
    }

    private void onUp(int id, float x, float y) {
        Integer key = pointerKey.get(id);
        if (key != null) {
            pointerKey.remove(id);
            Host.release(key);
            for (Button b : buttons) {
                if (b.key == key) {
                    b.down = false;
                }
            }
            invalidate();
            return;
        }
        if (id != gamePointer) {
            return;
        }
        gamePointer = -1;
        if (gameKeyDown != 0) {
            Host.release(gameKeyDown);
            gameKeyDown = 0;
            return;
        }
        if (Float.isNaN(x)) {
            return;
        }
        int k = gestureKey(x, y);
        if (k != 0) {
            Host.press(k);
            Host.release(k);
        }
    }

    /** Swipe = arrow, bottom corner = soft key, otherwise the screen's "go on" key. */
    private int gestureKey(float x, float y) {
        float dx = x - downX;
        float dy = y - downY;
        float swipe = 28 * density;
        if (Math.abs(dx) > swipe || Math.abs(dy) > swipe) {
            if (Math.abs(dy) >= Math.abs(dx)) {
                return dy < 0 ? Host.KEY_UP : Host.KEY_DOWN;
            }
            return dx < 0 ? Host.KEY_LEFT : Host.KEY_RIGHT;
        }
        // soft key labels sit in the bottom corners of the picture
        float fx = (downX - gameRect.left) / gameRect.width();
        float fy = (downY - gameRect.top) / gameRect.height();
        if (fy > 0.88f && fx < 0.3f) {
            return Host.KEY_LSK;
        }
        if (fy > 0.88f && fx > 0.7f) {
            return Host.KEY_RSK;
        }
        switch (screen()) {
            case 23: // intro story: only "SKIP" (left soft key) moves on
            case 22: // match objective: only "OK"
            case 15: // settings: left soft key toggles sound
                return Host.KEY_LSK;
            case STATE_PLAYING:
                // end-of-match scoreboard: upload is gone, so a tap goes to the menu
                return subState() == SUB_STATE_SCOREBOARD ? Host.KEY_RSK : Host.KEY_FIRE;
            default:
                return Host.KEY_FIRE;
        }
    }

    private boolean isBatting() {
        return screen() == STATE_PLAYING && subState() != SUB_STATE_SCOREBOARD;
    }

    // the game's screen state lives in fields of the decompiled canvas class e
    private static int screen() {
        return readByte("f24a");
    }

    private static int subState() {
        return readByte("f30b");
    }

    private static int readByte(String name) {
        Object c = Host.canvas();
        if (c == null) {
            return -1;
        }
        try {
            Field f = "f24a".equals(name) ? screenField : subStateField;
            if (f == null) {
                f = c.getClass().getField(name);
                if ("f24a".equals(name)) {
                    screenField = f;
                } else {
                    subStateField = f;
                }
            }
            return f.getByte(c);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }
}
