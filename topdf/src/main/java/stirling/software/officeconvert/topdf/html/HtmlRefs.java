package stirling.software.officeconvert.topdf.html;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.regex.Pattern;

import stirling.software.officeconvert.topdf.HtmlAssets;

public final class HtmlRefs {

    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:.*", Pattern.DOTALL);

    private HtmlRefs() {}

    public static String resolve(String base, String reference) {
        if (reference == null) {
            return null;
        }
        String ref = reference.strip();
        if (ref.isEmpty() || ref.startsWith("#") || ref.regionMatches(true, 0, "data:", 0, 5)) {
            return null;
        }
        if (SCHEME.matcher(ref).matches()) {
            return web(base) && web(ref) ? ref : null;
        }
        if (base != null && SCHEME.matcher(base).matches()) {
            if (!web(base)) {
                return null;
            }
            try {
                String resolved = URI.create(base).resolve(ref.replace(" ", "%20")).toString();
                return web(resolved) ? resolved : null;
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

    private static boolean web(String s) {
        return s != null && (s.regionMatches(true, 0, "http://", 0, 7) || s.regionMatches(true, 0, "https://", 0, 8));
    }

    public static String extension(String reference) {
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

    static String percentDecoded(String s) {
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
