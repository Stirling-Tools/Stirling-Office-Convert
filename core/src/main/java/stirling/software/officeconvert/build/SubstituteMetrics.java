package stirling.software.officeconvert.build;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

final class SubstituteMetrics {

    private static final Map<Standard14Fonts.FontName, Metrics> FONTS = new ConcurrentHashMap<>();

    private static final Map<String, Short> EXTRA = load();

    private SubstituteMetrics() {}

    static float width(String text, String family, boolean bold, boolean italic, float size) {
        Standard14Fonts.FontName name = fontFor(family, bold, italic);
        if (name == null) {
            return Float.NaN;
        }
        Metrics metrics = FONTS.computeIfAbsent(name, Metrics::new);
        String key = family + "|" + ((bold ? 1 : 0) | (italic ? 2 : 0)) + "|";
        float total = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            float w = metrics.width(c);
            if (Float.isNaN(w)) {
                Short extra = EXTRA.get(key + (int) c);
                w = extra == null ? Float.NaN : extra;
            }
            total += w;
        }
        return Float.isNaN(total) ? Float.NaN : total / 1000f * size;
    }

    private static Map<String, Short> load() {
        Map<String, Short> out = new HashMap<>();
        try (InputStream in = SubstituteMetrics.class.getResourceAsStream("stand-in-widths.txt")) {
            if (in == null) {
                return out;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                String[] f = line.split("\\|");
                if (f.length != 4 || line.startsWith("#")) {
                    continue;
                }
                int cp = Integer.parseInt(f[2], 16);
                for (String w : f[3].split(" ")) {
                    out.put(f[0] + "|" + f[1] + "|" + cp++, Short.valueOf(w));
                }
            }
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
        return out;
    }

    private static final class Metrics {

        private final PDType1Font font;
        private final Map<Character, Float> widths = new ConcurrentHashMap<>();

        Metrics(Standard14Fonts.FontName name) {
            font = new PDType1Font(name);
        }

        float width(char c) {
            Float w = widths.get(c);
            if (w == null) {
                w = measure(c);
                widths.put(c, w);
            }
            return w;
        }

        private float measure(char c) {
            synchronized (font) {
                try {
                    return font.getStringWidth(String.valueOf(c));
                } catch (IOException | IllegalArgumentException e) {
                    return Float.NaN;
                }
            }
        }
    }

    private static Standard14Fonts.FontName fontFor(String family, boolean bold, boolean italic) {
        if (family == null) {
            return null;
        }
        return switch (family) {
            case "Times New Roman" -> bold
                    ? italic ? Standard14Fonts.FontName.TIMES_BOLD_ITALIC : Standard14Fonts.FontName.TIMES_BOLD
                    : italic ? Standard14Fonts.FontName.TIMES_ITALIC : Standard14Fonts.FontName.TIMES_ROMAN;
            case "Arial" -> bold
                    ? italic ? Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE : Standard14Fonts.FontName.HELVETICA_BOLD
                    : italic ? Standard14Fonts.FontName.HELVETICA_OBLIQUE : Standard14Fonts.FontName.HELVETICA;
            case "Courier New" -> bold
                    ? italic ? Standard14Fonts.FontName.COURIER_BOLD_OBLIQUE : Standard14Fonts.FontName.COURIER_BOLD
                    : italic ? Standard14Fonts.FontName.COURIER_OBLIQUE : Standard14Fonts.FontName.COURIER;
            default -> null;
        };
    }
}
