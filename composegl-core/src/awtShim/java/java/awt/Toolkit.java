package java.awt;
/** Shim: always headless, so callers fall into their HeadlessException branch. */
public abstract class Toolkit {
    public static Toolkit getDefaultToolkit() { throw new HeadlessException(); }
    public java.awt.datatransfer.Clipboard getSystemClipboard() { throw new HeadlessException(); }
    public boolean getLockingKeyState(int keyCode) { throw new UnsupportedOperationException(); }
}
