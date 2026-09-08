package java.awt.datatransfer;
public interface ClipboardOwner {
    void lostOwnership(Clipboard clipboard, Transferable contents);
}
