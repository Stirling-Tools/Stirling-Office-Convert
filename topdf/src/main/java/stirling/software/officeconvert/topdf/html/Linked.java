package stirling.software.officeconvert.topdf.html;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import stirling.software.officeconvert.topdf.HtmlAssets;

final class Linked {

    static final long MAX_TOTAL_BYTES = 192L << 20;

    static final int MAX_READS = 4_000;

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
}
