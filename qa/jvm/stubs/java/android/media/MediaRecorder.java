package android.media;
/** JVM stub: there is no microphone on the bench; prepare() fails like a device without one. */
public class MediaRecorder {
    public static final class AudioSource { public static final int MIC = 1; }
    public static final class OutputFormat { public static final int MPEG_4 = 2; }
    public static final class AudioEncoder { public static final int AAC = 3; }
    public MediaRecorder() {}
    public MediaRecorder(android.content.Context context) {}
    public void setAudioSource(int s) {}
    public void setOutputFormat(int f) {}
    public void setAudioEncoder(int e) {}
    public void setOutputFile(String path) {}
    public void prepare() throws java.io.IOException { throw new java.io.IOException("no microphone on JVM bench"); }
    public void start() {}
    public void stop() {}
    public void release() {}
}
