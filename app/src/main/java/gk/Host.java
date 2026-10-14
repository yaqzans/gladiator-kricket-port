package gk;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Process;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;
import javax.microedition.media.Player;

/**
 * Glue between the MIDP shim and Android: frame rendering, the key queue, pause and resume,
 * the saved match, exit. The game thread calls renderFrame(); the UI thread queues keys and
 * draws the last frame.
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

    private static final String TAG = "GK";
    private static final int SNAPSHOT_VERSION = 2;

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
    /** Key sequences from tapping a menu item: one key per frame, released the next frame. */
    private static final ConcurrentLinkedQueue<Integer> macro = new ConcurrentLinkedQueue<>();
    private static volatile int macroHeld;

    private static final Object pauseLock = new Object();
    private static boolean paused;
    private static boolean parked;
    private static volatile boolean interrupted;

    private static volatile int aim;

    // resume after the process was killed: 0 none, 1 waiting for the menu, 2 loading the match
    private static int restorePhase;
    private static byte[] restoreData;
    private static int restoreMatch;
    private static int restoreWait;

    /** Debug builds only (src/debug): a test bot that swings at the sweet spot. */
    private static Method autoplay;
    /** Debug builds only: shot type the test bot aims every swing at (0 = game's choice). */
    static int debugAim;

    private static long lastFrameAt;
    private static long frameTimeSum;
    private static int frameTimeCount;

    private Host() {
    }

    static void attach(Activity a, GameView v, boolean wantAutoplay) {
        activity = a;
        view = v;
        if (wantAutoplay) {
            try {
                autoplay = Class.forName("gk.Autoplay").getDeclaredMethod("frame", Canvas.class);
            } catch (ReflectiveOperationException e) {
                autoplay = null;   // release build: the bot is not in the APK
            }
        }
    }

    /** Before the game starts: pick up a match saved by a previous run of the app. */
    static void armRestore() {
        byte[] data = readFile(snapshotFile());
        if (data == null || data.length < 8) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readInt() != SNAPSHOT_VERSION) {
                snapshotFile().delete();
                return;
            }
            restoreMatch = in.readByte();
        } catch (IOException e) {
            snapshotFile().delete();
            return;
        }
        restoreData = data;
        restorePhase = 1;
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

    private static File snapshotFile() {
        return new File(activity.getFilesDir(), "match.snapshot");
    }

    // ---- keys -------------------------------------------------------------

    static void press(int key) {
        keyQueue.add(new int[]{1, key});
    }

    static void release(int key) {
        keyQueue.add(new int[]{0, key});
    }

    /** A tapped menu move is still being played into the game. */
    static boolean busy() {
        return macroHeld != 0 || !macro.isEmpty();
    }

    static void pressSequence(int... keys) {
        for (int k : keys) {
            macro.add(k);
        }
    }

    /** Swipe-to-aim: the shot type the next / current swing should play (0 = none). */
    static void setAim(int shotType) {
        aim = shotType;
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
        if (macroHeld != 0) {
            c.callKeyReleased(macroHeld);
            macroHeld = 0;
        } else {
            Integer k = macro.poll();
            if (k != null) {
                c.callKeyPressed(k);
                macroHeld = k;
            }
        }
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
        if (restorePhase != 0) {
            stepRestore(c);
        }
        if (interrupted) {
            interrupted = false;
            // a call or another app took the sound: what a call did on the original phone
            c.callHideNotify();
        }
        if (autoplay != null) {
            try {
                autoplay.invoke(null, c);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        applyAim(c);
        deliverKeys(c);
        graphics.reset(WIDTH, HEIGHT);
        try {
            c.callPaint(graphics);
        } catch (RuntimeException e) {
            Log.e(TAG, "paint failed, skipping frame", e);
        }
        synchronized (front) {
            new android.graphics.Canvas(front).drawBitmap(back, 0, 0, null);
        }
        if (view != null) {
            view.postInvalidate();
        }
        logFrameTime();
    }

    private static void applyAim(Canvas c) {
        int a = aim;
        if (a == 0) {
            return;
        }
        try {
            boolean inFlight = Game.screen(c) == Game.SCREEN_PLAYING
                    && Game.sub(c) == Game.SUB_BALL_IN_FLIGHT;
            if (inFlight) {
                Game.aim(c, a);
            } else {
                // a swipe made after contact or between balls must not aim the next delivery
                aim = 0;
            }
        } catch (ReflectiveOperationException e) {
            aim = 0;
        }
    }

    /** Average frame time every 100 frames, to check the game runs at its original ~95 ms. */
    private static void logFrameTime() {
        long now = System.currentTimeMillis();
        if (lastFrameAt != 0) {
            frameTimeSum += now - lastFrameAt;
            frameTimeCount++;
            if (frameTimeCount == 100) {
                Log.i(TAG, "avg frame ms " + (frameTimeSum / 100.0));
                frameTimeSum = 0;
                frameTimeCount = 0;
            }
        }
        lastFrameAt = now;
    }

    // ---- saved match ------------------------------------------------------

    /** Game thread. Saves the match exactly as it is, or removes the save if there is none. */
    private static void saveSnapshot(Canvas c) {
        File f = snapshotFile();
        try {
            if (Game.screen(c) == Game.SCREEN_LOADING) {
                return;   // Continue was just picked: keep the save that exists until the match is up
            }
            if (!Game.matchInProgress(c)) {
                f.delete();
                return;
            }
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp);
                 DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(fos))) {
                out.writeInt(SNAPSHOT_VERSION);
                out.writeByte(Game.match(c));
                Snapshot.write(out, c);
                out.flush();
                fos.getFD().sync();
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
            Log.i(TAG, "match saved, " + f.length() + " bytes: " + Game.summary(c));
        } catch (IOException | ReflectiveOperationException | RuntimeException e) {
            // an old save would bring back the wrong match; better none
            Log.e(TAG, "could not save the match", e);
            f.delete();
        }
    }

    /**
     * After a cold start with a saved match: as soon as the game reaches its intro or menu,
     * load that match the normal way (so every player and image exists), then write the saved
     * state over it and pause it, exactly as the app left it. The menu then offers "Continue".
     */
    private static void stepRestore(Canvas c) {
        try {
            int s = Game.screen(c);
            if (restorePhase == 1) {
                if (s == Game.SCREEN_INTRO || s == Game.SCREEN_MENU) {
                    Game.startLoading(c, restoreMatch);
                    restorePhase = 2;
                }
                return;
            }
            if (s != Game.SCREEN_PLAYING) {
                if (++restoreWait > 600) {          // ~60 s: loading never finished
                    Log.e(TAG, "saved match did not load, starting normally");
                    restorePhase = 0;
                    restoreData = null;
                    snapshotFile().delete();
                }
                return;
            }
            restorePhase = 0;
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(restoreData));
            in.readInt();
            in.readByte();
            Snapshot.restore(in, c);
            restoreData = null;
            if (Game.screen(c) == Game.SCREEN_PLAYING) {
                c.callHideNotify();
            }
            Log.i(TAG, "match restored: " + Game.summary(c));
        } catch (IOException | ReflectiveOperationException | RuntimeException e) {
            // half-written game state is worse than none: start the app over without the save
            Log.e(TAG, "could not restore the match", e);
            snapshotFile().delete();
            exitProcess();
        }
    }

    // ---- lifecycle --------------------------------------------------------

    /** Game thread, between frames: save, pause the match like a phone call did, then wait. */
    public static void waitWhilePaused() {
        synchronized (pauseLock) {
            if (!paused) {
                return;
            }
        }
        // the save can take a moment (fsync); do it without holding the lock the UI thread waits on
        Canvas c = canvas;
        if (c != null && restorePhase == 0) {
            saveSnapshot(c);
            // in a match this drops to the menu with "Continue", like a phone call did
            c.callHideNotify();
        }
        synchronized (pauseLock) {
            parked = true;
            pauseLock.notifyAll();
            while (paused) {
                try {
                    pauseLock.wait();
                } catch (InterruptedException e) {
                    break;
                }
            }
            parked = false;
        }
        lastFrameAt = 0;
    }

    /** UI thread (onPause). Waits up to 1.5 s for the save, since Android may kill the app next. */
    static void pause() {
        Player.pauseAll();
        synchronized (pauseLock) {
            paused = true;
            long until = System.currentTimeMillis() + 1500;
            while (!parked && System.currentTimeMillis() < until) {
                try {
                    pauseLock.wait(100);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    static void resume() {
        Player.resumeAll();
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
    }

    /** Audio focus lost while the app stays on screen (an incoming call banner, a video...). */
    static void interrupt() {
        Player.pauseAll();
        interrupted = true;
    }

    static void interruptionOver() {
        Player.resumeAll();
    }

    public static void toast(final String text) {
        activity.runOnUiThread(() -> Toast.makeText(activity, text, Toast.LENGTH_SHORT).show());
    }

    /**
     * The game's Exit menu item (game thread). A match left behind "Continue" is saved first,
     * so it can be picked up next time. Its static state can't be reset cleanly, so end the process.
     */
    public static void exit() {
        Canvas c = canvas;
        if (c != null && restorePhase == 0) {
            saveSnapshot(c);
        }
        exitProcess();
    }

    private static void exitProcess() {
        activity.runOnUiThread(() -> {
            activity.finishAndRemoveTask();
            Process.killProcess(Process.myPid());
        });
    }

    private static byte[] readFile(File f) {
        if (!f.exists()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) {
                    return null;
                }
                off += n;
            }
            return b;
        } catch (IOException e) {
            return null;
        }
    }
}
