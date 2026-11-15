package android.content;

import java.io.File;

/** JVM stub. The bench supplies a subclass that roots filesDir/cacheDir in a temp folder. */
public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public abstract File getFilesDir();
    public abstract File getCacheDir();
    public abstract SharedPreferences getSharedPreferences(String name, int mode);
    public abstract <T> T getSystemService(Class<T> serviceClass);
    public Context getApplicationContext() { return this; }
    public boolean stopService(Intent service) { return false; }
    public int checkSelfPermission(String permission) { return android.content.pm.PackageManager.PERMISSION_DENIED; }
}
