package javax.microedition.lcdui;

/** Never shown; Display.setCurrent ignores anything that isn't the game Canvas. */
public class Form extends Displayable {
    public Form(String title) {
    }

    public int append(Item item) {
        return 0;
    }
}
