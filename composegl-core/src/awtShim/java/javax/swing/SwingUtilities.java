package javax.swing;
/** Shim: skiko's desktop main dispatcher posts through Swing. Run inline instead. */
public class SwingUtilities {
    public static void invokeLater(Runnable doRun) { doRun.run(); }
    public static boolean isEventDispatchThread() { return true; }
}
