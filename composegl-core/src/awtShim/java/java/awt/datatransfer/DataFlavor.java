package java.awt.datatransfer;
public class DataFlavor {
    public static final DataFlavor stringFlavor = new DataFlavor();
    public DataFlavor() { }
    public DataFlavor(Class<?> representationClass, String humanPresentableName) { }
    public String getMimeType() { return "application/x-shim"; }
}
