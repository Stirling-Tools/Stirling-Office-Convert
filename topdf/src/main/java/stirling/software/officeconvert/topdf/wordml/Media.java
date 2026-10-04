package stirling.software.officeconvert.topdf.wordml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import stirling.software.officeconvert.topdf.xls.Parts;

final class Media {

    static final int MAX_BASE64_CHARS = 96 << 20;

    private static final int MAX_PICTURE_BYTES = 64 << 20;

    private static final int MAX_PICTURES = 10_000;

    private final Parts zip;

    private final Map<String, String> stored = new HashMap<>();

    private int count;

    boolean dropped;

    Media(Parts zip) {
        this.zip = zip;
    }

    void put(String name, CharSequence base64) throws IOException {
        if (name == null || !name.startsWith("wordml://") || stored.containsKey(name)) {
            return;
        }
        if (count >= MAX_PICTURES) {
            dropped = true;
            return;
        }
        byte[] data;
        try {
            data = Base64.getMimeDecoder().decode(base64.toString());
        } catch (IllegalArgumentException e) {
            dropped = true;
            return;
        }
        if (gzip(data)) {
            data = gunzip(data);
            if (data == null) {
                dropped = true;
                return;
            }
        }
        String ext = extension(name, data);
        if (ext == null) {
            return;
        }
        if (zip.full(data.length)) {
            dropped = true;
            return;
        }
        String file = "image" + ++count + "." + ext;
        zip.put("word/media/" + file, data);
        stored.put(name, "media/" + file);
    }

    String target(String src) {
        return src == null ? null : stored.get(src);
    }

    private static boolean gzip(byte[] d) {
        return d.length > 2 && (d[0] & 0xFF) == 0x1F && (d[1] & 0xFF) == 0x8B;
    }

    private static byte[] gunzip(byte[] d) {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(d))) {
            byte[] out = in.readNBytes(MAX_PICTURE_BYTES + 1);
            return out.length > MAX_PICTURE_BYTES ? null : out;
        } catch (IOException e) {
            return null;
        }
    }

    static String extension(String name, byte[] d) {
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "png";
        }
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) {
            return "jpeg";
        }
        if (d.length >= 4 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F') {
            return "gif";
        }
        if (d.length >= 2 && d[0] == 'B' && d[1] == 'M') {
            return "bmp";
        }
        if (d.length >= 4 && (d[0] == 'I' && d[1] == 'I' && d[2] == 42 || d[0] == 'M' && d[1] == 'M' && d[3] == 42)) {
            return "tiff";
        }
        if (d.length >= 44 && d[0] == 1 && d[1] == 0 && d[2] == 0 && d[3] == 0 && d[40] == ' ' && d[41] == 'E'
                && d[42] == 'M' && d[43] == 'F') {
            return "emf";
        }
        if (d.length >= 4 && (d[0] & 0xFF) == 0xD7 && (d[1] & 0xFF) == 0xCD && (d[2] & 0xFF) == 0xC6
                && (d[3] & 0xFF) == 0x9A) {
            return "wmf";
        }
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".wmf") || n.endsWith(".wmz")) {
            return d.length >= 18 && (d[0] == 1 || d[0] == 2) && d[1] == 0 ? "wmf" : null;
        }
        if (n.endsWith(".pict") || n.endsWith(".pct")) {
            return "pict";
        }
        return null;
    }
}
