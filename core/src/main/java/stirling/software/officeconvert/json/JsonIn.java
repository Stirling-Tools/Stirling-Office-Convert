package stirling.software.officeconvert.json;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Typed, bounded reads from one parsed JSON object; a value of the wrong type fails the document. */
final class JsonIn {

    static final float MAX_LENGTH = 14_400f;

    private final Map<?, ?> map;

    JsonIn(Object value, String what) throws IOException {
        if (!(value instanceof Map<?, ?> m)) {
            throw new IOException("Expected an object for " + what);
        }
        this.map = m;
    }

    boolean has(String key) {
        return map.get(key) != null;
    }

    /** True when the name is there, even with null. */
    boolean present(String key) {
        return map.containsKey(key);
    }

    String str(String key, String fallback) throws IOException {
        Object v = map.get(key);
        if (v == null) {
            return fallback;
        }
        if (!(v instanceof String s)) {
            throw new IOException("\"" + key + "\" must be text");
        }
        return s;
    }

    float num(String key, float fallback, float min, float max) throws IOException {
        Object v = map.get(key);
        if (v == null) {
            return fallback;
        }
        if (!(v instanceof Double d)) {
            throw new IOException("\"" + key + "\" must be a number");
        }
        return (float) Math.max(min, Math.min(max, d));
    }

    float length(String key) throws IOException {
        return num(key, 0, -MAX_LENGTH, MAX_LENGTH);
    }

    int integer(String key, int fallback, int min, int max) throws IOException {
        Object v = map.get(key);
        if (v == null) {
            return fallback;
        }
        if (!(v instanceof Double d)) {
            throw new IOException("\"" + key + "\" must be a number");
        }
        return (int) Math.max(min, Math.min(max, Math.round(d)));
    }

    boolean flag(String key) throws IOException {
        return flag(key, false);
    }

    boolean flag(String key, boolean fallback) throws IOException {
        Object v = map.get(key);
        if (v == null) {
            return fallback;
        }
        if (!(v instanceof Boolean b)) {
            throw new IOException("\"" + key + "\" must be true or false");
        }
        return b;
    }

    int color(String key, int fallback) throws IOException {
        String s = str(key, null);
        if (s == null) {
            return fallback;
        }
        String hex = s.startsWith("#") ? s.substring(1) : s;
        if (hex.length() == 3) {
            hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
        }
        try {
            if (hex.length() == 6) {
                return Integer.parseInt(hex, 16);
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new IOException("\"" + key + "\" must be a colour like #1A2B3C, was " + s);
    }

    <E extends Enum<E>> E choice(String key, Class<E> type, E fallback) throws IOException {
        String s = str(key, null);
        if (s == null) {
            return fallback;
        }
        String name = s.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            throw new IOException("\"" + key + "\" has an unknown value: " + s);
        }
    }

    List<?> list(String key) throws IOException {
        Object v = map.get(key);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> l)) {
            throw new IOException("\"" + key + "\" must be a list");
        }
        return l;
    }

    JsonIn obj(String key) throws IOException {
        Object v = map.get(key);
        return v == null ? null : new JsonIn(v, key);
    }

    Object raw(String key) {
        return map.get(key);
    }

    Object raw() {
        return map;
    }
}
