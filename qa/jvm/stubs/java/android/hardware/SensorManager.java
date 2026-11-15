package android.hardware;

import android.os.Handler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** JVM stub. Keeps the registered listeners so a test can deliver events through the real listener. */
public class SensorManager {
    public static final float GRAVITY_EARTH = 9.80665f;
    public static final int SENSOR_DELAY_GAME = 1;
    public static final class Registration {
        public final SensorEventListener listener; public final Sensor sensor;
        Registration(SensorEventListener l, Sensor s) { listener = l; sensor = s; }
    }
    public final List<Registration> registrations = new CopyOnWriteArrayList<>();
    private final boolean accelerometer, gyroscope;
    public SensorManager(boolean accelerometer, boolean gyroscope) { this.accelerometer = accelerometer; this.gyroscope = gyroscope; }
    public Sensor getDefaultSensor(int type) {
        if (type == Sensor.TYPE_ACCELEROMETER && accelerometer) return new Sensor(type);
        if (type == Sensor.TYPE_GYROSCOPE && gyroscope) return new Sensor(type);
        return null;
    }
    public boolean registerListener(SensorEventListener listener, Sensor sensor, int samplingPeriodUs, Handler handler) {
        registrations.add(new Registration(listener, sensor)); return true;
    }
    public void unregisterListener(SensorEventListener listener) { registrations.removeIf(r -> r.listener == listener); }
}
