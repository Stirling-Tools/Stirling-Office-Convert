package stirling.software.officeconvert.build;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

final class SubstituteMetrics {

    private static final Map<Standard14Fonts.FontName, Metrics> FONTS = new ConcurrentHashMap<>();

    private SubstituteMetrics() {}

    static float width(String text, String family, boolean bold, boolean italic, float size) {
        Standard14Fonts.FontName name = fontFor(family, bold, italic);
        if (name == null) {
            return Float.NaN;
        }
        Metrics metrics = FONTS.computeIfAbsent(name, Metrics::new);
        float total = 0;
        for (int i = 0; i < text.length(); i++) {
            total += metrics.width(text.charAt(i));
        }
        return Float.isNaN(total) ? Float.NaN : total / 1000f * size;
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
