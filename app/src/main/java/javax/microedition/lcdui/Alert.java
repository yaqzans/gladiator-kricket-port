package javax.microedition.lcdui;

/** Shown as an Android toast by Display.setCurrent(Alert, Displayable). */
public class Alert extends Displayable {
    String text;

    public Alert(String title) {
        this.text = title;
    }

    public void setString(String s) {
        text = s;
    }

    public void setTimeout(int ms) {
    }

    public void setType(AlertType type) {
    }
}
