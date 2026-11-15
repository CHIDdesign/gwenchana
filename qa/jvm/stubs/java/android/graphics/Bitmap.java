package android.graphics;
/** JVM stub. No image decoding exists on the bench, so no Bitmap is ever produced. */
public final class Bitmap {
    public enum CompressFormat { JPEG, PNG, WEBP }
    private Bitmap() {}
    public boolean compress(CompressFormat format, int quality, java.io.OutputStream stream) { throw new UnsupportedOperationException("stub"); }
}
