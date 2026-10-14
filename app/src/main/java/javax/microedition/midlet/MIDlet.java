package javax.microedition.midlet;

import javax.microedition.io.ConnectionNotFoundException;

import gk.Host;

/** Just enough of MIDP's MIDlet for GC: lifecycle hooks and exit. */
public abstract class MIDlet {

    protected MIDlet() {
    }

    protected abstract void startApp();

    protected abstract void pauseApp();

    protected abstract void destroyApp(boolean unconditional);

    public final void notifyPaused() {
    }

    public final void notifyDestroyed() {
        Host.exit();
    }

    /** The only URL the game asks for is a dead high-score page, so never open anything. */
    public final boolean platformRequest(String url) throws ConnectionNotFoundException {
        return false;
    }
}
