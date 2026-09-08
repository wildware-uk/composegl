package java.awt;
/** Shim: Compose's desktop PointerIcon actuals wrap predefined AWT cursors. */
public class Cursor {
    public static final int DEFAULT_CURSOR = 0;
    public static final int CROSSHAIR_CURSOR = 1;
    public static final int TEXT_CURSOR = 2;
    public static final int WAIT_CURSOR = 3;
    public static final int HAND_CURSOR = 12;
    public static final int MOVE_CURSOR = 13;
    private final int type;
    public Cursor(int type) { this.type = type; }
    public static Cursor getPredefinedCursor(int type) { return new Cursor(type); }
    public int getType() { return type; }
}
