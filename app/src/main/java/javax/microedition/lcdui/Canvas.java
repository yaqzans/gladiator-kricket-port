package javax.microedition.lcdui;

import gk.Host;

/**
 * The game calls repaint() then serviceRepaints() once per frame from its own thread.
 * serviceRepaints() does the whole frame right there: deliver queued touch keys,
 * call paint() into the 240x320 frame bitmap, hand the frame to the view.
 */
public abstract class Canvas extends Displayable {
    public static final int UP = 1;
    public static final int DOWN = 6;
    public static final int LEFT = 2;
    public static final int RIGHT = 5;
    public static final int FIRE = 8;

    private volatile boolean dirty;

    protected Canvas() {
    }

    public int getWidth() {
        return Host.WIDTH;
    }

    public int getHeight() {
        return Host.HEIGHT;
    }

    public void setFullScreenMode(boolean mode) {
    }

    public final void repaint() {
        dirty = true;
    }

    public final void serviceRepaints() {
        Host.waitWhilePaused();
        if (!dirty) {
            return;
        }
        dirty = false;
        Host.renderFrame(this);
    }

    protected abstract void paint(Graphics g);

    /** Lets gk.Host call the protected paint(). */
    public final void callPaint(Graphics g) {
        paint(g);
    }

    protected void keyPressed(int keyCode) {
    }

    protected void keyReleased(int keyCode) {
    }

    public final void callKeyPressed(int keyCode) {
        keyPressed(keyCode);
    }

    public final void callKeyReleased(int keyCode) {
        keyReleased(keyCode);
    }

    protected void hideNotify() {
    }

    protected void showNotify() {
    }

    public final void callHideNotify() {
        hideNotify();
    }
}
