package app.aemu.stub;

/** stdout/stderr of the service land in run/aemu-bt.log on the host. */
final class BtLog {
    private BtLog() { }
    static void i(String m) { System.out.println("aemu-bt: " + m); }
    static void w(String m, Throwable t) {
        System.out.println("aemu-bt: " + m + (t != null ? ": " + t : ""));
    }
}
