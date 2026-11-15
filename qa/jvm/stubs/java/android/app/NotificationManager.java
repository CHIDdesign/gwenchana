package android.app;
/** JVM stub. */
public class NotificationManager {
    public final java.util.List<Integer> cancelled = new java.util.concurrent.CopyOnWriteArrayList<>();
    public void cancel(int id) { cancelled.add(id); }
}
