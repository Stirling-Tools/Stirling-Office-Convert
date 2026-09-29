package stirling.software.officeconvert.topdf.font;

import java.util.HashMap;
import java.util.Map;


/** Stand-ins for Office cloud fonts that are not installed: a metric-close face scaled to the cloud font's width. */
public final class CloudFonts {

    public static final String SAMPLE = "The quick brown fox jumps over the lazy dog. "
            + "Pack my box with five dozen liquor jugs! "
            + "Sphinx of black quartz, judge my vow. 0123456789 THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG. "
            + "Revenue grew 12% across regional markets, and the team reviewed the annual plan (Q3).";

    private static final float NaN = Float.NaN;

    private static final Map<String, float[]> WIDTHS = table();

    private static final Map<String, String> ALTERNATIVES = alternatives();

    private final FontLibrary fonts;

    private final Map<FontFace, Float> averages = new HashMap<>();

    public CloudFonts(FontLibrary fonts) {
        this.fonts = fonts;
    }

    /** The face to draw with, its horizontal scale in percent, ascent and descent in ems (or null) and a note. */
    public record Emulation(FontFace face, float scale, float[] vertical, String note, CloudMetrics metrics) {}

    public Emulation emulate(String family, boolean bold, boolean italic) {
        FontFace face = fonts.find(family, bold, italic);
        if (!face.substituted()) {
            return new Emulation(face, 100, null, null, null);
        }
        OfficeFonts.Style office = face.officeWidths();
        if (office != null) {
            return new Emulation(face, 100, new float[] {office.winAscent(), office.winDescent()}, null, null);
        }
        String key = FontLibrary.normalize(family);
        float[] w = WIDTHS.get(key);
        if (w == null) {
            return new Emulation(face, 100, null, null, null);
        }
        String alternative = ALTERNATIVES.get(key);
        String note = null;
        if (alternative != null) {
            face = fonts.find(alternative, bold, italic);
            note = family + " is not installed; using " + face.family() + " scaled to its width";
        }
        float target = w[(bold ? 2 : 0) + (italic ? 1 : 0)];
        FontFace reference = face;
        if (Float.isNaN(target)) {
            boolean regular = !Float.isNaN(w[0]);
            target = regular ? w[0] : w[2];
            reference = fonts.find(alternative != null ? alternative : family, !regular, false);
        }
        float actual = average(reference);
        float scale = target > 0 && actual > 0 ? Math.max(70, Math.min(130, 100 * target / actual)) : 100;
        return new Emulation(face, scale, new float[] {w[4], w[5]}, note, CloudMetrics.of(family, bold, italic));
    }

    private synchronized float average(FontFace face) {
        Float known = averages.get(face);
        if (known != null) {
            return known;
        }
        long units = 0;
        int n = 0;
        for (int i = 0; i < SAMPLE.length(); i++) {
            units += face.advance(SAMPLE.charAt(i));
            n++;
        }
        float a = units / (float) face.unitsPerEm() / n;
        averages.put(face, a);
        return a;
    }

    private static Map<String, String> alternatives() {
        Map<String, String> m = new HashMap<>();
        for (String f : new String[] {"Walbaum Display", "Lora", "Georgia Pro Semibold"}) {
            m.put(FontLibrary.normalize(f), "Georgia");
        }
        for (String f : new String[] {"Univers Condensed", "Trade Gothic Next Cond", "Barlow Condensed"}) {
            m.put(FontLibrary.normalize(f), "Arial Narrow");
        }
        m.put(FontLibrary.normalize("Trade Gothic Next Light"), "Arial");
        return m;
    }

    private static void entry(Map<String, float[]> m, String family, float regular, float italic, float bold,
            float boldItalic, float winAscent, float winDescent) {
        m.put(FontLibrary.normalize(family), new float[] {regular, italic, bold, boldItalic, winAscent, winDescent});
    }

    private static Map<String, float[]> table() {
        Map<String, float[]> m = new HashMap<>();
        entry(m, "Aptos", 0.4528f, 0.4525f, 0.4742f, 0.4746f, 0.9390f, 0.2817f);
        entry(m, "Aptos Display", 0.4269f, 0.4267f, 0.4479f, 0.4486f, 0.9390f, 0.2817f);
        entry(m, "Aptos Light", 0.4421f, 0.4414f, NaN, NaN, 0.9390f, 0.2817f);
        entry(m, "Aptos Narrow", 0.4168f, 0.4155f, 0.4292f, 0.4260f, 0.9390f, 0.2817f);
        entry(m, "Arial Nova Light", 0.4640f, NaN, NaN, NaN, 0.9819f, 0.2275f);
        entry(m, "Avenir Next LT Pro", 0.4888f, NaN, 0.5227f, NaN, 0.9629f, 0.2500f);
        entry(m, "Avenir Next LT Pro Light", 0.4846f, NaN, NaN, NaN, 0.9629f, 0.2500f);
        entry(m, "Barlow Condensed", 0.3634f, NaN, 0.3704f, NaN, 1.0000f, 0.2000f);
        entry(m, "Bierstadt", 0.4566f, 0.4564f, 0.4859f, NaN, 0.7422f, 0.2598f);
        entry(m, "DM Sans", 0.4867f, NaN, NaN, NaN, 0.9920f, 0.3100f);
        entry(m, "Georgia Pro Semibold", NaN, NaN, 0.5154f, 0.5234f, 0.7563f, 0.2168f);
        entry(m, "Grandview Display", 0.4628f, NaN, NaN, NaN, 0.7939f, 0.2061f);
        entry(m, "Lora", 0.4937f, 0.4829f, NaN, NaN, 1.0060f, 0.2740f);
        entry(m, "Modern Love", 0.4940f, NaN, NaN, NaN, 1.1499f, 0.4897f);
        entry(m, "Montserrat", 0.5316f, 0.5354f, 0.5571f, NaN, 0.9680f, 0.2510f);
        entry(m, "Neue Haas Grotesk Text Pro", 0.4992f, NaN, 0.5173f, NaN, 0.9780f, 0.2450f);
        entry(m, "Noto Sans", 0.4964f, 0.4679f, 0.5242f, NaN, 1.0688f, 0.2930f);
        entry(m, "Open Sans", 0.4921f, NaN, 0.5229f, NaN, 1.0688f, 0.2930f);
        entry(m, "Posterama", 0.4929f, NaN, NaN, NaN, 1.0420f, 0.2881f);
        entry(m, "Raleway", 0.4922f, NaN, NaN, NaN, 0.9400f, 0.2340f);
        entry(m, "Roboto", 0.4722f, 0.4589f, 0.4793f, NaN, 0.9502f, 0.2500f);
        entry(m, "Seaford Display", 0.4303f, NaN, NaN, NaN, 0.7812f, 0.2188f);
        entry(m, "Tenorite", 0.4587f, NaN, NaN, NaN, 0.6616f, 0.2471f);
        entry(m, "Trade Gothic Next Cond", 0.3659f, NaN, 0.3815f, NaN, 0.9590f, 0.2407f);
        entry(m, "Trade Gothic Next Light", 0.4524f, 0.4483f, NaN, NaN, 0.9590f, 0.2407f);
        entry(m, "Ubuntu", 0.4803f, NaN, 0.5037f, NaN, 0.9320f, 0.1890f);
        entry(m, "Univers Condensed", 0.4228f, NaN, NaN, NaN, 0.9888f, 0.2500f);
        entry(m, "Walbaum Display", 0.4744f, NaN, NaN, NaN, 1.0269f, 0.3062f);
        return m;
    }
}
