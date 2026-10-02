package stirling.software.officeconvert.topdf.vsdx;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.xls.Parts;

final class Media {

    static final Map<String, String> TYPES = Map.of("png", "image/png", "jpeg", "image/jpeg", "jpg", "image/jpeg",
            "gif", "image/gif", "bmp", "image/bmp", "tif", "image/tiff", "tiff", "image/tiff", "emf", "image/x-emf",
            "wmf", "image/x-wmf");

    private static final int MAX_PICTURES = 5000;

    private final Parts parts;

    private final Map<String, String> copied = new LinkedHashMap<>();

    final Map<String, String> extensions = new LinkedHashMap<>();

    Media(Parts parts) {
        this.parts = parts;
    }

    String add(OfficeZip zip, String part) throws IOException {
        String done = copied.get(part);
        if (done != null || copied.containsKey(part)) {
            return done;
        }
        int dot = part.lastIndexOf('.');
        String ext = dot < 0 ? "" : part.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!TYPES.containsKey(ext) || copied.size() >= MAX_PICTURES || parts.full(zip.size(part))) {
            copied.put(part, null);
            return null;
        }
        String name = "media/image" + (copied.size() + 1) + "." + ext;
        try (InputStream in = zip.open(part)) {
            parts.put("ppt/" + name, in);
        }
        copied.put(part, "../" + name);
        extensions.put(ext, TYPES.get(ext));
        return "../" + name;
    }
}
