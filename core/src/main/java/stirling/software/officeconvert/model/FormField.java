package stirling.software.officeconvert.model;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record FormField(Kind kind, String name, String label, String value, List<Choice> choices, boolean checked,
        boolean placeholderShown, boolean readOnly, boolean required, int maxLength) {

    public enum Kind {
        TEXT,
        PASSWORD,
        MULTILINE,
        COMBO,
        LIST,
        CHECKBOX,
        RADIO
    }

    public record Choice(String value, String label, boolean selected) {}

    private static final String TAG = "pdf-form-field?";

    private static final int MAX_TAG_CHARS = 1 << 20;

    public FormField {
        name = name == null ? "" : name;
        label = label == null ? "" : label;
        value = value == null ? "" : value;
        choices = choices == null ? List.of() : List.copyOf(choices);
        maxLength = Math.max(0, maxLength);
    }

    public String tag() {
        StringBuilder sb = new StringBuilder(TAG);
        sb.append("kind=").append(kind.name().toLowerCase(Locale.ROOT));
        param(sb, "name", name);
        param(sb, "label", label);
        param(sb, "value", value);
        for (Choice c : choices) {
            param(sb, "choice", enc(c.value()) + "," + enc(c.label()) + "," + (c.selected() ? "1" : "0"));
        }
        flag(sb, "checked", checked);
        flag(sb, "placeholder", placeholderShown);
        flag(sb, "readonly", readOnly);
        flag(sb, "required", required);
        if (maxLength > 0) {
            sb.append("&maxlength=").append(maxLength);
        }
        return sb.toString();
    }

    public static FormField fromTag(String tag) {
        if (tag == null || !tag.startsWith(TAG) || tag.length() > MAX_TAG_CHARS) {
            return null;
        }
        Kind kind = null;
        String name = "";
        String label = "";
        String value = "";
        List<Choice> choices = new ArrayList<>();
        boolean checked = false;
        boolean placeholder = false;
        boolean readOnly = false;
        boolean required = false;
        int maxLength = 0;
        try {
            for (String part : tag.substring(TAG.length()).split("&")) {
                int eq = part.indexOf('=');
                String key = eq < 0 ? part : part.substring(0, eq);
                String v = eq < 0 ? "" : dec(part.substring(eq + 1));
                switch (key) {
                    case "kind" -> kind = Kind.valueOf(v.toUpperCase(Locale.ROOT));
                    case "name" -> name = v;
                    case "label" -> label = v;
                    case "value" -> value = v;
                    case "choice" -> {
                        String[] c = v.split(",", -1);
                        if (c.length == 3) {
                            choices.add(new Choice(dec(c[0]), dec(c[1]), c[2].equals("1")));
                        }
                    }
                    case "checked" -> checked = v.equals("1");
                    case "placeholder" -> placeholder = v.equals("1");
                    case "readonly" -> readOnly = v.equals("1");
                    case "required" -> required = v.equals("1");
                    case "maxlength" -> maxLength = Integer.parseInt(v);
                    default -> { }
                }
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return kind == null ? null
                : new FormField(kind, name, label, value, choices, checked, placeholder, readOnly, required, maxLength);
    }

    private static void param(StringBuilder sb, String key, String v) {
        if (!v.isEmpty()) {
            sb.append('&').append(key).append('=').append(enc(v));
        }
    }

    private static void flag(StringBuilder sb, String key, boolean on) {
        if (on) {
            sb.append('&').append(key).append("=1");
        }
    }

    private static final String HEX = "0123456789ABCDEF";

    private static String enc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_'
                    || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX.charAt(c >> 4)).append(HEX.charAt(c & 0xF));
            }
        }
        return sb.toString();
    }

    private static String dec(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '%') {
                out.write(c > 0x7F ? '?' : c);
                continue;
            }
            int hi = i + 2 < s.length() ? Character.digit(s.charAt(i + 1), 16) : -1;
            int lo = hi < 0 ? -1 : Character.digit(s.charAt(i + 2), 16);
            if (lo < 0) {
                throw new IllegalArgumentException("Not percent-encoded: " + s);
            }
            out.write(hi << 4 | lo);
            i += 2;
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
