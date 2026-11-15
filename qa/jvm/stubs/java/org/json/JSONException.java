package org.json;
/** JVM stand-in for Android's org.json (Android ships it in the platform; the bench has no copy). */
public class JSONException extends Exception {
    public JSONException(String s) { super(s); }
}
