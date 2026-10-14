package javax.microedition.lcdui;

import javax.microedition.midlet.MIDlet;

import gk.Host;

public class Display {
    private static final Display instance = new Display();

    public static Display getDisplay(MIDlet m) {
        return instance;
    }

    public void setCurrent(Displayable d) {
        // only the game canvas is ever shown; Forms and null are ignored
        if (d instanceof Canvas) {
            Host.setCanvas((Canvas) d);
        }
    }

    public void setCurrent(Alert alert, Displayable next) {
        Host.toast(alert.text);
        setCurrent(next);
    }
}
