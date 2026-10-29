package stirling.software.officeconvert.odp;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import stirling.software.officeconvert.model.Scripts;
import stirling.software.officeconvert.slides.Gradient;

final class OdpStyles {

    private final Map<String, String> names = new HashMap<>();
    private final Map<String, Integer> counters = new HashMap<>();
    private final StringBuilder xml = new StringBuilder();
    private final Set<String> fonts = new LinkedHashSet<>();
    private final Map<String, String> gradients = new HashMap<>();
    private final StringBuilder gradientXml = new StringBuilder();

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

    String gradient(Gradient g) {
        int n = g.colours().length;
        StringBuilder stops = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int k = g.radial() ? n - 1 - i : i;
            float offset = g.radial() ? 1 - g.positions()[k] : g.positions()[k];
            stops.append("<loext:gradient-stop svg:offset=\"").append(String.format(Locale.ROOT, "%.4f", offset))
                    .append("\" loext:color-type=\"rgb\" loext:color-value=\"").append(Odf.colour(g.colours()[k])).append("\"/>");
        }
        int start = g.radial() ? g.colours()[n - 1] : g.colours()[0];
        int end = g.radial() ? g.colours()[0] : g.colours()[n - 1];
        String body = (g.radial() ? "draw:style=\"radial\" draw:cx=\"50%\" draw:cy=\"50%\" draw:angle=\"0deg\""
                : "draw:style=\"linear\" draw:angle=\"" + String.format(Locale.ROOT, "%.1fdeg", Math.floorMod(Math.round((90 - g.angle()) * 10), 3600) / 10f) + '"')
                + " draw:start-color=\"" + Odf.colour(start) + "\" draw:end-color=\"" + Odf.colour(end)
                + "\" draw:start-intensity=\"100%\" draw:end-intensity=\"100%\" draw:border=\"0%\">" + stops;
        String name = gradients.get(body);
        if (name == null) {
            name = "Gradient_" + (gradients.size() + 1);
            gradients.put(body, name);
            gradientXml.append("<draw:gradient draw:name=\"").append(name).append("\" ").append(body).append("</draw:gradient>");
        }
        return name;
    }

    String gradients() {
        return gradientXml.toString();
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

    Scripts.Profile scripts = new Scripts.Profile();

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
