package stirling.software.officeconvert.topdf.html;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import stirling.software.officeconvert.topdf.HtmlAssets;

final class Linked {

    static final long MAX_TOTAL_BYTES = 192L << 20;

    static final int MAX_READS = 4_000;

    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:.*", Pattern.DOTALL);

    private final HtmlAssets assets;
    private final Map<String, byte[]> cache = new HashMap<>();
    private long total;
    private int reads;
    private boolean exhausted;

    Linked(HtmlAssets assets) {
        this.assets = assets == null ? HtmlAssets.NONE : assets;
    }

    boolean none() {
        return assets == HtmlAssets.NONE;
    }

    String base() {
        String b = assets.base();
        return b == null ? "" : b;
    }

    boolean exhausted() {
        return exhausted;
    }

    byte[] read(String reference) {
        if (reference == null || none()) {
            return null;
        }
        if (cache.containsKey(reference)) {
            return cache.get(reference);
        }
        if (reads >= MAX_READS || total >= MAX_TOTAL_BYTES) {
            exhausted = true;
            return null;
        }
        reads++;
        byte[] bytes;
        try {
            bytes = assets.read(reference);
        } catch (IOException | RuntimeException e) {
            bytes = null;
        }
        if (bytes != null && (bytes.length > HtmlAssets.MAX_ASSET_BYTES || total + bytes.length > MAX_TOTAL_BYTES)) {
            exhausted = true;
            bytes = null;
        }
        if (bytes != null) {
            total += bytes.length;
        }
        cache.put(reference, bytes);
        return bytes;
    }

    String text(String reference) {
        byte[] bytes = read(reference);
        if (bytes == null) {
            return null;
        }
        int from = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        return new String(bytes, from, bytes.length - from, StandardCharsets.UTF_8);
    }

    static String resolve(String base, String reference) {
        if (reference == null) {
            return null;
        }
        String ref = reference.strip();
        if (ref.isEmpty() || ref.startsWith("#") || ref.regionMatches(true, 0, "data:", 0, 5)) {
            return null;
        }
        if (SCHEME.matcher(ref).matches()) {
            return ref.regionMatches(true, 0, "javascript:", 0, 11) ? null : ref;
        }
        if (base != null && SCHEME.matcher(base).matches()) {
            try {
                return URI.create(base).resolve(ref.replace(" ", "%20")).toString();
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        String path = ref;
        int cut = indexOfAny(path, '?', '#');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        path = percentDecoded(path).replace('\\', '/');
        if (path.isEmpty()) {
            return null;
        }
        Deque<String> steps = new ArrayDeque<>();
        if (!path.startsWith("/") && base != null) {
            String dir = base.replace('\\', '/');
            int slash = dir.lastIndexOf('/');
            for (String s : (slash < 0 ? "" : dir.substring(0, slash)).split("/")) {
                if (!s.isEmpty() && !s.equals(".")) {
                    steps.addLast(s);
                }
            }
        }
        for (String s : path.split("/")) {
            if (s.isEmpty() || s.equals(".")) {
                continue;
            }
            if (s.equals("..")) {
                if (steps.isEmpty()) {
                    return null;
                }
                steps.removeLast();
            } else {
                steps.addLast(s);
            }
        }
        if (steps.isEmpty()) {
            return null;
        }
        String joined = String.join("/", steps);
        return HtmlAssets.packagePath(joined) ? joined : null;
    }

    static String extension(String reference) {
        String r = reference.toLowerCase(Locale.ROOT);
        int cut = indexOfAny(r, '?', '#');
        if (cut >= 0) {
            r = r.substring(0, cut);
        }
        int dot = r.lastIndexOf('.');
        return dot < 0 || dot < r.lastIndexOf('/') ? "" : r.substring(dot + 1);
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        return i < 0 ? j : j < 0 ? i : Math.min(i, j);
    }

    private static String percentDecoded(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int hi = c == '%' && i + 2 < s.length() ? Character.digit(s.charAt(i + 1), 16) : -1;
            int lo = hi >= 0 ? Character.digit(s.charAt(i + 2), 16) : -1;
            if (hi >= 0 && lo >= 0) {
                out.write(hi << 4 | lo);
                i += 2;
            } else {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(b, 0, b.length);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
