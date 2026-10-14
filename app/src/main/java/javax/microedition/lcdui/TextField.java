package javax.microedition.lcdui;

/** The name-entry form is never shown in the port; this only has to compile. */
public class TextField extends Item {
    private final String text;

    public TextField(String label, String text, int maxSize, int constraints) {
        this.text = text == null ? "" : text;
    }

    public String getString() {
        return text;
    }
}
