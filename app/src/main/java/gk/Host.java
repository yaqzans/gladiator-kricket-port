package gk;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Process;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;
import javax.microedition.media.Player;

/**
 * Glue between the MIDP shim and Android: frame rendering, the key queue, pause, exit.
 * The game thread calls renderFrame(); the UI thread queues keys and draws the last frame.
 */
public final class Host {
    public static final int WIDTH = 240;
    public static final int HEIGHT = 320;

    // MIDP key codes the game understands (kb/code-map.md section 5)
    public static final int KEY_UP = -1;
    public static final int KEY_DOWN = -2;
    public static final int KEY_LEFT = -3;
    public static final int KEY_RIGHT = -4;
    public static final int KEY_FIRE = -5;
    public static final int KEY_LSK = -6;
    public static final int KEY_RSK = -7;

    private static Activity activity;
    private static GameView view;
    private static volatile Canvas canvas;

    private static final Bitmap back = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
    private static final Graphics graphics = new Graphics(back);
    /** Last finished frame; the view draws this one. Guarded by itself. */
    static final Bitmap front = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);

    private static final ConcurrentLinkedQueue<int[]> keyQueue = new ConcurrentLinkedQueue<>();
    private static final ArrayList<Integer> deferredReleases = new ArrayList<>();
    private static final int[] pressedOnFrame = new int[16];
    private static int frameNo;

    private static final Object pauseLock = new Object();
    private static boolean paused;

    /** Test hook only: launched with --ez autoplay true, swings at the sweet spot. */
    static boolean autoplay;

    private static long lastFrameAt;
    private static long frameTimeSum;
    private static int frameTimeCount;

    private Host() {
    }

    static void attach(Activity a, GameView v) {
        activity = a;
        view = v;
    }

    public static void setCanvas(Canvas c) {
        canvas = c;
    }

    static Canvas canvas() {
        return canvas;
    }

    public static File cacheDir() {
        return activity.getCacheDir();
    }

    public static File filesDir() {
        return activity.getFilesDir();
    }

    // ---- keys -------------------------------------------------------------

    static void press(int key) {
        keyQueue.add(new int[]{1, key});
    }

    static void release(int key) {
        keyQueue.add(new int[]{0, key});
    }

    /**
     * The game only notices a key that is down when paint() starts (once per ~95 ms frame).
     * So a release that arrives in the same frame as its press waits one frame.
     */
    private static void deliverKeys(Canvas c) {
        frameNo++;
        for (Integer k : deferredReleases) {
            c.callKeyReleased(k);
        }
        deferredReleases.clear();
        int[] ev;
        while ((ev = keyQueue.poll()) != null) {
            int key = ev[1];
            int slot = -key & 15;
            if (ev[0] == 1) {
                // a newer press cancels a release still waiting from this frame
                deferredReleases.remove(Integer.valueOf(key));
                c.callKeyPressed(key);
                pressedOnFrame[slot] = frameNo;
            } else if (pressedOnFrame[slot] == frameNo) {
                deferredReleases.add(key);
            } else {
                c.callKeyReleased(key);
            }
        }
    }

    // ---- frames -----------------------------------------------------------

    public static void renderFrame(Canvas c) {
        if (autoplay) {
            Autoplay.frame(c);
        }
        deliverKeys(c);
        graphics.reset(WIDTH, HEIGHT);
        try {
            c.callPaint(graphics);
        } catch (RuntimeException e) {
            Log.e("GK", "paint failed, skipping frame", e);
        }
        synchronized (front) {
            new android.graphics.Canvas(front).drawBitmap(back, 0, 0, null);
        }
        if (view != null) {
            view.postInvalidate();
        }
        logFrameTime();
    }

    /** Average frame time every 100 frames, to check the game runs at its original ~95 ms. */
    private static void logFrameTime() {
        long now = System.currentTimeMillis();
        if (lastFrameAt != 0) {
            frameTimeSum += now - lastFrameAt;
            frameTimeCount++;
            if (frameTimeCount == 100) {
                Log.i("GK", "avg frame ms " + (frameTimeSum / 100.0));
                frameTimeSum = 0;
                frameTimeCount = 0;
            }
        }
        lastFrameAt = now;
    }

    // ---- lifecycle --------------------------------------------------------

    /** Runs on the game thread between frames, so hideNotify never races a paint(). */
    public static void waitWhilePaused() {
        synchronized (pauseLock) {
            if (!paused) {
                return;
            }
            Canvas c = canvas;
            if (c != null) {
                // in a match this drops to the menu with "Continue", like a phone call did
                c.callHideNotify();
            }
            while (paused) {
                try {
                    pauseLock.wait();
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
        lastFrameAt = 0;
    }

    static void pause() {
        synchronized (pauseLock) {
            paused = true;
        }
        Player.pauseAll();
    }

    static void resume() {
        Player.resumeAll();
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
    }

    public static void toast(final String text) {
        activity.runOnUiThread(() -> Toast.makeText(activity, text, Toast.LENGTH_SHORT).show());
    }

    /** The game's Exit menu item. Its static state can't be reset cleanly, so end the process. */
    public static void exit() {
        activity.runOnUiThread(() -> {
            activity.finishAndRemoveTask();
            Process.killProcess(Process.myPid());
        });
    }
}
