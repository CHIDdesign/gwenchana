package org.json;

import java.util.ArrayList;
import java.util.List;

/** Minimal JSON reader/writer shared by the JSONObject/JSONArray stand-ins. */
final class JSON {
    private JSON() {}

    static Object wrap(Object o) {
        if (o == null) return JSONObject.NULL;
        if (o instanceof JSONObject || o instanceof JSONArray || o == JSONObject.NULL || o instanceof String
                || o instanceof Boolean || o instanceof Number || o instanceof Character) return o instanceof Character ? o.toString() : o;
        if (o instanceof java.util.Collection) return new JSONArray((java.util.Collection<?>) o);
        if (o instanceof java.util.Map) {
            JSONObject j = new JSONObject();
            for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) o).entrySet()) j.putUnchecked(String.valueOf(e.getKey()), wrap(e.getValue()));
            return j;
        }
        if (o.getClass().isEnum()) return o.toString();
        return o.toString();
    }

    static void write(StringBuilder sb, Object v) {
        if (v == null || v == JSONObject.NULL) sb.append("null");
        else if (v instanceof String) quote(sb, (String) v);
        else if (v instanceof Number) sb.append(number((Number) v));
        else if (v instanceof Boolean || v instanceof JSONObject || v instanceof JSONArray) sb.append(v.toString());
        else quote(sb, v.toString());
    }

    static String number(Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("Forbidden numeric value: " + d);
            if (d == (long) d && Math.abs(d) < 1e15) return Long.toString((long) d);
            return Double.toString(d);
        }
        return n.toString();
    }

    static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '/': sb.append("\\/"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        sb.append('"');
    }

    static double toDouble(Object v, String where) throws JSONException {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) try { return Double.parseDouble((String) v); } catch (NumberFormatException e) { /* fall through */ }
        throw new JSONException("Value " + v + " at " + where + " is not a number");
    }

    static Boolean toBoolean(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) { String s = (String) v; if (s.equalsIgnoreCase("true")) return true; if (s.equalsIgnoreCase("false")) return false; }
        return null;
    }

    /** Recursive-descent parser. */
    static final class Reader {
        private final String s; private int i;
        Reader(String s) { this.s = s; }
        Object next() throws JSONException {
            ws();
            if (i >= s.length()) throw new JSONException("End of input at character " + i);
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            return literal();
        }
        void end() throws JSONException { ws(); if (i != s.length()) throw new JSONException("Trailing data at character " + i); }
        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private JSONObject object() throws JSONException {
            JSONObject o = new JSONObject(); i++; ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return o; }
            while (true) {
                ws(); if (i >= s.length() || s.charAt(i) != '"') throw new JSONException("Expected name at character " + i);
                String k = string(); ws();
                if (i >= s.length() || s.charAt(i) != ':') throw new JSONException("Expected ':' at character " + i);
                i++; o.putUnchecked(k, next()); ws();
                if (i >= s.length()) throw new JSONException("Unterminated object");
                char c = s.charAt(i++);
                if (c == '}') return o;
                if (c != ',') throw new JSONException("Expected ',' or '}' at character " + (i - 1));
            }
        }
        private JSONArray array() throws JSONException {
            JSONArray a = new JSONArray(); i++; ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
            while (true) {
                a.put(next()); ws();
                if (i >= s.length()) throw new JSONException("Unterminated array");
                char c = s.charAt(i++);
                if (c == ']') return a;
                if (c != ',') throw new JSONException("Expected ',' or ']' at character " + (i - 1));
            }
        }
        private String string() throws JSONException {
            StringBuilder sb = new StringBuilder(); i++;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) break;
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': sb.append('\n'); break; case 'r': sb.append('\r'); break; case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break; case 'f': sb.append('\f'); break;
                        case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default: sb.append(e);
                    }
                } else sb.append(c);
            }
            throw new JSONException("Unterminated string");
        }
        private Object literal() throws JSONException {
            int st = i;
            while (i < s.length() && "{}[],:\" \t\r\n".indexOf(s.charAt(i)) < 0) i++;
            String w = s.substring(st, i);
            if (w.isEmpty()) throw new JSONException("Expected literal at character " + st);
            if (w.equals("null")) return JSONObject.NULL;
            if (w.equals("true")) return Boolean.TRUE;
            if (w.equals("false")) return Boolean.FALSE;
            try {
                if (w.indexOf('.') < 0 && w.indexOf('e') < 0 && w.indexOf('E') < 0) {
                    long l = Long.parseLong(w);
                    return (l <= Integer.MAX_VALUE && l >= Integer.MIN_VALUE) ? (Object) (int) l : (Object) l;
                }
                return Double.parseDouble(w);
            } catch (NumberFormatException e) { return w; }
        }
    }

    static List<Object> list() { return new ArrayList<>(); }
}
