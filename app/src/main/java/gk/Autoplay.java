package gk;

import android.util.Log;

import java.lang.reflect.Field;

import javax.microedition.lcdui.Canvas;

/**
 * Test hook for checking the whole match flow (win, unlock, save) without perfect human
 * timing. Only active when the app is launched with the "autoplay" intent extra.
 * The batting window is ball y in [0, 48], with the best hit at y = 24 (kb/code-map.md 5).
 */
final class Autoplay {
    private static Field screen, sub, ballY, swung;
    private static boolean holding;

    private Autoplay() {
    }

    static void frame(Canvas c) {
        try {
            if (screen == null) {
                Class<?> k = c.getClass();
                screen = k.getField("f24a");
                sub = k.getField("f30b");
                ballY = k.getDeclaredField("f90x");
                swung = k.getDeclaredField("f75h");
                ballY.setAccessible(true);
                swung.setAccessible(true);
            }
            if (holding) {
                Host.release(Host.KEY_FIRE);
                holding = false;
                return;
            }
            if (screen.getByte(c) != 8 || sub.getByte(c) != 5 || swung.getBoolean(c)) {
                return;
            }
            int y = ((Number) ballY.get(c)).intValue();
            if (y <= 36 && y >= 12) {
                Log.i("GK", "autoplay swing at y " + y);
                Host.press(Host.KEY_FIRE);
                holding = true;
            }
        } catch (ReflectiveOperationException e) {
            Log.e("GK", "autoplay off: " + e);
            Host.autoplay = false;
        }
    }
}
