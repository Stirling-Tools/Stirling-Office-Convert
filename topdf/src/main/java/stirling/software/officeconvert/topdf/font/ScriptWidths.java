package stirling.software.officeconvert.topdf.font;

import java.util.Map;

/** How wide Windows fonts draw Arabic and Hebrew, so a fallback face for those scripts can keep their width. */
final class ScriptWidths {

    static final String ARABIC = "بسم الله الرحمن الرحيم سيتم إغلاق المكتب يوم الجمعة لإجراء صيانة مجدولة لأنظمة "
            + "المبنى نشكركم على تفهمكم وتعاونكم";

    static final String HEBREW = "המשרד יהיה סגור ביום שישי לצורך תחזוקה מתוכננת של מערכות הבניין תודה על ההבנה";

    // Average advance in ems over the samples above: {Arabic, Hebrew, bold Arabic, bold Hebrew}, 0 where the font
    // lacks the script or has no bold face
    private static final Map<String, float[]> TABLE = Map.ofEntries(
            Map.entry("arial", new float[] {0.4450f, 0.4425f, 0.4417f, 0.4633f}),
            Map.entry("times new roman", new float[] {0.4407f, 0.3872f, 0.4375f, 0.4112f}),
            Map.entry("tahoma", new float[] {0.5573f, 0.5124f, 0.6416f, 0.5679f}),
            Map.entry("segoe ui", new float[] {0.5642f, 0.4878f, 0.5645f, 0.5119f}),
            Map.entry("courier new", new float[] {0.6001f, 0.6001f, 0.6001f, 0.6001f}),
            Map.entry("microsoft sans serif", new float[] {0.4474f, 0.4583f, 0, 0}),
            Map.entry("calibri", new float[] {0.4755f, 0.4136f, 0.4887f, 0.4228f}),
            Map.entry("sakkal majalla", new float[] {0.4040f, 0, 0.4152f, 0}),
            Map.entry("arabic typesetting", new float[] {0.3271f, 0, 0, 0}),
            Map.entry("aharoni", new float[] {0, 0.3911f, 0, 0.3911f}));

    private ScriptWidths() {}

    /** The sample for the code point's script (Arabic or Hebrew), else null. */
    static String sample(int codePoint) {
        Character.UnicodeScript script;
        try {
            script = Character.UnicodeScript.of(codePoint);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return switch (script) {
            case ARABIC -> ARABIC;
            case HEBREW -> HEBREW;
            default -> null;
        };
    }

    /** The family's average advance over the sample in ems, or 0 when unknown. */
    static float average(String family, String sample) {
        return average(family, sample, false);
    }

    // The bold face's width where Windows has one, else the regular one's
    static float average(String family, String sample, boolean bold) {
        float[] w = family == null ? null : TABLE.get(FontLibrary.normalize(family));
        if (w == null) {
            return 0;
        }
        int i = ARABIC.equals(sample) ? 0 : 1;
        return bold && w[i + 2] > 0 ? w[i + 2] : w[i];
    }

    /**
     * The horizontal scale that draws the sample's letters as wide as the family draws them on Windows, spaces
     * left as they are; 0 when the family or the program lacks the script or the difference is negligible.
     */
    static float scale(String family, FontProgram program, String sample, boolean bold) {
        float target = average(family, sample, bold);
        float own = average(program, sample);
        if (target <= 0 || own <= 0) {
            return 0;
        }
        int n = sample.length();
        long spaces = sample.chars().filter(c -> c == ' ').count();
        float upm = program.metrics().unitsPerEm();
        float ownSpace = program.advanceOfGlyph(Math.max(0, program.glyph(' '))) / upm;
        OfficeFonts.Style office = OfficeFonts.style(family, false, false);
        float space = office != null && office.advance(' ') > 0 ? office.advance(' ') / 1000f : ownSpace;
        float letters = own * n - ownSpace * spaces;
        float k = letters > 0 ? (target * n - space * spaces) / letters : 0;
        if (!(k > 0) || Math.abs(k - 1) < 0.02f) {
            return 0;
        }
        return Math.max(0.6f, Math.min(1.4f, k));
    }

    /** Scales for {Arabic, Hebrew} when a stand-in draws either script itself, else null. */
    static float[] scales(String family, String base, FontProgram program, boolean bold) {
        float[] out = new float[2];
        boolean any = false;
        String[] samples = {ARABIC, HEBREW};
        for (int i = 0; i < 2; i++) {
            String name = average(family, samples[i]) > 0 ? family : base;
            out[i] = scale(name, program, samples[i], bold);
            any |= out[i] > 0;
        }
        return any ? out : null;
    }

    static float average(FontProgram program, String sample) {
        long units = 0;
        int missing = 0;
        for (int i = 0; i < sample.length(); i++) {
            char c = sample.charAt(i);
            int g = program.glyph(c);
            if (g <= 0 && c != ' ') {
                missing++;
            }
            units += program.advanceOfGlyph(Math.max(0, g));
        }
        return missing > sample.length() / 4 ? 0 : units / (float) program.metrics().unitsPerEm() / sample.length();
    }
}
