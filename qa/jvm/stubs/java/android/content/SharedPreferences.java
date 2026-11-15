package android.content;

import java.util.Map;

/** JVM stub of the platform interface (only what the included sources call). */
public interface SharedPreferences {
    Map<String, ?> getAll();
    String getString(String key, String defValue);
    boolean getBoolean(String key, boolean defValue);
    int getInt(String key, int defValue);
    long getLong(String key, long defValue);
    float getFloat(String key, float defValue);
    boolean contains(String key);
    Editor edit();

    interface Editor {
        Editor putString(String key, String value);
        Editor putBoolean(String key, boolean value);
        Editor putInt(String key, int value);
        Editor putLong(String key, long value);
        Editor putFloat(String key, float value);
        Editor remove(String key);
        Editor clear();
        boolean commit();
        void apply();
    }
}
