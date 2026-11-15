package android.os;
/** JVM stub. Sensor callbacks are delivered by the test's own thread instead of a Looper. */
public class Handler {
    public Handler(Looper looper) {}
    public boolean postDelayed(Runnable r, long delayMillis) { throw new UnsupportedOperationException("stub"); }
}
