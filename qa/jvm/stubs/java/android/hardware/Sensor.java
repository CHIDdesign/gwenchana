package android.hardware;
/** JVM stub. Public constructor so the bench can build events (the real one is hidden). */
public final class Sensor {
    public static final int TYPE_ACCELEROMETER = 1;
    public static final int TYPE_GYROSCOPE = 4;
    private final int type;
    public Sensor(int type) { this.type = type; }
    public int getType() { return type; }
}
