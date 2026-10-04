package stirling.software.officeconvert.model;

import java.util.LinkedHashMap;
import java.util.Map;

public final class StyleSheet {

    public record Style(String id, String name, RunStyle run, int outlineLevel, boolean keepNext) {}

    private final Map<String, Style> styles = new LinkedHashMap<>();
    public final RunStyle normal;
    public Scripts.Profile scripts = new Scripts.Profile();

    public StyleSheet(RunStyle normal) {
        this.normal = normal;
        styles.put("Normal", new Style("Normal", "Normal", normal, -1, false));
    }

    public Style use(String id, RunStyle look) {
        RunStyle l = look == null ? normal : look.font() == null ? look.withFont(normal.font()) : look;
        return styles.computeIfAbsent(id, k -> define(k, l));
    }

    private Style define(String id, RunStyle look) {
        if (id.startsWith("Heading")) {
            int level = Integer.parseInt(id.substring(7));
            return new Style(id, "heading " + level, look, level - 1, true);
        }
        if (id.equals("Title")) {
            return new Style(id, "Title", look, -1, true);
        }
        if (id.equals("ListParagraph")) {
            return new Style(id, "List Paragraph", normal, -1, false);
        }
        return new Style(id, id, look == null ? normal : look, -1, false);
    }

    public Style get(String id) {
        Style s = styles.get(id);
        return s != null ? s : styles.get("Normal");
    }

    public Iterable<Style> all() {
        return styles.values();
    }
}
