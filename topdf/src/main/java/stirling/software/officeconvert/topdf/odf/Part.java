package stirling.software.officeconvert.topdf.odf;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** One OOXML part being written: its name and its relationships. */
final class Part {

    final String name;

    final Rels rels = new Rels();

    private final Map<String, String> pictures = new HashMap<>();

    private final Map<String, String> links = new HashMap<>();

    Part(String name) {
        this.name = name;
    }

    String dir() {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? "" : name.substring(0, slash + 1);
    }

    /** The relationship id of a stored picture, relative to this part's folder, or null when it is not one. */
    String picture(PackageOut out, String mediaDir, byte[] data) throws IOException {
        String stored = out.picture(mediaDir, data);
        if (stored == null) {
            return null;
        }
        String existing = pictures.get(stored);
        if (existing != null) {
            return existing;
        }
        String target = relative(stored);
        String id = rels.add("image", target);
        pictures.put(stored, id);
        return id;
    }

    String link(String url) {
        return links.computeIfAbsent(url, u -> rels.external("hyperlink", u));
    }

    String relative(String target) {
        String dir = dir();
        if (target.startsWith(dir)) {
            return target.substring(dir.length());
        }
        int up = 0;
        String d = dir;
        while (!target.startsWith(d) && !d.isEmpty()) {
            d = d.substring(0, d.lastIndexOf('/', d.length() - 2) + 1);
            up++;
        }
        return "../".repeat(up) + target.substring(d.length());
    }
}
