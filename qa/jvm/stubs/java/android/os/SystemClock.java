package android.os;
/** JVM stub backed by the real monotonic clock. */
public final class SystemClock {
    public static long elapsedRealtimeNanos() { return System.nanoTime(); }
    public static long elapsedRealtime() { return System.nanoTime() / 1_000_000L; }
}
