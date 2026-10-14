package javax.microedition.media;

import android.media.MediaPlayer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

import gk.Host;

/**
 * MIDI playback through Android's MediaPlayer, which plays .mid files natively.
 * MediaPlayer cannot read a stream, so each sound is written to the cache dir once.
 */
public class Player {
    public static final int UNREALIZED = 100;
    public static final int REALIZED = 200;
    public static final int PREFETCHED = 300;
    public static final int STARTED = 400;
    public static final int CLOSED = 0;

    private static final ArrayList<Player> all = new ArrayList<>();
    private static int fileCounter;

    private final byte[] data;
    private MediaPlayer mp;
    private boolean looping;
    private boolean started;
    private boolean resumeOnForeground;

    Player(byte[] data) {
        this.data = data;
        synchronized (all) {
            all.add(this);
        }
    }

    public synchronized void realize() throws MediaException {
        if (mp != null) {
            return;
        }
        try {
            File f = new File(Host.cacheDir(), "snd" + (fileCounter++) + ".mid");
            FileOutputStream out = new FileOutputStream(f);
            out.write(data);
            out.close();
            mp = new MediaPlayer();
            mp.setDataSource(f.getAbsolutePath());
            mp.prepare();
            mp.setLooping(looping);
            mp.setOnCompletionListener(p -> {
                synchronized (Player.this) {
                    if (!looping) {
                        started = false;
                    }
                }
            });
        } catch (IOException e) {
            throw new MediaException(e.toString());
        }
    }

    /** -1 loops forever; the game only uses 1 and -1. */
    public synchronized void setLoopCount(int count) {
        looping = count == -1;
        if (mp != null) {
            mp.setLooping(looping);
        }
    }

    public synchronized void start() throws MediaException {
        realize();
        mp.start();
        started = true;
    }

    /** MIDP stop() is a pause; playback resumes from the same spot. */
    public synchronized void stop() throws MediaException {
        if (mp != null && mp.isPlaying()) {
            mp.pause();
        }
        started = false;
        resumeOnForeground = false;
    }

    /** Microseconds, like MIDP. */
    public synchronized long setMediaTime(long micros) throws MediaException {
        realize();
        mp.seekTo((int) (micros / 1000));
        return micros;
    }

    public synchronized long getDuration() {
        if (mp == null) {
            return -1;
        }
        return mp.getDuration() * 1000L;
    }

    public synchronized int getState() {
        if (mp == null) {
            return UNREALIZED;
        }
        return started ? STARTED : PREFETCHED;
    }

    /** Called by gk.Host when the app goes to the background and comes back. */
    public static void pauseAll() {
        synchronized (all) {
            for (Player p : all) {
                synchronized (p) {
                    p.resumeOnForeground = p.started && p.mp != null && p.mp.isPlaying();
                    if (p.resumeOnForeground) {
                        p.mp.pause();
                    }
                }
            }
        }
    }

    public static void resumeAll() {
        synchronized (all) {
            for (Player p : all) {
                synchronized (p) {
                    if (p.resumeOnForeground && p.started && p.mp != null) {
                        p.mp.start();
                    }
                    p.resumeOnForeground = false;
                }
            }
        }
    }
}
