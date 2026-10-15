package gk;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Named access to the few fields of the decompiled game that the port reads or writes
 * (field names are JADX's; see kb/code-map.md and kb/resume-and-direction.md).
 * All calls must come from the game thread, between two paint() calls.
 */
final class Game {
    static final int SCREEN_MENU = 6;
    static final int SCREEN_LOADING = 7;
    static final int SCREEN_PLAYING = 8;
    static final int SCREEN_MATCH_SELECT_LOAD = 14;
    static final int SCREEN_SETTING = 15;
    static final int SCREEN_MATCH_SELECT_NEW = 20;
    static final int SCREEN_OBJECTIVE = 22;
    static final int SCREEN_INTRO = 23;
    static final int SUB_BALL_IN_FLIGHT = 5;
    static final int SUB_SCOREBOARD = 11;

    private static Field screen, sub, match, unlocked, menuIndex, continueFlag, loadStep,
            shotType, shotPlayed, swung, sound, batsman, animG, animC, animD;
    private static Method soundStop;

    private Game() {
    }

    private static Field f(Class<?> k, String name) throws NoSuchFieldException {
        Field f = k.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static synchronized void init(Object e) throws ReflectiveOperationException {
        if (screen != null) {
            return;
        }
        Class<?> k = e.getClass();
        screen = f(k, "f24a");
        sub = f(k, "f30b");
        match = f(k, "f33d");
        unlocked = f(k, "f34e");
        menuIndex = f(k, "f112t");
        continueFlag = f(k, "m");          // static: "Continue" shown in the menu
        loadStep = f(k, "y");              // loading step; -1 = start from step 0
        shotType = f(k, "f103G");          // shot chosen for this delivery
        shotPlayed = f(k, "f102F");        // shot copied at the press, read at contact
        swung = f(k, "f75h");              // the one swing per ball has been used
        sound = f(k, "f23a");
        batsman = f(k, "a");
        Class<?> d = batsman.getType();
        animG = f(d, "g");
        animC = f(d, "c");
        animD = f(d, "d");
        soundStop = sound.getType().getDeclaredMethod("f");
        soundStop.setAccessible(true);
    }

    /** For the log: balls left, runs, lives, screen / sub-state. */
    static String summary(Object e) {
        StringBuilder b = new StringBuilder();
        for (String n : new String[]{"f36g", "f32a", "f35f", "f24a", "f30b"}) {
            try {
                Field f = e.getClass().getDeclaredField(n);
                f.setAccessible(true);
                b.append(n).append('=').append(f.get(e)).append(' ');
            } catch (ReflectiveOperationException ex) {
                b.append(n).append("=? ");
            }
        }
        return b.toString().trim();
    }

    private static Field layoutHeight, targetReached, ballsLeft;

    /** The camera's vertical centre for a canvas this tall (as the patched constructor sets it). */
    static void centreCamera(Object e, int height) throws ReflectiveOperationException {
        Field d = f(e.getClass(), "d");
        d.setInt(e, (Host.WIDTH >> 1) + ((height - 320) >> 1));
    }

    /** The game's own screen height variable (e.b); read by its drawing code every frame. */
    static void setLayoutHeight(Object e, int h) throws ReflectiveOperationException {
        if (layoutHeight == null) {
            layoutHeight = f(e.getClass(), "b");
        }
        layoutHeight.setInt(null, h);
    }

    /**
     * Shamvi's rule for the port: reaching the target ends the match. The original kept batting
     * until the balls ran out; here, once the game has shown "target reached" (f42f) the balls
     * left are set to 0 at the start of the next ball, so the game's own end-of-match check runs
     * its normal win sequence (unlock, score saved, win animation).
     */
    static void finishWhenTargetReached(Object e) throws ReflectiveOperationException {
        init(e);
        if (targetReached == null) {
            targetReached = f(e.getClass(), "f42f");
            ballsLeft = f(e.getClass(), "f36g");
        }
        if (screen.getByte(e) == SCREEN_PLAYING && sub.getByte(e) == 0
                && targetReached.getBoolean(e) && ballsLeft.getByte(e) > 0) {
            ballsLeft.setByte(e, (byte) 0);
        }
    }

    static int screen(Object e) throws ReflectiveOperationException {
        init(e);
        return screen.getByte(e);
    }

    static int sub(Object e) throws ReflectiveOperationException {
        init(e);
        return sub.getByte(e);
    }

    /** Put the game's own list cursor on a row: main menu f112t, Load Game match list f33d. */
    static void setCursor(Object e, int screen, int row) throws ReflectiveOperationException {
        init(e);
        if (screen == SCREEN_MENU) {
            menuIndex.setByte(e, (byte) row);
        } else if (screen == SCREEN_MATCH_SELECT_LOAD) {
            match.setByte(e, (byte) row);
        }
        // New Game's list (20) always starts at match 1, as in the original
    }

    static int menuIndex(Object e) throws ReflectiveOperationException {
        init(e);
        return menuIndex.getByte(e);
    }

    static int match(Object e) throws ReflectiveOperationException {
        init(e);
        return match.getByte(e);
    }

    static int unlockedMatch(Object e) throws ReflectiveOperationException {
        init(e);
        return unlocked.getByte(e);
    }

    static boolean continueShown(Object e) throws ReflectiveOperationException {
        init(e);
        return continueFlag.getBoolean(null);
    }

    /** A match exists that the player could go back to (playing, or paused behind "Continue"). */
    static boolean matchInProgress(Object e) throws ReflectiveOperationException {
        int s = screen(e);
        if (s == SCREEN_PLAYING) {
            return sub(e) != SUB_SCOREBOARD;
        }
        return continueShown(e);
    }

    /** Start loading a match the same way picking it from the menu does (loading step 0 builds it). */
    static void startLoading(Object e, int matchIndex) throws ReflectiveOperationException {
        init(e);
        Object s = sound.get(e);
        if (s != null) {
            soundStop.invoke(s);
        }
        match.setByte(e, (byte) matchIndex);
        loadStep.setByte(e, (byte) -1);
        screen.setByte(e, (byte) SCREEN_LOADING);
    }

    /**
     * Swipe-to-aim. Before the press: choose the shot the game will play. After the press but
     * before contact: switch the stroke the same way the press itself sets it (e.java ~1445).
     * Never turns an unplayable ball (shot type 0) into a hit.
     */
    static boolean aim(Object e, int type) throws ReflectiveOperationException {
        init(e);
        if (screen.getByte(e) != SCREEN_PLAYING || sub.getByte(e) != SUB_BALL_IN_FLIGHT) {
            return false;
        }
        if (!swung.getBoolean(e)) {
            if (shotType.getInt(e) == 0) {
                return false;
            }
            shotType.setInt(e, type);
            return true;
        }
        int played = shotPlayed.getInt(e);
        if (played == 0) {
            return false;       // the game judged this ball unplayable: stays a miss
        }
        if (played == type) {
            return true;        // already set (also stops the swing restarting every frame)
        }
        shotPlayed.setInt(e, type);
        Object bat = batsman.get(e);
        animG.setByte(bat, (byte) (2 + type));
        animC.setByte(bat, (byte) -1);
        animD.setByte(bat, (byte) 0);
        return true;
    }
}
