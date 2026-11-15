package android.car.hardware;
/** JVM stub. */
public final class CarPropertyValue<T> {
    private final int propertyId; private final T value;
    public CarPropertyValue(int propertyId, T value) { this.propertyId = propertyId; this.value = value; }
    public int getPropertyId() { return propertyId; }
    public T getValue() { return value; }
}
