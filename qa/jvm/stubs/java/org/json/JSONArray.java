package org.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** JVM stand-in for Android's org.json.JSONArray (subset used by the included sources). */
public class JSONArray {
    private final List<Object> list = new ArrayList<>();

    public JSONArray() {}
    public JSONArray(Collection<?> c) { if (c != null) for (Object o : c) list.add(JSON.wrap(o)); }
    public JSONArray(String json) throws JSONException {
        JSON.Reader r = new JSON.Reader(json);
        Object v = r.next(); r.end();
        if (!(v instanceof JSONArray)) throw new JSONException("Value " + v + " cannot be converted to JSONArray");
        list.addAll(((JSONArray) v).list);
    }

    public int length() { return list.size(); }
    public JSONArray put(Object v) { list.add(JSON.wrap(v)); return this; }
    public JSONArray put(boolean v) { list.add(v); return this; }
    public JSONArray put(double v) throws JSONException { JSON.number(v); list.add(v); return this; }
    public JSONArray put(int v) { list.add(v); return this; }
    public JSONArray put(long v) { list.add(v); return this; }

    public Object opt(int i) { return (i < 0 || i >= list.size()) ? null : list.get(i); }
    public Object get(int i) throws JSONException {
        if (i < 0 || i >= list.size()) throw new JSONException("Index " + i + " out of range [0.." + list.size() + ")");
        return list.get(i);
    }
    public boolean isNull(int i) { Object v = opt(i); return v == null || v == JSONObject.NULL; }
    public String getString(int i) throws JSONException { Object v = get(i); return v == JSONObject.NULL ? "null" : v.toString(); }
    public String optString(int i) { Object v = opt(i); return (v == null || v == JSONObject.NULL) ? "" : v.toString(); }
    public double getDouble(int i) throws JSONException { return JSON.toDouble(get(i), "index " + i); }
    public int getInt(int i) throws JSONException { return (int) JSON.toDouble(get(i), "index " + i); }
    public long getLong(int i) throws JSONException { Object v = get(i); return v instanceof Number ? ((Number) v).longValue() : (long) JSON.toDouble(v, "index " + i); }
    public boolean getBoolean(int i) throws JSONException {
        Boolean b = JSON.toBoolean(get(i));
        if (b == null) throw new JSONException("Value at index " + i + " is not a boolean");
        return b;
    }
    public JSONObject getJSONObject(int i) throws JSONException {
        Object v = get(i);
        if (v instanceof JSONObject) return (JSONObject) v;
        throw new JSONException("Value at index " + i + " is not a JSONObject");
    }
    public JSONObject optJSONObject(int i) { Object v = opt(i); return v instanceof JSONObject ? (JSONObject) v : null; }
    public JSONArray getJSONArray(int i) throws JSONException {
        Object v = get(i);
        if (v instanceof JSONArray) return (JSONArray) v;
        throw new JSONException("Value at index " + i + " is not a JSONArray");
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) { if (i > 0) sb.append(','); JSON.write(sb, list.get(i)); }
        return sb.append(']').toString();
    }
}
