package java.awt.datatransfer;
public class StringSelection implements Transferable, ClipboardOwner {
    private final String data;
    public StringSelection(String data) { this.data = data; }
    public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[] { DataFlavor.stringFlavor }; }
    public boolean isDataFlavorSupported(DataFlavor flavor) { return flavor == DataFlavor.stringFlavor; }
    public Object getTransferData(DataFlavor flavor) { return data; }
    public void lostOwnership(Clipboard clipboard, Transferable contents) { }
}
