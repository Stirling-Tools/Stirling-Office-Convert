package stirling.software.officeconvert.json;

import stirling.software.officeconvert.sink.ColourHex;

/** Writes one JSON object, leaving out values that equal their defaults. */
final class JsonOut {

    private final StringBuilder sb;
    private boolean first = true;

    JsonOut(StringBuilder sb) {
        this.sb = sb;
        sb.append('{');
    }

    StringBuilder key(String name) {
        if (!first) {
            sb.append(',');
        }
        first = false;
        Json.string(sb, name);
        return sb.append(':');
    }

    JsonOut str(String name, String v) {
        if (v != null) {
            Json.string(key(name), v);
        }
        return this;
    }

    /** Writes null itself, where leaving the name out would mean "the default". */
    JsonOut nullable(String name, String v) {
        if (v == null) {
            key(name).append("null");
        } else {
            Json.string(key(name), v);
        }
        return this;
    }

    JsonOut num(String name, float v) {
        if (v != 0 && Float.isFinite(v)) {
            Json.number(key(name), v);
        }
        return this;
    }

    JsonOut num(String name, float v, float unset) {
        if (v != unset && Float.isFinite(v)) {
            Json.number(key(name), v);
        }
        return this;
    }

    JsonOut integer(String name, long v, long unset) {
        if (v != unset) {
            key(name).append(v);
        }
        return this;
    }

    JsonOut always(String name, float v) {
        Json.number(key(name), v);
        return this;
    }

    JsonOut flag(String name, boolean v) {
        if (v) {
            key(name).append("true");
        }
        return this;
    }

    JsonOut color(String name, int rgb) {
        if (rgb >= 0) {
            key(name).append('"').append(hex(rgb)).append('"');
        }
        return this;
    }

    void end() {
        sb.append('}');
    }

    static String hex(int rgb) {
        return "#" + ColourHex.upper(rgb);
    }
}
