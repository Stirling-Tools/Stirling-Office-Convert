package stirling.software.officeconvert.topdf.ofd;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.io.Dom;

final class OfdResources {

    static final int MAX_RESOURCES = 100_000;

    record Font(String name, String family, boolean bold, boolean italic, boolean serif, String file) {}

    record ColorSpace(String type, int bits, List<int[]> palette) {}

    record Media(String format, String file) {}

    private final Map<String, Font> fonts = new HashMap<>();

    private final Map<String, ColorSpace> colorSpaces = new HashMap<>();

    private final Map<String, Element> drawParams = new HashMap<>();

    private final Map<String, Media> media = new HashMap<>();

    private final Map<String, Element> units = new HashMap<>();

    private final Set<String> loaded = new HashSet<>();

    private final OfdResources parent;

    private int count;

    OfdResources() {
        this.parent = null;
    }

    OfdResources(OfdResources parent) {
        this.parent = parent;
        this.count = parent.count;
    }

    private boolean known(String part) {
        return loaded.contains(part) || parent != null && parent.known(part);
    }

    static String key(String id) {
        if (id == null) {
            return "";
        }
        String t = id.strip();
        if (t.isEmpty() || t.length() > 20 || !t.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return t;
        }
        int i = 0;
        while (i < t.length() - 1 && t.charAt(i) == '0') {
            i++;
        }
        return t.substring(i);
    }

    Font font(String id) {
        Font f = fonts.get(key(id));
        return f != null || parent == null ? f : parent.font(id);
    }

    Media media(String id) {
        Media m = media.get(key(id));
        return m != null || parent == null ? m : parent.media(id);
    }

    Element unit(String id) {
        Element u = units.get(key(id));
        return u != null || parent == null ? u : parent.unit(id);
    }

    void load(OfdPackage pkg, String part) throws IOException {
        if (part == null || known(part) || !pkg.exists(part)) {
            return;
        }
        loaded.add(part);
        Element res = pkg.xml(part);
        String base = OfdPackage.resolve(part, attr(res, "BaseLoc", ""));
        String dir = base == null ? OfdPackage.dir(part) : base.isEmpty() ? "" : base + "/";
        for (Element group : Dom.kids(res)) {
            for (Element e : Dom.kids(group)) {
                if (++count > MAX_RESOURCES) {
                    return;
                }
                String id = key(e.getAttribute("ID"));
                if (id.isEmpty()) {
                    continue;
                }
                switch (Dom.local(e)) {
                    case "Font" -> {
                        Element file = Dom.kid(e, "FontFile");
                        String loc = file == null ? null : OfdPackage.resolve(dir + "x", Dom.text(file));
                        fonts.put(id, new Font(attr(e, "FontName", ""), attr(e, "FamilyName", ""),
                                bool(e, "Bold"), bool(e, "Italic"), bool(e, "Serif"), loc));
                    }
                    case "ColorSpace" -> {
                        List<int[]> palette = new ArrayList<>();
                        for (Element cv : Dom.kids(Dom.kid(e, "Palette"), "CV")) {
                            palette.add(OfdColors.ints(Dom.text(cv)));
                        }
                        colorSpaces.put(id, new ColorSpace(attr(e, "Type", "RGB"),
                                OfdNumbers.integer(e.getAttribute("BitsPerComponent"), 8), palette));
                    }
                    case "DrawParam" -> drawParams.put(id, e);
                    case "MultiMedia" -> {
                        Element file = Dom.kid(e, "MediaFile");
                        String loc = file == null ? null : OfdPackage.resolve(dir + "x", Dom.text(file));
                        media.put(id, new Media(attr(e, "Format", ""), loc));
                    }
                    case "CompositeGraphicUnit" -> {
                        if (Dom.kid(e, "Content") != null) {
                            units.put(id, e);
                        }
                    }
                    default -> {}
                }
            }
        }
    }

    Element drawParam(String id) {
        if (id == null) {
            return null;
        }
        Element d = drawParams.get(key(id));
        return d != null || parent == null ? d : parent.drawParam(id);
    }

    ColorSpace colorSpace(String id) {
        if (id == null) {
            return null;
        }
        ColorSpace c = colorSpaces.get(key(id));
        return c != null || parent == null ? c : parent.colorSpace(id);
    }

    static String attr(Element e, String name, String fallback) {
        if (e == null || !e.hasAttribute(name)) {
            return fallback;
        }
        return e.getAttribute(name).trim();
    }

    static boolean bool(Element e, String name) {
        return OfdNumbers.bool(attr(e, name, "false"), false);
    }
}
