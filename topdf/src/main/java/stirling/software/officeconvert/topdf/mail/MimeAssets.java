package stirling.software.officeconvert.topdf.mail;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import stirling.software.officeconvert.topdf.HtmlAssets;

public final class MimeAssets implements HtmlAssets {

    private final Map<String, byte[]> byId = new HashMap<>();
    private final Map<String, byte[]> byFoldedId = new HashMap<>();
    private final Map<String, byte[]> byKey = new HashMap<>();
    private final Map<String, byte[]> byFoldedKey = new HashMap<>();
    private String base = "";
    private int inline;

    String inlineId() {
        return "office-convert-inline-" + inline++;
    }
    private boolean web;

    public void add(String contentId, String location, byte[] data) {
        if (data == null) {
            return;
        }
        if (contentId != null && !contentId.isEmpty()) {
            byId.putIfAbsent(contentId, data);
            byFoldedId.putIfAbsent(contentId.toLowerCase(Locale.ROOT), data);
        }
        String key = key(location);
        if (key != null) {
            byKey.putIfAbsent(key, data);
            byFoldedKey.putIfAbsent(key.toLowerCase(Locale.ROOT), data);
            byKey.putIfAbsent(location.strip(), data);
        }
    }

    public void root(String location) {
        String key = key(location);
        web = key != null && web(key);
        base = key == null ? "" : key;
    }

    public boolean has(String reference) {
        return find(reference) != null;
    }

    @Override
    public String base() {
        return base;
    }

    @Override
    public boolean absoluteWeb() {
        return web;
    }

    @Override
    public boolean contentIds() {
        return true;
    }

    @Override
    public byte[] read(String reference) {
        byte[] data = find(reference);
        return data == null || data.length > MAX_ASSET_BYTES ? null : data;
    }

    private byte[] find(String reference) {
        if (reference == null) {
            return null;
        }
        String r = reference.strip();
        if (r.regionMatches(true, 0, "cid:", 0, 4)) {
            String id = r.substring(4);
            if (id.startsWith("<") && id.endsWith(">") && id.length() > 1) {
                id = id.substring(1, id.length() - 1);
            }
            byte[] d = byId.get(id);
            if (d == null) {
                String decoded = percentDecoded(id);
                d = byId.get(decoded);
                if (d == null) {
                    d = byFoldedId.get(decoded.toLowerCase(Locale.ROOT));
                }
            }
            return d;
        }
        byte[] raw = byKey.get(r);
        if (raw != null) {
            return raw;
        }
        String key = key(r);
        if (key == null) {
            return null;
        }
        byte[] d = byKey.get(key);
        return d != null ? d : byFoldedKey.get(key.toLowerCase(Locale.ROOT));
    }

    static String key(String location) {
        if (location == null) {
            return null;
        }
        String l = location.strip();
        int hash = l.indexOf('#');
        if (hash >= 0) {
            l = l.substring(0, hash);
        }
        if (l.isEmpty()) {
            return null;
        }
        if (web(l)) {
            int at = l.indexOf("://");
            int path = l.indexOf('/', at + 3);
            int query = l.indexOf('?', at + 3);
            if (path < 0 || query >= 0 && query < path) {
                path = query;
            }
            String authority = (path < 0 ? l.substring(at + 3) : l.substring(at + 3, path)).toLowerCase(Locale.ROOT);
            String scheme = l.substring(0, at).toLowerCase(Locale.ROOT);
            if (scheme.equals("http") && authority.endsWith(":80")) {
                authority = authority.substring(0, authority.length() - 3);
            } else if (scheme.equals("https") && authority.endsWith(":443")) {
                authority = authority.substring(0, authority.length() - 4);
            }
            String rest = path < 0 ? "/" : l.substring(path);
            if (rest.startsWith("?")) {
                rest = "/" + rest;
            }
            try {
                int q = rest.indexOf('?');
                String p = q < 0 ? rest : rest.substring(0, q);
                p = URI.create("http://h" + p.replace(" ", "%20")).normalize().getRawPath();
                rest = q < 0 ? p : p + rest.substring(q);
            } catch (IllegalArgumentException ignored) {
            }
            return scheme + "://" + authority + percentDecoded(rest.replace("+", "%20"));
        }
        if (l.regionMatches(true, 0, "cid:", 0, 4)) {
            return null;
        }
        if (l.regionMatches(true, 0, "file:", 0, 5)) {
            l = l.substring(5);
        }
        l = percentDecoded(l).replace('\\', '/');
        int q = l.indexOf('?');
        if (q >= 0) {
            l = l.substring(0, q);
        }
        StringBuilder sb = new StringBuilder();
        for (String step : l.split("/")) {
            if (step.isEmpty() || step.equals(".")) {
                continue;
            }
            if (step.equals("..")) {
                int slash = sb.lastIndexOf("/");
                sb.setLength(Math.max(0, slash));
                continue;
            }
            String s = step.replace(":", "");
            if (s.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('/');
            }
            sb.append(s);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static boolean web(String s) {
        return s.regionMatches(true, 0, "http://", 0, 7) || s.regionMatches(true, 0, "https://", 0, 8);
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
