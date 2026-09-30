package stirling.software.officeconvert.topdf.font;

import java.util.Arrays;
import java.util.Objects;

public final class FontFace {

    public static final int SOFT_HYPHEN = 0x00AD;

    private final FontProgram program;

    private final String requestedFamily;

    private final boolean syntheticBold;

    private final boolean syntheticItalic;

    private final String note;

    private final SymbolFonts.Remap symbols;

    private final OfficeFonts.Style widths;

    private final float stretch;

    private final boolean calibriHints;

    private final boolean eastAsianName;

    private final OfficeFonts.Style original;

    private final FontMetrics metrics;

    // Horizontal scale of Arabic and Hebrew glyphs of a stand-in that draws those scripts wider or narrower
    private final float[] scripts;

    private final float embolden;

    private byte substitutedState;

    private byte eastAsianGlyphs;

    FontFace(FontProgram program, String requestedFamily, boolean syntheticBold, boolean syntheticItalic, String note) {
        this(program, requestedFamily, syntheticBold, syntheticItalic, note, null, null, 1, null);
    }

    FontFace(FontProgram program, String requestedFamily, boolean syntheticBold, boolean syntheticItalic, String note,
            SymbolFonts.Remap symbols, OfficeFonts.Style widths, float stretch) {
        this(program, requestedFamily, syntheticBold, syntheticItalic, note, symbols, widths, stretch, null);
    }

    FontFace(FontProgram program, String requestedFamily, boolean syntheticBold, boolean syntheticItalic, String note,
            SymbolFonts.Remap symbols, OfficeFonts.Style widths, float stretch, OfficeFonts.Style original) {
        this(program, requestedFamily, syntheticBold, syntheticItalic, note, symbols, widths, stretch, original, null,
                0);
    }

    private FontFace(FontProgram program, String requestedFamily, boolean syntheticBold, boolean syntheticItalic,
            String note, SymbolFonts.Remap symbols, OfficeFonts.Style widths, float stretch, OfficeFonts.Style original,
            float[] scripts, float embolden) {
        this.program = Objects.requireNonNull(program, "program");
        this.scripts = scripts;
        this.embolden = embolden;
        this.requestedFamily = requestedFamily == null ? program.entry().family() : requestedFamily;
        this.syntheticBold = syntheticBold;
        this.syntheticItalic = syntheticItalic;
        this.note = note;
        this.symbols = symbols;
        this.widths = widths;
        this.stretch = stretch;
        this.calibriHints = HintedWidths.applies(this.requestedFamily, program.entry().family());
        this.eastAsianName = Substitutes.eastAsian(this.requestedFamily);
        this.original = original;
        this.metrics = original == null ? program.metrics() : original.lines(program.metrics());
    }

    /** True for a Chinese, Japanese or Korean font, also when a face without those scripts stands in for one. */
    public boolean eastAsian() {
        if (eastAsianName) {
            return true;
        }
        byte known = eastAsianGlyphs;
        if (known == 0) {
            known = covers(0x4E00) || covers(0x3042) || covers(0xAC00) ? (byte) 2 : (byte) 1;
            eastAsianGlyphs = known;
        }
        return known == 2;
    }

    /** False when the font's missing-glyph box (.notdef) has no outline. */
    public boolean notdefVisible() {
        return program.notdefInk();
    }

    /** True when the face keeps a missing Office font's advance widths. */
    public boolean emulated() {
        return widths != null;
    }

    /** True when glyphs are drawn at their own horizontal scale, {@link #glyphScale(int)}, not one for the face. */
    public boolean scaledPerGlyph() {
        return widths != null || scripts != null;
    }

    /** The horizontal scale that makes a glyph of an emulated stand-in fill the original font's advance. */
    public float glyphScale(int codePoint) {
        float script = scriptScale(codePoint);
        if (script > 0) {
            return script;
        }
        int glyph = glyph(codePoint);
        int own = glyph > 0 ? program.advanceOfGlyph(glyph) : 0;
        if (widths == null || own <= 0) {
            return stretch;
        }
        return Math.max(0.6f, Math.min(1.6f, advance(codePoint) / (float) own));
    }

    // Symbol fonts draw codes 0x7F to 0x9F too, which are C1 controls everywhere else
    public boolean symbolCode(int codePoint) {
        return codePoint >= 0x7F && codePoint <= 0x9F && (symbols != null || program.symbol());
    }

    OfficeFonts.Style officeWidths() {
        return widths;
    }

    /** Horizontal glyph scale of a stand-in that keeps another font's widths (an Office font, a script), else 1. */
    public float glyphStretch() {
        return stretch;
    }

    // The scale for Arabic or Hebrew text when the stand-in draws that script at another width than the original
    float scriptScale(int codePoint) {
        if (scripts == null || codePoint < 0x0590 || codePoint > 0xFEFF) {
            return 0;
        }
        String sample = ScriptWidths.sample(codePoint);
        if (sample == null) {
            return 0;
        }
        return scripts[ScriptWidths.ARABIC.equals(sample) ? 0 : 1];
    }

    /** The scale a shaped run of {@code text} is drawn at: its script's, else the face's. */
    public float glyphStretch(CharSequence text) {
        if (scripts != null) {
            for (int i = 0; i < text.length(); ) {
                int cp = Character.codePointAt(text, i);
                float k = scriptScale(cp);
                if (k > 0) {
                    return k;
                }
                i += Character.charCount(cp);
            }
        }
        return stretch;
    }

    /** Extra outline stroke in ems that brings a lighter stand-in to the weight of the font it stands for. */
    public float embolden() {
        return embolden;
    }

    FontFace withScripts(float[] scale) {
        return new FontFace(program, requestedFamily, syntheticBold, syntheticItalic, note, symbols, widths, stretch,
                original, scale, embolden);
    }

    FontFace withEmbolden(float stroke) {
        return new FontFace(program, requestedFamily, syntheticBold, syntheticItalic, note, symbols, widths, stretch,
                original, scripts, stroke);
    }

    // A Symbol or Wingdings character drawn with a Unicode font takes its Unicode look-alike
    int mapped(int codePoint) {
        if (symbols == null) {
            return codePoint;
        }
        int code = SymbolFonts.code(codePoint);
        return code < 0 || symbols.of(code) == 0 ? codePoint : symbols.of(code);
    }

    /**
     * How a symbol stand-in draws a look-alike so that its ink fills the symbol font's glyph box: {sx, sy, dx, dy},
     * scales and the offset of the glyph origin from the pen in ems (y up); null when the glyph is drawn as it is.
     */
    public float[] symbolFit(int codePoint) {
        int code = symbols == null ? -1 : SymbolFonts.code(codePoint);
        float[] target = code < 0 ? null : symbols.box(code);
        int glyph = target == null ? 0 : glyph(codePoint);
        float[] own = glyph > 0 ? program.glyphBox(glyph) : null;
        if (own == null) {
            return null;
        }
        float upm = unitsPerEm();
        float w = (own[2] - own[0]) / upm;
        float h = (own[3] - own[1]) / upm;
        float tw = target[2] - target[0];
        float th = target[3] - target[1];
        if (w <= 0 || h <= 0 || tw <= 0 || th <= 0) {
            return null;
        }
        float sx = tw / w;
        float sy = th / h;
        float mean = (float) Math.sqrt(sx * sy);
        float ratio = Math.max(0.6f, Math.min(1.67f, sx / sy));
        sx = Math.max(0.25f, Math.min(4, mean * (float) Math.sqrt(ratio)));
        sy = Math.max(0.25f, Math.min(4, mean / (float) Math.sqrt(ratio)));
        float dx = (target[0] + target[2]) / 2 - sx * (own[0] + own[2]) / 2 / upm;
        float dy = (target[1] + target[3]) / 2 - sy * (own[1] + own[3]) / 2 / upm;
        return new float[] {sx, sy, dx, dy};
    }

    /** True when this face draws Symbol, Wingdings or Webdings characters with Unicode look-alikes. */
    public boolean symbolStandIn() {
        return symbols != null;
    }

    FontProgram program() {
        return program;
    }

    public String family() {
        return program.entry().family();
    }

    public String subfamily() {
        return program.entry().subfamily();
    }

    public String postScriptName() {
        String ps = program.entry().postScriptName();
        return ps == null ? family().replace(" ", "") : ps;
    }

    public String requestedFamily() {
        return requestedFamily;
    }

    public boolean substituted() {
        byte known = substitutedState;
        if (known == 0) {
            known = findSubstituted() ? (byte) 2 : (byte) 1;
            substitutedState = known;
        }
        return known == 2;
    }

    private boolean findSubstituted() {
        String wanted = FontLibrary.normalize(requestedFamily);
        FontEntry e = program.entry();
        if (FontLibrary.normalize(e.family()).equals(wanted)) {
            return false;
        }
        for (String name : e.legacyFamilies()) {
            if (FontLibrary.normalize(name).equals(wanted)) {
                return false;
            }
        }
        for (String name : e.typographicFamilies()) {
            if (FontLibrary.normalize(name).equals(wanted)) {
                return false;
            }
        }
        return !FontLibrary.normalize(e.fullName()).equals(wanted)
                && (e.postScriptName() == null || !FontLibrary.normalize(e.postScriptName()).equals(wanted));
    }

    public String note() {
        return note;
    }

    public boolean bold() {
        return program.entry().bold();
    }

    public boolean italic() {
        return program.entry().italic();
    }

    public int weight() {
        return program.entry().weight();
    }

    public boolean syntheticBold() {
        return syntheticBold;
    }

    public boolean syntheticItalic() {
        return syntheticItalic;
    }

    public boolean boldStyle() {
        return syntheticBold || bold();
    }

    public boolean italicStyle() {
        return syntheticItalic || italic();
    }

    public FontFace withSynthetic(boolean bold, boolean italic) {
        if (bold == syntheticBold && italic == syntheticItalic) {
            return this;
        }
        return new FontFace(program, requestedFamily, bold, italic, note, symbols, widths, stretch, original, scripts,
                embolden);
    }

    public int unitsPerEm() {
        return program.metrics().unitsPerEm();
    }

    /** The font's metrics; a stand-in for an Office font takes that font's underline and strikeout. */
    public FontMetrics metrics() {
        return metrics;
    }

    public boolean symbolEncoded() {
        return program.symbol();
    }

    public boolean covers(int codePoint) {
        return codePoint != SOFT_HYPHEN && program.glyph(mapped(codePoint)) > 0;
    }

    public int advance(int codePoint) {
        if (codePoint == SOFT_HYPHEN || Character.isISOControl(codePoint) && !symbolCode(codePoint)) {
            return 0;
        }
        int symbol = symbolAdvance(codePoint);
        if (symbol >= 0) {
            return Math.round(symbol * unitsPerEm() / 1000f);
        }
        int glyph = program.glyph(mapped(codePoint));
        if (glyph > 0) {
            float script = scriptScale(codePoint);
            if (script > 0) {
                return Math.round(program.advanceOfGlyph(glyph) * script);
            }
            if (widths == null) {
                int own = program.advanceOfGlyph(glyph);
                return stretch == 1 ? own : Math.round(own * stretch);
            }
            int a = widths.advance(codePoint);
            if (a >= 0) {
                return Math.round(a * unitsPerEm() / 1000f);
            }
            int own = program.advanceOfGlyph(glyph);
            return wide(codePoint) ? own : Math.round(own * stretch);
        }
        if (ignorable(codePoint)) {
            return 0;
        }
        return wide(codePoint) ? unitsPerEm() : program.advanceOfGlyph(0);
    }

    private int symbolAdvance(int codePoint) {
        int code = symbols == null ? -1 : SymbolFonts.code(codePoint);
        return code < 0 ? -1 : symbols.advance(code);
    }

    // East Asian wide characters take a full em in every CJK font, so a missing one keeps that width
    static boolean wide(int cp) {
        return cp >= 0x1100 && cp <= 0x115F || cp >= 0x2E80 && cp <= 0x303E || cp >= 0x3041 && cp <= 0x33FF
                || cp >= 0x3400 && cp <= 0x4DBF || cp >= 0x4E00 && cp <= 0x9FFF || cp >= 0xA000 && cp <= 0xA4CF
                || cp >= 0xAC00 && cp <= 0xD7A3 || cp >= 0xF900 && cp <= 0xFAFF || cp >= 0xFE30 && cp <= 0xFE4F
                || cp >= 0xFF00 && cp <= 0xFF60 || cp >= 0xFFE0 && cp <= 0xFFE6 || cp >= 0x20000 && cp <= 0x3FFFD;
    }

    public float advance(int codePoint, float size) {
        return advance(codePoint) * size / unitsPerEm();
    }

    public int kerning(int left, int right) {
        if (symbols != null) {
            return 0;
        }
        int k = program.kerning(program.glyph(mapped(left)), program.glyph(mapped(right)));
        return stretch == 1 ? k : Math.round(k * stretch);
    }

    public float width(CharSequence text, float size) {
        long units = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            units += advance(cp);
            i += Character.charCount(cp);
        }
        return units * size / unitsPerEm();
    }

    public int glyph(int codePoint) {
        return codePoint == SOFT_HYPHEN ? 0 : program.glyph(mapped(codePoint));
    }

    public int glyphAdvance(int glyph) {
        return glyph >= 0 && glyph < program.glyphCount() ? program.advanceOfGlyph(glyph) : 0;
    }

    public float[] inkBounds(int codePoint) {
        int g = glyph(codePoint);
        float[] b = g > 0 ? program.inkBounds(g) : null;
        return b == null ? null : b.clone();
    }

    public int hintedAdvance(int codePoint, int ppem) {
        int g = glyph(codePoint);
        if (g <= 0) {
            return -1;
        }
        int patched = calibriHints ? HintedWidths.calibri(program.entry(), codePoint, ppem) : -1;
        int symbol = symbolAdvance(codePoint);
        if (symbol >= 0) {
            patched = Math.round(symbol * ppem / 1000f);
        }
        if (patched < 0 && widths != null) {
            int a = widths.advance(codePoint);
            patched = a >= 0 ? Math.round(a * ppem / 1000f) : -1;
        }
        return patched >= 0 ? patched : program.hintedAdvance(g, ppem);
    }

    /** A glyph's outline in font units with y pointing down, or null when Java cannot read this face. */
    public java.awt.Shape glyphOutline(int glyph) {
        return program.glyphOutline(glyph);
    }

    public int glyphCount() {
        return program.glyphCount();
    }

    public boolean sameProgram(FontFace other) {
        return other != null && other.program == program;
    }

    public boolean shapeable() {
        return program.awtFont() != null;
    }

    public GlyphRun shape(String text, boolean rightToLeft) {
        Objects.requireNonNull(text, "text");
        return Shaper.shape(this, symbols == null ? text : mappedText(text), rightToLeft);
    }

    private String mappedText(String text) {
        StringBuilder b = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> b.appendCodePoint(mapped(cp)));
        return b.toString();
    }

    public static boolean needsShaping(CharSequence text) {
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            if (complex(cp)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    static boolean complex(int cp) {
        if (cp < 0x0300) {
            return false;
        }
        int type = Character.getType(cp);
        if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK || cp == 0x200C || cp == 0x200D) {
            return true;
        }
        return cp >= 0x0590 && cp <= 0x08FF || cp >= 0x0900 && cp <= 0x0DFF || cp >= 0x0E00 && cp <= 0x0FFF
                || cp >= 0x1000 && cp <= 0x109F || cp >= 0x1100 && cp <= 0x11FF || cp >= 0x1780 && cp <= 0x18AF
                || cp >= 0x1A00 && cp <= 0x1CFF || cp >= 0xA800 && cp <= 0xABFF || cp >= 0xFB1D && cp <= 0xFDFF
                || cp >= 0xFE70 && cp <= 0xFEFF || cp >= 0x10A00 && cp <= 0x10A5F || cp >= 0x11000 && cp <= 0x11DFF;
    }

    public int encodedCodePoint(int codePoint) {
        return program.encode(mapped(codePoint));
    }

    static boolean ignorable(int cp) {
        return Character.getType(cp) == Character.FORMAT || Character.isISOControl(cp)
                || cp >= 0xFE00 && cp <= 0xFE0F || cp >= 0xE0100 && cp <= 0xE01EF;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FontFace f && f.program == program && f.syntheticBold == syntheticBold
                && f.syntheticItalic == syntheticItalic && f.symbols == symbols && f.widths == widths
                && Float.compare(f.stretch, stretch) == 0 && f.original == original
                && Arrays.equals(f.scripts, scripts) && Float.compare(f.embolden, embolden) == 0;
    }

    @Override
    public int hashCode() {
        int extras = System.identityHashCode(symbols) + System.identityHashCode(widths) + Float.hashCode(stretch)
                + System.identityHashCode(original) + Arrays.hashCode(scripts) + Float.hashCode(embolden);
        return System.identityHashCode(program) * 31 + extras * 4 + (syntheticBold ? 2 : 0) + (syntheticItalic ? 1 : 0);
    }

    @Override
    public String toString() {
        return "FontFace[" + family() + " " + subfamily() + (syntheticBold ? ", synthetic bold" : "")
                + (syntheticItalic ? ", synthetic italic" : "") + (substituted() ? ", for " + requestedFamily : "") + "]";
    }
}
