package android.hardware;
/** JVM stub with the same public fields as the platform class. */
public class SensorEvent {
    public final float[] values;
    public Sensor sensor;
    public int accuracy;
    public long timestamp;
    public SensorEvent(Sensor sensor, long timestamp, float[] values) { this.sensor = sensor; this.timestamp = timestamp; this.values = values; }
}
