package stirling.software.officeconvert.odt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import stirling.software.officeconvert.extract.FontNames;

final class OdtStyles {

    private static final class Def {
        final String family;
        final String name;
        final String parent;
        String master;
        String inner;

        Def(String family, String name, String parent, String inner) {
            this.family = family;
            this.name = name;
            this.parent = parent;
            this.inner = inner;
        }
    }

    private final String prefix;
    private final Map<String, Def> byKey = new HashMap<>();
    private final Map<String, Def> byName = new HashMap<>();
    private final List<Def> defs = new ArrayList<>();
    private final Map<String, Integer> counts = new HashMap<>();
    final Set<String> fonts = new LinkedHashSet<>();

    OdtStyles(String prefix) {
        this.prefix = prefix;
    }

    String get(String family, String parent, String inner) {
        String key = family + '|' + parent + '|' + inner;
        Def d = byKey.get(key);
        if (d == null) {
            d = define(family, parent, inner);
            byKey.put(key, d);
        }
        return d.name;
    }

    String own(String family, String parent, String inner) {
        return define(family, parent, inner).name;
    }

    void settle(String name, String masterPage, String element, String attrs) {
        Def d = byName.get(name);
        if (d == null) {
            return;
        }
        d.master = masterPage;
        if (attrs.isEmpty()) {
            return;
        }
        String open = "<" + element;
        int at = d.inner.indexOf(open + ' ') >= 0 ? d.inner.indexOf(open + ' ') : d.inner.indexOf(open + '/');
        if (at < 0) {
            at = d.inner.indexOf(open + '>');
        }
        if (at < 0) {
            d.inner = open + attrs + "/>" + d.inner;
            return;
        }
        int end = at + open.length();
        String tag = d.inner.substring(at, d.inner.indexOf('>', at));
        StringBuilder add = new StringBuilder();
        for (String a : attrs.trim().split(" (?=[a-z]+:[a-z-]+=)")) {
            if (!a.isEmpty() && !tag.contains(" " + a.substring(0, a.indexOf('=') + 1))) {
                add.append(' ').append(a);
            }
        }
        d.inner = d.inner.substring(0, end) + add + d.inner.substring(end);
    }

    private Def define(String family, String parent, String inner) {
        String p = prefix + switch (family) {
            case "paragraph" -> "P";
            case "text" -> "T";
            case "table" -> "Tbl";
            case "table-column" -> "Col";
            case "table-row" -> "Row";
            case "table-cell" -> "Cell";
            case "graphic" -> "fr";
            case "section" -> "Sect";
            default -> "S";
        };
        int n = counts.merge(p, 1, Integer::sum);
        Def d = new Def(family, p + n, parent, inner);
        defs.add(d);
        byName.put(d.name, d);
        return d;
    }

    boolean isEmpty() {
        return defs.isEmpty();
    }

    void write(StringBuilder sb) {
        for (Def d : defs) {
            sb.append("<style:style style:name=\"").append(d.name).append("\" style:family=\"").append(d.family).append('"');
            if (d.parent != null) {
                sb.append(" style:parent-style-name=\"").append(d.parent).append('"');
            }
            if (d.master != null) {
                sb.append(" style:master-page-name=\"").append(d.master).append('"');
            }
            sb.append('>').append(d.inner).append("</style:style>");
        }
    }

    static void fontFaces(StringBuilder sb, Set<String> fonts) {
        sb.append("<office:font-face-decls>");
        for (String f : fonts) {
            boolean symbol = FontNames.isSymbolFamily(f);
            boolean mono = FontNames.looksMono(f);
            String family = symbol ? "system" : mono ? "modern" : FontNames.looksSerif(f) ? "roman" : "swiss";
            String name = OdtXml.esc(f);
            sb.append("<style:font-face style:name=\"").append(name).append("\" svg:font-family=\"&apos;").append(name)
                    .append("&apos;\" style:font-family-generic=\"").append(family).append("\" style:font-pitch=\"")
                    .append(mono ? "fixed" : "variable").append('"');
            if (symbol) {
                sb.append(" style:font-charset=\"x-symbol\"");
            }
            sb.append("/>");
        }
        sb.append("</office:font-face-decls>");
    }
}
