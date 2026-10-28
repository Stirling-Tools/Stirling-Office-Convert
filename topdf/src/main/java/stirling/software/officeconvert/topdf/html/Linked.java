package stirling.software.officeconvert.topdf.html;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import stirling.software.officeconvert.topdf.HtmlAssets;

final class Linked implements HtmlAssets {

    static final long MAX_TOTAL_BYTES = 192L << 20;

    static final int MAX_READS = 4_000;

    private final HtmlAssets assets;
    private final Map<String, byte[]> cache = new HashMap<>();
    private long total;
    private int reads;
    private boolean exhausted;
    private String rebased;

    Linked(HtmlAssets assets) {
        this.assets = assets == null ? HtmlAssets.NONE : assets;
    }

    boolean none() {
        return assets == HtmlAssets.NONE;
    }

    @Override
    public String base() {
        if (rebased != null) {
            return rebased;
        }
        String b = assets.base();
        return b == null ? "" : b;
    }

    String resolve(String base, String reference) {
        if (reference != null && assets.absoluteWeb()) {
            String r = reference.strip();
            if (r.startsWith("//")) {
                r = (plainHttp(base) || !web(base) && plainHttp(base()) ? "http:" : "https:") + r;
            }
            if (r.regionMatches(true, 0, "http://", 0, 7) || r.regionMatches(true, 0, "https://", 0, 8)) {
                return r;
            }
        }
        return HtmlRefs.resolve(base, reference);
    }

    private static boolean plainHttp(String url) {
        return url != null && url.strip().regionMatches(true, 0, "http://", 0, 7);
    }

    private static boolean web(String url) {
        return plainHttp(url) || url != null && url.strip().regionMatches(true, 0, "https://", 0, 8);
    }

    void rebase(String href) {
        String b = assets.base();
        String web = b != null && (b.regionMatches(true, 0, "http://", 0, 7) || b.regionMatches(true, 0, "https://", 0, 8))
                ? HtmlRefs.resolve(b, href) : null;
        rebased = web;
    }

    boolean exhausted() {
        return exhausted;
    }

    @Override
    public byte[] read(String reference) {
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
}
