package com.javelin.language;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader/writer, enough for JSON-RPC. The JDK has no JSON support and a language-server
 * protocol client needs very little of it, so this avoids adding a dependency.
 * Objects become {@code Map<String,Object>}, arrays {@code List<Object>}, numbers {@code Long} or {@code Double},
 * plus {@code String}, {@code Boolean} and {@code null}.
 */
public final class Json {
    /** Nesting limit for both parsing and writing. Real protocol messages are a handful of levels deep; anything past this is hostile or cyclic. */
    static final int MAX_DEPTH = 256;

    private Json() {}

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        Object value = parser.value();
        parser.skipSpace();
        if (parser.pos != text.length()) throw parser.error("Unexpected trailing characters");
        return value;
    }

    /** Builds an object from alternating keys and values. Entries whose value is null are kept as JSON null. */
    public static Map<String, Object> obj(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return map;
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0);
        return out.toString();
    }

    // ---- typed accessors: tolerant of missing or mistyped fields, because servers vary ----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) { return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null; }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object value) { return value instanceof List<?> l ? (List<Object>) l : null; }

    public static Object get(Object object, String key) { Map<String, Object> m = asObject(object); return m == null ? null : m.get(key); }

    public static String string(Object object, String key) { return get(object, key) instanceof String s ? s : null; }

    public static int integer(Object object, String key, int fallback) { return get(object, key) instanceof Number n ? n.intValue() : fallback; }

    public static boolean bool(Object object, String key) { return Boolean.TRUE.equals(get(object, key)); }

    // ---- writer ----

    private static void write(Object value, StringBuilder out, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Value is nested more than " + MAX_DEPTH + " levels deep (or contains a cycle)");
        if (value == null) out.append("null");
        else if (value instanceof String s) writeString(s, out);
        else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) out.append(value);
        else if (value instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) out.append("null"); else out.append(d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d));
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                writeString(String.valueOf(e.getKey()), out);
                out.append(':');
                write(e.getValue(), out, depth + 1);
            }
            out.append('}');
        } else if (value instanceof Iterable<?> items) {
            out.append('[');
            boolean first = true;
            for (Object item : items) { if (!first) out.append(','); first = false; write(item, out, depth + 1); }
            out.append(']');
        } else throw new IllegalArgumentException("Cannot write " + value.getClass());
    }

    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        out.append('"');
    }

    // ---- parser ----

    private static final class Parser {
        private final String text;
        private int pos;
        private int depth;

        Parser(String text) { this.text = text; }

        IllegalArgumentException error(String message) { return new IllegalArgumentException(message + " at offset " + pos); }

        void skipSpace() { while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++; }

        Object value() {
            skipSpace();
            if (pos >= text.length()) throw error("Unexpected end of JSON");
            char c = text.charAt(pos);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': return literal("true", Boolean.TRUE);
                case 'f': return literal("false", Boolean.FALSE);
                case 'n': return literal("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw error("Unexpected character '" + c + "'");
            }
        }

        private Object literal(String word, Object result) {
            if (!text.startsWith(word, pos)) throw error("Invalid literal");
            pos += word.length();
            return result;
        }

        private void enter() { if (++depth > MAX_DEPTH) throw error("Nested more than " + MAX_DEPTH + " levels deep"); }

        private Map<String, Object> object() {
            enter();
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            skipSpace();
            if (peek() == '}') { pos++; depth--; return map; }
            while (true) {
                skipSpace();
                if (peek() != '"') throw error("Expected object key");
                String key = string();
                skipSpace();
                if (peek() != ':') throw error("Expected ':'");
                pos++;
                map.put(key, value());
                skipSpace();
                char c = peek();
                pos++;
                if (c == '}') { depth--; return map; }
                if (c != ',') throw error("Expected ',' or '}'");
            }
        }

        private List<Object> array() {
            enter();
            List<Object> list = new ArrayList<>();
            pos++;
            skipSpace();
            if (peek() == ']') { pos++; depth--; return list; }
            while (true) {
                list.add(value());
                skipSpace();
                char c = peek();
                pos++;
                if (c == ']') { depth--; return list; }
                if (c != ',') throw error("Expected ',' or ']'");
            }
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            pos++; // opening quote
            while (true) {
                if (pos >= text.length()) throw error("Unterminated string");
                char c = text.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (pos >= text.length()) throw error("Unterminated escape");
                char e = text.charAt(pos++);
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (pos + 4 > text.length()) throw error("Bad unicode escape");
                        try { sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16)); }
                        catch (NumberFormatException ex) { throw error("Bad unicode escape"); }
                        pos += 4;
                    }
                    default -> throw error("Bad escape '\\" + e + "'");
                }
            }
        }

        private Object number() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < text.length() && "0123456789.eE+-".indexOf(text.charAt(pos)) >= 0) pos++;
            String token = text.substring(start, pos);
            try {
                if (token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0) return Long.parseLong(token);
                return Double.parseDouble(token);
            } catch (NumberFormatException ex) { pos = start; throw error("Bad number"); }
        }

        private char peek() { return pos < text.length() ? text.charAt(pos) : '\0'; }
    }
}
