package stirling.software.officeconvert.topdf.font;

import java.util.Locale;
import java.util.Map;

/** How heavy a family is meant to be, so a lighter face standing in for it can be drawn with a thicker outline. */
final class Weights {

    // Synthetic bold strokes 0.035 em for the 300 weight units from regular to bold
    static final float STROKE_PER_UNIT = 0.035f / 300;

    // Stems grow faster below regular: a CJK Thin stem is 0.049 em thinner than its Regular
    static final float LIGHT_STROKE_PER_UNIT = 0.049f / 300;

    private static final float MAX_STROKE = 0.07f;

    private static final Map<String, Integer> WORDS = Map.ofEntries(Map.entry("thin", 100),
            Map.entry("hairline", 100), Map.entry("extralight", 200), Map.entry("ultralight", 200),
            Map.entry("light", 300), Map.entry("semilight", 350), Map.entry("demilight", 350),
            Map.entry("regular", 400), Map.entry("normal", 400), Map.entry("book", 400), Map.entry("medium", 500),
            Map.entry("semibold", 600), Map.entry("demibold", 600), Map.entry("demi", 600), Map.entry("bold", 700),
            Map.entry("extrabold", 800), Map.entry("ultrabold", 800), Map.entry("heavy", 800),
            Map.entry("black", 900), Map.entry("extrablack", 950), Map.entry("ultrablack", 950));

    // Display faces that are heavy although their names do not say so
    private static final Map<String, Integer> HEAVY = Map.of("impact", 900, "haettenschweiler", 900,
            "showcard gothic", 900, "bauhaus 93", 900, "stencil", 800);

    private Weights() {}

    /** The weight a family name asks for (400 when it names none). */
    static int of(String family) {
        if (family == null) {
            return 400;
        }
        String n = FontLibrary.normalize(family);
        Integer heavy = HEAVY.get(n);
        if (heavy != null) {
            return heavy;
        }
        String spaced = family.strip().indexOf(' ') < 0 ? split(family.strip()) : family;
        String[] words = spaced.toLowerCase(Locale.ROOT).split("[\\s_-]+");
        int weight = 400;
        for (int i = 1; i < words.length; i++) {
            Integer w = i + 1 < words.length ? WORDS.get(words[i] + words[i + 1]) : null;
            if (w != null) {
                weight = w;
                i++;
                continue;
            }
            w = WORDS.get(words[i]);
            if (w != null) {
                weight = w;
            }
        }
        return weight;
    }

    /** The extra stroke in ems for face {@code f} standing in for {@code family}, 0 when it is heavy enough. */
    static float embolden(String family, boolean bold, FontFace f) {
        return stroke(bold ? Math.max(of(family), 700) : of(family), f);
    }

    /**
     * The extra stroke for a face of the family itself or for a fallback face, which only a thin face gets (a
     * variable font whose default instance is Thin); display faces keep the weight they are drawn in.
     */
    static float thinStroke(String family, boolean bold, FontFace f) {
        if (weight(f.weight()) >= 300 || of(family) < 350) {
            return 0;
        }
        return stroke(bold ? 700 : 400, f);
    }

    private static float stroke(int wanted, FontFace f) {
        float extra = stem(wanted) - stem(weight(f.weight())) - (f.syntheticBold() ? 300 * STROKE_PER_UNIT : 0);
        return extra < 150 * STROKE_PER_UNIT ? 0 : Math.min(MAX_STROKE, extra);
    }

    // How much thicker than regular a stem of this weight is, in ems
    private static float stem(int weight) {
        return (weight - 400) * (weight < 400 ? LIGHT_STROKE_PER_UNIT : STROKE_PER_UNIT);
    }

    static FontFace emboldened(FontFace f, String family, boolean bold) {
        return with(f, embolden(family, bold, f));
    }

    static FontFace emboldenedIfThin(FontFace f, String family, boolean bold) {
        return with(f, thinStroke(family, bold, f));
    }

    private static FontFace with(FontFace f, float stroke) {
        return stroke > 0 && stroke != f.embolden() ? f.withEmbolden(stroke) : f;
    }

    // Some old fonts still write the Windows 3.1 weights 1 to 9
    private static int weight(int w) {
        if (w >= 1 && w <= 9) {
            return w * 100;
        }
        return w < 100 ? 400 : w;
    }

    // "ArialBlack" -> "Arial Black"
    private static String split(String n) {
        StringBuilder b = new StringBuilder(n.length() + 4);
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(n.charAt(i - 1))) {
                b.append(' ');
            }
            b.append(c);
        }
        return b.toString();
    }
}
