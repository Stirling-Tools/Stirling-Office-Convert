package stirling.software.officeconvert.topdf.pdf;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import stirling.software.officeconvert.topdf.io.Percent;

public final class SafeLinks {

    public static final int MAX_LENGTH = 8192;

    private static final Set<String> MAIL_FIELDS = Set.of("to", "cc", "bcc", "subject", "body");

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private SafeLinks() {}

    public static String safeUrl(String url) {
        if (url == null) {
            return null;
        }
        String u = url.strip();
        if (u.length() > MAX_LENGTH) {
            return null;
        }
        String lower = u.toLowerCase(Locale.ROOT);
        if (lower.startsWith("mailto:")) {
            u = mail(u);
            if (u.length() <= "mailto:".length()) {
                return null;
            }
        } else if (lower.startsWith("http://") || lower.startsWith("https://")) {
            String rest = u.substring(u.indexOf("//") + 2);
            if (rest.isEmpty() || rest.startsWith("/")) {
                return null;
            }
        } else {
            return null;
        }
        StringBuilder sb = new StringBuilder(u.length());
        for (byte b : u.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c > 0x20 && c < 0x7F && "\"<>\\^`{|}".indexOf(c) < 0) {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 15]);
            }
        }
        return sb.toString();
    }

    private static String mail(String u) {
        int q = u.indexOf('?');
        if (q < 0) {
            return u;
        }
        StringBuilder out = new StringBuilder(u.substring(0, q));
        char sep = '?';
        for (String field : u.substring(q + 1).split("&")) {
            int eq = field.indexOf('=');
            String name;
            try {
                name = Percent.decode(eq < 0 ? field : field.substring(0, eq)).strip();
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (MAIL_FIELDS.contains(name.toLowerCase(Locale.ROOT))) {
                out.append(sep).append(field);
                sep = '&';
            }
        }
        return out.toString();
    }
}
