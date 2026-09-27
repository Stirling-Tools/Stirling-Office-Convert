package stirling.software.officeconvert.sink;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import stirling.software.officeconvert.model.Inline;

public final class Links {

    public static final String PAGE_PREFIX = "_Pg";

    private static final Set<String> MAIL_FIELDS = Set.of("to", "cc", "bcc", "subject", "body");

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private Links() {}

    public static String target(Inline.Text t) {
        String url = t.link() == null ? null : safeUrl(t.link());
        if (url != null) {
            return url;
        }
        if (t.anchorPage() >= 0) {
            return "#" + PAGE_PREFIX + (t.anchorPage() + 1);
        }
        return null;
    }

    public static String safeUrl(String url) {
        String u = url.strip();
        String lower = u.toLowerCase(Locale.ROOT);
        if (lower.startsWith("mailto:")) {
            u = mail(u);
        } else if (!(lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("ftp://"))) {
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
                name = URLDecoder.decode(eq < 0 ? field : field.substring(0, eq), StandardCharsets.UTF_8).strip();
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

    public static int pageOf(String bookmark) {
        if (bookmark == null || !bookmark.startsWith(PAGE_PREFIX)) {
            return -1;
        }
        try {
            return Integer.parseInt(bookmark.substring(PAGE_PREFIX.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
