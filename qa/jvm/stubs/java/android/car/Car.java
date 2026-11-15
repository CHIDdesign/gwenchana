package android.car;
/** JVM stub: no car service on the bench. */
public final class Car {
    public static final String PROPERTY_SERVICE = "property";
    public static Car createCar(android.content.Context context) { throw new IllegalStateException("no car service on JVM bench"); }
    public Object getCarManager(String serviceName) { return null; }
    public void disconnect() {}
}
