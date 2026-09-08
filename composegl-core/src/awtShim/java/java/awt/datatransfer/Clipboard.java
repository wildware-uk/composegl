package java.awt.datatransfer;
public class Clipboard {
    public synchronized void setContents(Transferable contents, ClipboardOwner owner) { }
    public synchronized Transferable getContents(Object requestor) { return null; }
}
