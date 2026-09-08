package java.awt;
/** Shim: the exception Compose's desktop clipboard catches when there is no display. */
public class HeadlessException extends UnsupportedOperationException {
    public HeadlessException() { super("no AWT on this runtime"); }
    public HeadlessException(String message) { super(message); }
}
