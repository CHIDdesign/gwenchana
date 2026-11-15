package android.util;
/** JVM stub backed by java.util.Base64. */
public class Base64 {
    public static final int NO_WRAP = 2;
    public static String encodeToString(byte[] input, int flags) { return java.util.Base64.getEncoder().encodeToString(input); }
}
