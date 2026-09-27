package stirling.software.officeconvert.odp;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class OdpStyles {

    private final Map<String, String> names = new HashMap<>();
    private final Map<String, Integer> counters = new HashMap<>();
    private final StringBuilder xml = new StringBuilder();
    private final Set<String> fonts = new LinkedHashSet<>();

    String style(String family, String prefix, String properties) {
        String key = family + '\u0000' + properties;
        String name = names.get(key);
        if (name == null) {
            name = prefix + counters.merge(prefix, 1, Integer::sum);
            names.put(key, name);
            xml.append("<style:style style:name=\"").append(name).append("\" style:family=\"").append(family).append("\">")
                    .append(properties).append("</style:style>");
        }
        return name;
    }

    String list(String levels) {
        String key = "list\u0000" + levels;
        String name = names.get(key);
        if (name == null) {
            name = "L" + counters.merge("L", 1, Integer::sum);
            names.put(key, name);
            xml.append("<text:list-style style:name=\"").append(name).append("\">").append(levels).append("</text:list-style>");
        }
        return name;
    }

    String font(String family) {
        fonts.add(family);
        return family;
    }

    String automaticStyles() {
        return xml.toString();
    }

    String fontDecls() {
        StringBuilder sb = new StringBuilder("<office:font-face-decls>");
        for (String f : fonts) {
            String e = Odf.esc(f);
            sb.append("<style:font-face style:name=\"").append(e).append("\" svg:font-family=\"&apos;").append(e)
                    .append("&apos;\"/>");
        }
        return sb.append("</office:font-face-decls>").toString();
    }

    Set<String> fonts() {
        return fonts;
    }
}
