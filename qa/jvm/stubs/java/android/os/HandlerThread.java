package android.os;
/** JVM stub: a plain thread whose run() returns at once. */
public class HandlerThread extends Thread {
    private final Looper looper = new Looper();
    public HandlerThread(String name) { super(name); setDaemon(true); }
    public Looper getLooper() { return looper; }
    public boolean quitSafely() { return true; }
}
