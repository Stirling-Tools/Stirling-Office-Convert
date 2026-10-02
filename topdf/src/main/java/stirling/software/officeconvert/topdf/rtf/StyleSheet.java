package stirling.software.officeconvert.topdf.rtf;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

final class StyleSheet {

    static final int MAX = 1 << 14;

    static final class Style {
        final char type;
        final int id;
        String name = "";
        int basedOn = -1;
        int next = -1;
        final CharProps chp = new CharProps();
        final ParaProps pap = new ParaProps();

        Style(char type, int id) {
            this.type = type;
            this.id = id;
        }

        String styleId() {
            return (type == 'c' ? "cs" : type == 't' ? "ts" : "s") + id;
        }
    }

    private final Map<String, Style> styles = new LinkedHashMap<>();

    Style open(char type, int id) {
        Style s = new Style(type, id);
        if (styles.size() < MAX) {
            styles.put(s.styleId(), s);
        }
        return s;
    }

    Style paragraph(int id) {
        return styles.get("s" + id);
    }

    Style character(int id) {
        return styles.get("cs" + id);
    }

    Collection<Style> all() {
        return styles.values();
    }

    Style basedOn(Style s) {
        if (s.basedOn < 0 || s.basedOn == s.id && s.type != 'c') {
            return null;
        }
        Style b = s.type == 'c' ? character(s.basedOn) : s.type == 'p' ? paragraph(s.basedOn) : null;
        return b == s ? null : b;
    }
}
