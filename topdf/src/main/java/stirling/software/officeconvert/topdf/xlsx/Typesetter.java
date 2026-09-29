package stirling.software.officeconvert.topdf.xlsx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.topdf.font.BidiRuns;
import stirling.software.officeconvert.topdf.font.CloudFonts;
import stirling.software.officeconvert.topdf.font.CloudMetrics;
import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.font.FontRun;
import stirling.software.officeconvert.topdf.font.GlyphRun;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.TextStyle;

final class Typesetter {

    private record Key(String family, boolean bold, boolean italic) {}

    private final FontLibrary fonts;

    record Look(FontFace face, double scale, CloudMetrics metrics, String family, boolean bold, boolean italic) {

        boolean emulated() {
            return metrics != null || scale != 1;
        }

        double em(FontFace run, int cp) {
            float real = metrics == null ? Float.NaN : metrics.advance(cp);
            return Float.isNaN(real) ? (double) run.advance(cp) / run.unitsPerEm() * scale : real;
        }
    }

    private final Map<Key, Look> looks = new HashMap<>();

    private final Map<Key, Key> resolved = new HashMap<>();

    private CloudFonts cloud;

    private final Map<Key, FontMeasure> measures = new HashMap<>();

    private double pageScale = 1;

    Typesetter(FontLibrary fonts) {
        this.fonts = fonts;
    }

    FontLibrary fonts() {
        return fonts;
    }

    FontFace face(FontSpec f) {
        return look(f).face();
    }

    Look look(FontSpec f) {
        return looks.computeIfAbsent(resolve(f), this::look);
    }

    private Key resolve(FontSpec f) {
        return resolved.computeIfAbsent(new Key(f.family(), f.bold(), f.italic()), k -> {
            if (fonts.exact(k.family(), k.bold(), k.italic()) != null) {
                return k;
            }
            StyledName n = StyledName.of(k.family());
            return n == null ? k : new Key(n.family(), k.bold() || n.bold(), k.italic() || n.italic());
        });
    }

    private Look look(Key k) {
        FontFace plain = fonts.find(k.family(), k.bold(), k.italic());
        if (!plain.substituted()) {
            return new Look(plain, 1, null, k.family(), k.bold(), k.italic());
        }
        if (cloud == null) {
            cloud = new CloudFonts(fonts);
        }
        CloudFonts.Emulation e = cloud.emulate(k.family(), k.bold(), k.italic());
        double scale = e.scale() > 0 ? e.scale() / 100.0 : 1;
        return new Look(e.face(), scale, e.metrics(), k.family(), k.bold(), k.italic());
    }

    FontMeasure measure(FontSpec f) {
        return measures.computeIfAbsent(resolve(f), k -> FontMeasure.of(fonts, k.family(), k.bold(), k.italic()));
    }

    double width(String text, FontSpec f, double size) {
        if (text.isEmpty()) {
            return 0;
        }
        double w = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (FormatCode.isSpacer(c) || FormatCode.isFill(c)) {
                w += plainWidth(text.substring(start, i), f, size);
                if (FormatCode.isSpacer(c)) {
                    w += plainWidth(String.valueOf(FormatCode.marked(c)), f, size);
                }
                start = i + 1;
            }
        }
        return w + plainWidth(text.substring(start), f, size);
    }

    double screenWidth(String text, FontSpec f, int ppem) {
        Look look = look(f);
        FontFace primary = look.face();
        text = spaces(text, primary);
        boolean emulate = ScreenAdvances.emulates(look.family(), primary);
        double hinted = 0;
        double approx = 0;
        double missing = 0;
        for (FontRun r : fonts.runs(text, primary)) {
            FontFace face = r.face();
            boolean own = face.sameProgram(primary);
            boolean table = emulate && own;
            boolean cloudy = own && look.emulated();
            for (int i = 0; i < r.text().length(); ) {
                int cp = r.text().codePointAt(i);
                i += Character.charCount(cp);
                if (MissingGlyphs.missing(face, cp)) {
                    missing += MissingGlyphs.em(cp) * ppem;
                    continue;
                }
                int exact = table ? ScreenAdvances.calibri(look.bold(), look.italic(), cp, ppem) : -1;
                if (cloudy && !skipped(cp)) {
                    exact = (int) Math.round(look.em(face, cp) * ppem);
                }
                if (exact < 0) {
                    exact = face.hintedAdvance(cp, ppem);
                }
                if (exact >= 0) {
                    hinted += exact;
                } else {
                    approx += approximateAdvance(face, cp, ppem);
                }
            }
        }
        if (look.bold() && !primary.bold()) {
            hinted += text.length();
        }
        return hinted * screenFactor(look.bold(), ppem) + (look.bold() ? approx : approx * HINTED_REGULAR) + missing;
    }


    static String spaces(String text, FontFace face) {
        if (text.indexOf(NBSP) < 0 || face.covers(NBSP) || !face.covers(' ')) {
            return text;
        }
        return text.replace(NBSP, ' ');
    }

    static final char NBSP = '\u00A0';

    static double screenFactor(boolean bold, int ppem) {
        if (bold) {
            return ppem <= SMALL_BOLD_PPEM ? SCREEN_SMALL_BOLD : SCREEN_BOLD;
        }
        return SCREEN_REGULAR;
    }

    private static double approximateAdvance(FontFace face, int cp, int ppem) {
        double scale = (double) ppem / face.unitsPerEm();
        int adv = face.advance(cp);
        if (cp >= '0' && cp <= '9') {
            return Math.floor(adv * scale);
        }
        float[] ink = face.inkBounds(cp);
        if (ink == null) {
            return Math.round(adv * scale);
        }
        return Math.round(ink[0] * scale) + Math.max(1, Math.round((ink[1] - ink[0]) * scale))
                + Math.round((adv - ink[1]) * scale);
    }

    static final double HINTED_REGULAR = 1.025;

    static final double SCREEN_REGULAR = 1.037;

    static final double SCREEN_BOLD = 1.028;

    static final int SMALL_BOLD_PPEM = 9;

    static final double SCREEN_SMALL_BOLD = 1.38;

    double width(List<TextRun> runs, double scale) {
        double w = 0;
        for (TextRun r : runs) {
            w += width(r.text(), r.font(), r.font().drawSize() * scale);
        }
        return w;
    }

    void pageScale(double scale) {
        this.pageScale = scale > 0 ? scale : 1;
    }

    double grid(double size) {
        double device = size * pageScale * 600 / 72;
        double rounded = Math.max(1, Math.round(device));
        return rounded * 72 / 600 / pageScale;
    }

    private double plainWidth(String text, FontSpec f, double size) {
        size = grid(size);
        if (text.isEmpty() || !(size > 0)) {
            return 0;
        }
        FontFace face = face(f);
        text = spaces(text, face);
        if (complex(text)) {
            double w = 0;
            for (Piece p : pieces(text, face)) {
                w += p.width(size);
            }
            return w;
        }
        Look look = look(f);
        double w = 0;
        for (FontRun run : fonts.runs(text, face)) {
            for (FontRun r : parts(run)) {
                if (MissingGlyphs.any(r.face(), r.text())) {
                    w += MissingGlyphs.width(r.text(), size);
                    continue;
                }
                double hinted = runWidth(look, face, r, size);
                w += hinted >= 0 ? hinted : TextStyle.of(r.face(), (float) size).width(r.text());
            }
        }
        return w;
    }

    // Splits a run where its face stops covering the text, so missing characters can be measured apart
    private static List<FontRun> parts(FontRun run) {
        String text = run.text();
        if (!MissingGlyphs.any(run.face(), text)) {
            return List.of(run);
        }
        List<FontRun> out = new ArrayList<>();
        int start = 0;
        Boolean state = null;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            boolean miss = MissingGlyphs.missing(run.face(), cp);
            if (state != null && miss != state) {
                out.add(new FontRun(run.face(), run.start() + start, run.start() + i, text.substring(start, i)));
                start = i;
            }
            state = miss;
            i += Character.charCount(cp);
        }
        out.add(new FontRun(run.face(), run.start() + start, run.start() + text.length(), text.substring(start)));
        return out;
    }

    private double runWidth(Look look, FontFace primary, FontRun r, double size) {
        if (!look.emulated() || !r.face().sameProgram(primary)) {
            return hintedWidth(r.face(), r.text(), size);
        }
        long ppem = Math.round(size * pageScale * 600 / 72);
        if (ppem < 1) {
            return -1;
        }
        long px = 0;
        String text = r.text();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!skipped(cp)) {
                px += Math.round(look.em(r.face(), cp) * ppem);
            }
        }
        return px * PrintMetrics.PX / pageScale;
    }

    private double hintedWidth(FontFace face, String text, double size) {
        long ppem = Math.round(size * pageScale * 600 / 72);
        if (ppem < 1 || ppem > 400) {
            return -1;
        }
        long px = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (skipped(cp)) {
                continue;
            }
            int a = face.hintedAdvance(cp, (int) ppem);
            if (a < 0) {
                return -1;
            }
            px += a;
        }
        return px * PrintMetrics.PX / pageScale;
    }

    private static boolean skipped(int cp) {
        return Character.isISOControl(cp) || cp == FontFace.SOFT_HYPHEN;
    }

    private double drawRun(PdfCanvas canvas, Look look, FontFace primary, FontRun r, double size, double x,
            double baseline, TextStyle style) throws IOException {
        TextStyle st = style.face(r.face());
        if (look.emulated() && r.face().sameProgram(primary)) {
            st = st.horizontalScale((float) (look.scale() * 100));
        }
        double hinted = runWidth(look, primary, r, size);
        long glyphs = r.text().codePoints().filter(cp -> !skipped(cp)).count();
        if (hinted >= 0 && glyphs > 0) {
            st = st.charSpacing((float) ((hinted - st.width(r.text())) / glyphs));
        }
        return canvas.text(r.text(), (float) x, (float) baseline, st);
    }

    double draw(PdfCanvas canvas, String text, FontSpec f, double size, double x, double baseline)
            throws IOException {
        if (text.isEmpty() || !(size > 0)) {
            return 0;
        }
        double at = x;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (FormatCode.isSpacer(c) || FormatCode.isFill(c)) {
                at += drawPlain(canvas, text.substring(start, i), f, size, at, baseline);
                if (FormatCode.isSpacer(c)) {
                    at += plainWidth(String.valueOf(FormatCode.marked(c)), f, size);
                }
                start = i + 1;
            }
        }
        at += drawPlain(canvas, text.substring(start), f, size, at, baseline);
        double w = at - x;
        decorate(canvas, f, size, x, baseline, w);
        return w;
    }

    void decorate(PdfCanvas canvas, FontSpec f, double size, double x, double baseline, double w) throws IOException {
        if (w <= 0) {
            return;
        }
        TextStyle style = TextStyle.of(face(f), (float) size).color(f.color());
        if (f.underline() != FontSpec.Underline.NONE) {
            canvas.underline((float) x, (float) baseline, (float) w, style);
            if (f.underline() == FontSpec.Underline.DOUBLE || f.underline() == FontSpec.Underline.DOUBLE_ACCOUNTING) {
                canvas.underline((float) x, (float) (baseline + size * 0.12), (float) w, style);
            }
        }
        if (f.strike()) {
            canvas.strikeout((float) x, (float) baseline, (float) w, style);
        }
    }

    private double drawPlain(PdfCanvas canvas, String text, FontSpec f, double size, double x, double baseline)
            throws IOException {
        size = grid(size);
        if (text.isEmpty()) {
            return 0;
        }
        FontFace face = face(f);
        text = spaces(text, face);
        TextStyle style = TextStyle.of(face, (float) size).color(f.color());
        if (complex(text)) {
            double at = x;
            for (Piece p : pieces(text, face)) {
                if (p.glyphs != null) {
                    at += canvas.drawGlyphs(p.glyphs, (float) at, (float) baseline, style.face(p.glyphs.face()));
                } else {
                    at += canvas.text(p.text, (float) at, (float) baseline, style.face(p.face));
                }
            }
            return at - x;
        }
        Look look = look(f);
        double at = x;
        for (FontRun run : fonts.runs(text, face)) {
            for (FontRun r : parts(run)) {
                at += MissingGlyphs.any(r.face(), r.text())
                        ? MissingGlyphs.draw(canvas, r.text(), size, at, baseline, f.color())
                        : drawRun(canvas, look, face, r, size, at, baseline, style);
            }
        }
        return at - x;
    }

    private static boolean complex(String text) {
        return FontFace.needsShaping(text) || BidiRuns.needed(text);
    }

    private record Piece(String text, FontFace face, GlyphRun glyphs) {

        double width(double size) {
            if (glyphs != null) {
                return glyphs.width((float) size);
            }
            return TextStyle.of(face, (float) size).width(text);
        }
    }

    private List<Piece> pieces(String text, FontFace primary) {
        List<Piece> out = new ArrayList<>();
        List<BidiRuns.Run> runs = BidiRuns.visual(BidiRuns.logical(text, null));
        for (BidiRuns.Run run : runs) {
            String part = text.substring(run.start(), run.end());
            List<FontRun> fr = fonts.runs(part, primary);
            if (run.rightToLeft()) {
                fr = new ArrayList<>(fr);
                java.util.Collections.reverse(fr);
            }
            for (FontRun r : fr) {
                if (FontFace.needsShaping(r.text()) || run.rightToLeft()) {
                    out.add(new Piece(r.text(), r.face(), r.face().shape(r.text(), run.rightToLeft())));
                } else {
                    out.add(new Piece(r.text(), r.face(), null));
                }
            }
        }
        return out;
    }
}
