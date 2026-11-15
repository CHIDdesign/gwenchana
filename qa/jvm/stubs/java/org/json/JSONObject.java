package org.json;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** JVM stand-in for Android's org.json.JSONObject (subset used by the included sources). */
public class JSONObject {
    public static final Object NULL = new Object() {
        @Override public boolean equals(Object o) { return o == this || o == null; }
        @Override public int hashCode() { return 0; }
        @Override public String toString() { return "null"; }
    };
    private final Map<String, Object> map = new LinkedHashMap<>();

    public JSONObject() {}
    public JSONObject(String json) throws JSONException {
        JSON.Reader r = new JSON.Reader(json);
        Object v = r.next(); r.end();
        if (!(v instanceof JSONObject)) throw new JSONException("Value " + v + " cannot be converted to JSONObject");
        map.putAll(((JSONObject) v).map);
    }

    void putUnchecked(String k, Object v) { map.put(k, v); }

    public JSONObject put(String name, boolean v) throws JSONException { map.put(name, v); return this; }
    public JSONObject put(String name, double v) throws JSONException { JSON.number(v); map.put(name, v); return this; }
    public JSONObject put(String name, int v) throws JSONException { map.put(name, v); return this; }
    public JSONObject put(String name, long v) throws JSONException { map.put(name, v); return this; }
    public JSONObject put(String name, Object v) throws JSONException {
        if (v == null) { map.remove(name); return this; }
        if (v instanceof Double || v instanceof Float) JSON.number((Number) v);
        map.put(name, JSON.wrap(v)); return this;
    }
    public Object remove(String name) { return map.remove(name); }

    public int length() { return map.size(); }
    public boolean has(String name) { return map.containsKey(name); }
    public boolean isNull(String name) { Object v = map.get(name); return v == null || v == NULL; }
    public Iterator<String> keys() { return map.keySet().iterator(); }

    public Object opt(String name) { return map.get(name); }
    public Object get(String name) throws JSONException {
        Object v = map.get(name);
        if (v == null) throw new JSONException("No value for " + name);
        return v;
    }
    public String getString(String name) throws JSONException { Object v = get(name); return v == NULL ? "null" : v.toString(); }
    public String optString(String name) { return optString(name, ""); }
    public String optString(String name, String fallback) { Object v = map.get(name); return (v == null || v == NULL) ? fallback : v.toString(); }
    public double getDouble(String name) throws JSONException { return JSON.toDouble(get(name), name); }
    public double optDouble(String name) { return optDouble(name, Double.NaN); }
    public double optDouble(String name, double fallback) {
        Object v = map.get(name);
        try { return (v == null || v == NULL) ? fallback : JSON.toDouble(v, name); } catch (JSONException e) { return fallback; }
    }
    public int getInt(String name) throws JSONException { return (int) JSON.toDouble(get(name), name); }
    public int optInt(String name) { return optInt(name, 0); }
    public int optInt(String name, int fallback) { return (int) optDouble(name, fallback); }
    public long getLong(String name) throws JSONException {
        Object v = get(name);
        if (v instanceof Number) return ((Number) v).longValue();
        return (long) JSON.toDouble(v, name);
    }
    public long optLong(String name, long fallback) { Object v = map.get(name); return v instanceof Number ? ((Number) v).longValue() : fallback; }
    public boolean getBoolean(String name) throws JSONException {
        Boolean b = JSON.toBoolean(get(name));
        if (b == null) throw new JSONException("Value at " + name + " is not a boolean");
        return b;
    }
    public boolean optBoolean(String name) { return optBoolean(name, false); }
    public boolean optBoolean(String name, boolean fallback) { Boolean b = JSON.toBoolean(map.get(name)); return b == null ? fallback : b; }
    public JSONObject getJSONObject(String name) throws JSONException {
        Object v = get(name);
        if (v instanceof JSONObject) return (JSONObject) v;
        throw new JSONException("Value at " + name + " is not a JSONObject");
    }
    public JSONObject optJSONObject(String name) { Object v = map.get(name); return v instanceof JSONObject ? (JSONObject) v : null; }
    public JSONArray getJSONArray(String name) throws JSONException {
        Object v = get(name);
        if (v instanceof JSONArray) return (JSONArray) v;
        throw new JSONException("Value at " + name + " is not a JSONArray");
    }
    public JSONArray optJSONArray(String name) { Object v = map.get(name); return v instanceof JSONArray ? (JSONArray) v : null; }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(','); first = false;
            JSON.quote(sb, e.getKey()); sb.append(':'); JSON.write(sb, e.getValue());
        }
        return sb.append('}').toString();
    }
}
