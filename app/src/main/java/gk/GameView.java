package gk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

/**
 * The game picture as large as the screen allows (sharp pixels, proportions kept), over a soft
 * blurred copy of itself that fills the rest of the screen. No button strip: the game is played
 * by touching the picture, like a phone game.
 *
 *  - In a match: touching anywhere swings the bat at that instant (the timing is everything,
 *    exactly as pressing 5 was). Swiping while doing it aims the shot (an extra on top of the
 *    original): down = straight back past the bowler, right / left = square, diagonals between.
 *    A small pause button (top right) is the phone's right soft key, the game's own pause.
 *  - In menus: tap an item to pick it, swipe for the arrow keys, tap the bottom corners for the
 *    soft keys (their labels are drawn there), tap elsewhere for the key that screen needs.
 */
public class GameView extends View {
    // menu geometry in game pixels (240x320), measured from the game's own screens
    private static final int MENU_FIRST_Y = 127, MENU_STEP = 28, MENU_HALF = 13;
    private static final int MATCH_FIRST_Y = 183;
    private static final int ITEM_LEFT = 50, ITEM_RIGHT = 190;

    private final float dp;
    private final Paint sharp = new Paint();
    private final Paint smooth = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint ui = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect gameRect = new Rect();
    private final Rect coverRect = new Rect();
    private final Rect pauseRect = new Rect();
    private final Rect src = new Rect(0, 0, Host.WIDTH, Host.HEIGHT);
    private final Rect tinyRect = new Rect(0, 0, 12, 16);
    private final Rect preRect = new Rect();
    private int insetL, insetT, insetR, insetB;

    // sharp-bilinear: nearest-neighbour up by a whole factor, then smooth to the final size,
    // so every game pixel comes out the same size (4.5x would otherwise mix 4- and 5-pixel rows)
    private Bitmap pre;
    private Canvas preCanvas;
    private final Bitmap tiny = Bitmap.createBitmap(12, 16, Bitmap.Config.ARGB_8888);
    private final Canvas tinyCanvas = new Canvas(tiny);

    // one finger drives the game at a time
    private int pointer = -1;
    private float downX, downY;
    private boolean batting;
    private boolean aimed;
    private boolean onPause;
    private int heldKey;

    public GameView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        sharp.setFilterBitmap(false);
        smooth.setFilterBitmap(true);
        setBackgroundColor(0xFF000000);
    }

    // ---- layout -----------------------------------------------------------

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        insetL = insets.getSystemWindowInsetLeft();
        insetT = insets.getSystemWindowInsetTop();
        insetR = insets.getSystemWindowInsetRight();
        insetB = insets.getSystemWindowInsetBottom();
        if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutout cut = insets.getDisplayCutout();
            if (cut != null) {
                insetL = Math.max(insetL, cut.getSafeInsetLeft());
                insetT = Math.max(insetT, cut.getSafeInsetTop());
                insetR = Math.max(insetR, cut.getSafeInsetRight());
                insetB = Math.max(insetB, cut.getSafeInsetBottom());
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
        int aw = w - insetL - insetR, ah = h - insetT - insetB;
        float scale = Math.min(aw / (float) Host.WIDTH, ah / (float) Host.HEIGHT);
        int gw = Math.round(Host.WIDTH * scale), gh = Math.round(Host.HEIGHT * scale);
        int gx = insetL + (aw - gw) / 2, gy = insetT + (ah - gh) / 2;
        gameRect.set(gx, gy, gx + gw, gy + gh);

        int k = Math.max(1, Math.min(3, (int) Math.floor(scale)));
        if (pre == null || pre.getWidth() != Host.WIDTH * k) {
            pre = Bitmap.createBitmap(Host.WIDTH * k, Host.HEIGHT * k, Bitmap.Config.ARGB_8888);
            preCanvas = new Canvas(pre);
            preRect.set(0, 0, pre.getWidth(), pre.getHeight());
        }

        // backdrop: the picture's aspect, covering the whole screen
        float s = Math.max(w / 12f, h / 16f);
        int cw = Math.round(12 * s), ch = Math.round(16 * s);
        coverRect.set((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2);

        // pause button: above the picture if there is room, otherwise in its top-right corner
        int size = Math.round(40 * dp), m = Math.round(12 * dp);
        int top = gy >= size + 2 * m ? gy - size - m : gy + m;
        int right = Math.min(w - insetR, gameRect.right) - m;
        pauseRect.set(right - size, top, right, top + size);
    }

    // ---- drawing ----------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        if (pre == null) {
            return;
        }
        synchronized (Host.front) {
            tinyCanvas.drawBitmap(Host.front, src, tinyRect, smooth);
            preCanvas.drawBitmap(Host.front, src, preRect, sharp);
        }
        // the picture blurred to a glow and darkened, filling the screen around it
        c.drawBitmap(tiny, null, coverRect, smooth);
        c.drawColor(0x99000000);
        c.drawBitmap(pre, null, gameRect, smooth);
        if (isPlaying()) {
            drawPause(c);
        }
    }

    private void drawPause(Canvas c) {
        float cx = pauseRect.exactCenterX(), cy = pauseRect.exactCenterY(), r = pauseRect.width() / 2f;
        ui.setStyle(Paint.Style.FILL);
        ui.setColor(onPause ? 0x99FFFFFF : 0x40000000);
        c.drawCircle(cx, cy, r, ui);
        ui.setStyle(Paint.Style.STROKE);
        ui.setStrokeWidth(3.5f * dp);
        ui.setColor(0x55000000);
        c.drawCircle(cx, cy, r, ui);
        ui.setStrokeWidth(1.8f * dp);
        ui.setColor(0xCCFFFFFF);
        c.drawCircle(cx, cy, r, ui);
        ui.setStyle(Paint.Style.FILL);
        ui.setColor(0xE6FFFFFF);
        float bw = 3.5f * dp, bh = 13 * dp, off = 4.5f * dp;
        c.drawRoundRect(cx - off - bw / 2, cy - bh / 2, cx - off + bw / 2, cy + bh / 2, dp, dp, ui);
        c.drawRoundRect(cx + off - bw / 2, cy - bh / 2, cx + off + bw / 2, cy + bh / 2, dp, dp, ui);
    }

    // ---- touch ------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        int idx = ev.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (pointer == -1) {
                    pointer = ev.getPointerId(idx);
                    onDown(ev.getX(idx), ev.getY(idx));
                }
                break;
            case MotionEvent.ACTION_MOVE: {
                int i = ev.findPointerIndex(pointer);
                if (i >= 0) {
                    onMove(ev.getX(i), ev.getY(i));
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (ev.getPointerId(idx) == pointer) {
                    onUp(ev.getX(idx), ev.getY(idx), false);
                    pointer = -1;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                if (pointer != -1) {
                    onUp(0, 0, true);
                    pointer = -1;
                }
                break;
            default:
                break;
        }
        return true;
    }

    private void onDown(float x, float y) {
        downX = x;
        downY = y;
        aimed = false;
        onPause = false;
        batting = false;
        heldKey = 0;
        Host.setAim(0);
        if (isPlaying()) {
            if (pauseRect.contains((int) x, (int) y)) {
                onPause = true;
                heldKey = Host.KEY_RSK;
                Host.press(heldKey);
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                invalidate();
                return;
            }
            // the swing happens now, on touch, so the timing is the player's
            batting = true;
            heldKey = Host.KEY_FIRE;
            Host.press(heldKey);
        }
    }

    private void onMove(float x, float y) {
        if (!batting || aimed) {
            return;
        }
        float dx = x - downX, dy = y - downY;
        if (Math.hypot(dx, dy) < 18 * dp) {
            return;
        }
        aimed = true;
        int shot = shotForSwipe(dx, dy);
        if (shot != 0) {
            Host.setAim(shot);
        }
    }

    private void onUp(float x, float y, boolean cancelled) {
        if (heldKey != 0) {
            Host.release(heldKey);
            heldKey = 0;
            if (onPause) {
                onPause = false;
                invalidate();
            }
            return;
        }
        if (cancelled) {
            return;
        }
        if (Host.busy()) {
            return;   // the previous tap is still moving the cursor; a second one would misfire
        }
        int[] keys = menuKeys(x, y);
        if (keys != null) {
            Host.pressSequence(keys);
        }
    }

    /**
     * Shot type for a swipe direction (kb/resume-and-direction.md, table of i()): the batsman
     * faces down the screen toward the bowler. Swiping straight up (toward the keeper) is not a
     * shot, so it leaves the game's own choice.
     */
    static int shotForSwipe(float dx, float dy) {
        double ang = Math.toDegrees(Math.atan2(dy, dx));     // screen: 0 right, 90 down
        if (ang < 0) {
            ang += 360;
        }
        int sector = (int) Math.floor((ang + 22.5) / 45) % 8;
        switch (sector) {
            case 0: return 6;    // right: square, 330-15 degrees
            case 1: return 7;    // down-right: 15-80
            case 2: return 5;    // down: straight down the ground, 60-120
            case 3: return 2;    // down-left: 105-160
            case 4: return 8;    // left: square, 150-210
            case 5: return 1;    // up-left: square to fine, 150-225
            case 7: return 4;    // up-right: square / just behind, 345-360
            default: return 0;   // straight up: no aim, the game's own shot
        }
    }

    // ---- menus ------------------------------------------------------------

    /** Keys for a tap or swipe outside a match (null = nothing). */
    private int[] menuKeys(float x, float y) {
        float dx = x - downX, dy = y - downY;
        if (Math.hypot(dx, dy) > 28 * dp) {
            if (Math.abs(dy) >= Math.abs(dx)) {
                return new int[]{dy < 0 ? Host.KEY_UP : Host.KEY_DOWN};
            }
            return new int[]{dx < 0 ? Host.KEY_LEFT : Host.KEY_RIGHT};
        }
        // tap position in game pixels
        float gx = (downX - gameRect.left) * Host.WIDTH / gameRect.width();
        float gy = (downY - gameRect.top) * Host.HEIGHT / gameRect.height();
        boolean bottom = gy > Host.HEIGHT * 0.88f && gy <= Host.HEIGHT;
        if (bottom && gx >= 0 && gx < Host.WIDTH * 0.3f) {
            return new int[]{Host.KEY_LSK};
        }
        if (bottom && gx > Host.WIDTH * 0.7f && gx <= Host.WIDTH) {
            return new int[]{Host.KEY_RSK};
        }
        Object e = Host.canvas();
        if (e == null) {
            return new int[]{Host.KEY_FIRE};
        }
        try {
            switch (Game.screen(e)) {
                case Game.SCREEN_MENU:
                    return pickRow(gx, gy, MENU_FIRST_Y, Game.continueShown(e) ? 0 : 1, 6, Game.menuIndex(e));
                case Game.SCREEN_MATCH_SELECT_LOAD:
                    return pickRow(gx, gy, MATCH_FIRST_Y, 0, Game.unlockedMatch(e), Game.match(e));
                case Game.SCREEN_MATCH_SELECT_NEW:
                    return pickRow(gx, gy, MATCH_FIRST_Y, 0, 0, 0);
                case Game.SCREEN_INTRO:       // story: only "SKIP" (left soft key) moves on
                case Game.SCREEN_OBJECTIVE:   // match objective: only "OK"
                case Game.SCREEN_SETTING:     // the left soft key toggles the sound
                    return new int[]{Host.KEY_LSK};
                case Game.SCREEN_PLAYING:
                    // end-of-match scoreboard: online upload is gone, MENU is the way on
                    return new int[]{Host.KEY_RSK};
                default:
                    return new int[]{Host.KEY_FIRE};
            }
        } catch (ReflectiveOperationException ex) {
            return new int[]{Host.KEY_FIRE};
        }
    }

    /**
     * Tapping a list item: move the game's own cursor there with the arrow keys, then select.
     * Taps between items or on locked ones do nothing.
     */
    private static int[] pickRow(float gx, float gy, int firstY, int firstRow, int lastRow, int current) {
        if (gx < ITEM_LEFT || gx > ITEM_RIGHT) {
            return null;
        }
        int row = Math.round((gy - firstY) / MENU_STEP);
        if (Math.abs(gy - (firstY + row * MENU_STEP)) > MENU_HALF || row < firstRow || row > lastRow) {
            return null;
        }
        int steps = row - current;
        int[] keys = new int[Math.abs(steps) + 1];
        for (int i = 0; i < Math.abs(steps); i++) {
            keys[i] = steps > 0 ? Host.KEY_DOWN : Host.KEY_UP;
        }
        keys[keys.length - 1] = Host.KEY_FIRE;
        return keys;
    }

    private static boolean isPlaying() {
        Object e = Host.canvas();
        if (e == null) {
            return false;
        }
        try {
            return Game.screen(e) == Game.SCREEN_PLAYING && Game.sub(e) != Game.SUB_SCOREBOARD;
        } catch (ReflectiveOperationException ex) {
            return false;
        }
    }
}
