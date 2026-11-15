package android.util;
/** JVM stub. Prints only when QA_VERBOSE=1. */
public final class Log {
    private static final boolean ON = "1".equals(System.getenv("QA_VERBOSE"));
    private static int out(String level, String tag, String msg, Throwable tr) {
        if (ON) System.err.println("[" + level + "/" + tag + "] " + msg + (tr == null ? "" : " :: " + tr));
        return 0;
    }
    public static int d(String tag, String msg) { return out("D", tag, msg, null); }
    public static int i(String tag, String msg) { return out("I", tag, msg, null); }
    public static int w(String tag, String msg) { return out("W", tag, msg, null); }
    public static int w(String tag, String msg, Throwable tr) { return out("W", tag, msg, tr); }
    public static int e(String tag, String msg) { return out("E", tag, msg, null); }
    public static int e(String tag, String msg, Throwable tr) { return out("E", tag, msg, tr); }
}
