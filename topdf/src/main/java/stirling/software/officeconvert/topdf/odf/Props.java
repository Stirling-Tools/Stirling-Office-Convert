package stirling.software.officeconvert.topdf.odf;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;

final class Props {

    static final Props EMPTY = new Props();

    private static final Set<String> RELATIVE_SIZES = Set.of("fo:font-size", "style:font-size-asian",
            "style:font-size-complex");

    private static final Set<String> RELATIVE_LENGTHS = Set.of("fo:margin-left", "fo:margin-right", "fo:margin-top",
            "fo:margin-bottom", "fo:text-indent");

    private static final Map<String, String[]> SHORTHANDS = Map.of(
            "fo:margin", new String[] {"fo:margin-top", "fo:margin-bottom", "fo:margin-left", "fo:margin-right"},
            "fo:padding", new String[] {"fo:padding-top", "fo:padding-bottom", "fo:padding-left", "fo:padding-right"},
            "fo:border", new String[] {"fo:border-top", "fo:border-bottom", "fo:border-left", "fo:border-right"},
            "style:border-line-width", new String[] {"style:border-line-width-top", "style:border-line-width-bottom",
                "style:border-line-width-left", "style:border-line-width-right"});

    private static final Set<String> LINE_SPACING = Set.of("fo:line-height", "style:line-height-at-least",
            "style:line-spacing");

    private static final String[][] FONT_KEYS = {{"font-name", "font-family", Ns.FO},
        {"font-name-asian", "font-family-asian", Ns.STYLE}, {"font-name-complex", "font-family-complex", Ns.STYLE}};

    private final Map<String, String> attrs = new HashMap<>();

    private final Map<String, Element> kids = new HashMap<>();

    Props() {}

    Props(Props base) {
        attrs.putAll(base.attrs);
        kids.putAll(base.kids);
    }

    void merge(Element properties) {
        if (properties == null) {
            return;
        }
        for (String[] pair : FONT_KEYS) {
            boolean name = properties.hasAttributeNS(Ns.STYLE, pair[0]);
            boolean family = properties.hasAttributeNS(pair[2], pair[1]);
            if (name != family) {
                attrs.remove(name ? (pair[2].equals(Ns.FO) ? "fo:" : "style:") + pair[1] : "style:" + pair[0]);
            }
        }
        NamedNodeMap all = properties.getAttributes();
        for (int i = 0; i < all.getLength(); i++) {
            Attr a = (Attr) all.item(i);
            if (a.getNamespaceURI() == null || "http://www.w3.org/2000/xmlns/".equals(a.getNamespaceURI())) {
                continue;
            }
            put(Ns.prefix(a.getNamespaceURI()) + ":" + Dom.local(a), a.getValue());
        }
        for (Element k : Dom.kids(properties)) {
            kids.put(Dom.local(k), k);
        }
    }

    void merge(Props other) {
        for (String[] pair : FONT_KEYS) {
            String nameKey = "style:" + pair[0];
            String familyKey = (pair[2].equals(Ns.FO) ? "fo:" : "style:") + pair[1];
            boolean name = other.attrs.containsKey(nameKey);
            boolean family = other.attrs.containsKey(familyKey);
            if (name != family) {
                attrs.remove(name ? familyKey : nameKey);
            }
        }
        for (Map.Entry<String, String> e : other.attrs.entrySet()) {
            put(e.getKey(), e.getValue());
        }
        kids.putAll(other.kids);
    }

    void put(String key, String value) {
        if (value == null) {
            return;
        }
        String[] sides = SHORTHANDS.get(key);
        if (sides != null) {
            for (String side : sides) {
                put(side, value);
            }
            return;
        }
        if (LINE_SPACING.contains(key)) {
            for (String other : LINE_SPACING) {
                attrs.remove(other);
            }
        }
        if (Length.isPercent(value) && (RELATIVE_SIZES.contains(key) || RELATIVE_LENGTHS.contains(key))) {
            double base = Length.pt(attrs.get(key), Double.NaN);
            double pct = Length.percent(value, Double.NaN);
            if (Double.isFinite(base) && Double.isFinite(pct)) {
                attrs.put(key, (base * pct / 100) + "pt");
            } else if (Double.isFinite(pct)) {
                attrs.put(key, value);
            }
            return;
        }
        attrs.put(key, value);
    }

    void remove(String key) {
        attrs.remove(key);
    }

    String get(String key) {
        return attrs.get(key);
    }

    String get(String key, String fallback) {
        String v = attrs.get(key);
        return v == null ? fallback : v;
    }

    boolean has(String key) {
        return attrs.containsKey(key);
    }

    boolean is(String key, String value) {
        return value.equals(attrs.get(key));
    }

    double pt(String key, double fallback) {
        return Length.pt(attrs.get(key), fallback);
    }

    Element kid(String local) {
        return kids.get(local);
    }

    boolean isEmpty() {
        return attrs.isEmpty() && kids.isEmpty();
    }
}
