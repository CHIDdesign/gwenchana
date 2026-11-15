package android.car.hardware.property;

import android.car.hardware.CarPropertyValue;

/** JVM stub. */
public class CarPropertyManager {
    public static final float SENSOR_RATE_FASTEST = 100f;
    public static final float SENSOR_RATE_NORMAL = 1f;
    public interface CarPropertyEventCallback {
        void onChangeEvent(CarPropertyValue<?> value);
        void onErrorEvent(int propertyId, int zone);
    }
    public boolean registerCallback(CarPropertyEventCallback callback, int propertyId, float rate) { return false; }
    public int getIntProperty(int propertyId, int area) { throw new IllegalStateException("stub"); }
}
