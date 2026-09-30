package stirling.software.officeconvert.topdf.pdf;

import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.function.PDFunction;
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType2;
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType3;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType3;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.util.Matrix;

import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontRun;
import stirling.software.officeconvert.topdf.font.GlyphRun;
import stirling.software.officeconvert.topdf.font.PdfFonts;
import stirling.software.officeconvert.topdf.io.DecodedPicture;

public final class PdfCanvas implements Closeable {

    public static final float ITALIC_SKEW = 0.2126f;

    public static final float BOLD_STROKE = 0.035f;

    private static final int SKIPPED = -1;

    private static final int GLYPHS = 0;

    private static final int MISSING = 1;

    private static final int FITTED = 2;

    private static final int BORROWED = 3;

    private static final int SILENT = 1;

    private static final int SPOKEN = 2;

    private final PdfOutput output;

    private final PDPage page;

    private final PDAppearanceStream form;

    private final int index;

    private final float width;

    private final float height;

    private final PDPageContentStream cs;

    private final Deque<AffineTransform> saved = new ArrayDeque<>();

    private final Map<COSDictionary, String> fontNames = new IdentityHashMap<>();

    private AffineTransform ctm = new AffineTransform();

    private final List<TextOrder.Piece> pending = new ArrayList<>();

    private boolean closed;

    private boolean hiddenText;

    PdfCanvas(PdfOutput output, PDPage page, int index) throws IOException {
        this.output = output;
        this.page = page;
        this.form = null;
        this.index = index;
        this.width = page.getMediaBox().getWidth();
        this.height = page.getMediaBox().getHeight();
        this.cs = new PDPageContentStream(output.document(), page, AppendMode.OVERWRITE, true);
        cs.saveGraphicsState();
        cs.transform(new Matrix(1, 0, 0, -1, 0, height));
    }

    // A canvas whose drawing becomes a form XObject: the same coordinates as a page, links and destinations left out
    PdfCanvas(PdfOutput output, float width, float height, float margin) throws IOException {
        this.output = output;
        this.page = null;
        this.form = new PDAppearanceStream(output.document());
        this.index = -1;
        this.width = width;
        this.height = height;
        form.setBBox(new PDRectangle(-margin, -margin, width + 2 * margin, height + 2 * margin));
        form.setResources(new PDResources());
        this.cs = new PDPageContentStream(output.document(), form,
                form.getContentStream().createOutputStream(COSName.FLATE_DECODE));
        cs.saveGraphicsState();
        cs.transform(new Matrix(1, 0, 0, -1, 0, height));
    }

    /** The form XObject a canvas made by {@link PdfOutput#newForm} draws into, or null for a page. */
    public PDFormXObject form() {
        return form;
    }

    private PDResources resources() {
        return page != null ? page.getResources() : form.getResources();
    }

    public PdfOutput output() {
        return output;
    }

    public PDPage page() {
        return page;
    }

    public int pageIndex() {
        return index;
    }

    public float width() {
        return width;
    }

    public float height() {
        return height;
    }

    public AffineTransform transform() {
        return new AffineTransform(ctm);
    }

    // Text written while hidden is kept for search and copy but not painted; returns the previous setting
    public boolean hideText(boolean hide) {
        boolean was = hiddenText;
        hiddenText = hide;
        return was;
    }

    public void save() throws IOException {
        ready();
        cs.saveGraphicsState();
        saved.push(new AffineTransform(ctm));
    }

    public void restore() throws IOException {
        ready();
        if (saved.isEmpty()) {
            throw new IllegalStateException("restore() without a matching save()");
        }
        cs.restoreGraphicsState();
        ctm = saved.pop();
    }

    public void transform(AffineTransform t) throws IOException {
        Objects.requireNonNull(t, "transform");
        ready();
        if (t.isIdentity()) {
            return;
        }
        cs.transform(new Matrix(t));
        ctm.concatenate(t);
    }

    public void translate(float x, float y) throws IOException {
        transform(AffineTransform.getTranslateInstance(x, y));
    }

    public void scale(float sx, float sy) throws IOException {
        transform(AffineTransform.getScaleInstance(sx, sy));
    }

    public void rotate(float degrees, float cx, float cy) throws IOException {
        transform(AffineTransform.getRotateInstance(Math.toRadians(degrees), cx, cy));
    }

    public void clip(Shape shape) throws IOException {
        Objects.requireNonNull(shape, "shape");
        ready();
        boolean evenOdd = append(shape);
        if (evenOdd) {
            cs.clipEvenOdd();
        } else {
            cs.clip();
        }
    }

    public void clipRect(float x, float y, float w, float h) throws IOException {
        clip(new Rectangle2D.Float(x, y, w, h));
    }

    public float text(String text, float x, float baseline, TextStyle style) throws IOException {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(style, "style");
        open();
        if (text.isEmpty()) {
            return 0;
        }
        PdfFonts.Embedded embedded = output.fonts().font(style.face());
        FontFace face = embedded.face();
        StringBuilder ops = new StringBuilder(text.length() * 5 + 96);
        int kind = SKIPPED;
        FontFace cover = null;
        int start = 0;
        float at = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int k = kind(face, cp);
            FontFace f = null;
            if (k == MISSING) {
                f = output.fonts().fallback(cp, face);
                k = f == null ? MISSING : BORROWED;
            }
            if (k != SKIPPED && (k != kind || f != cover)) {
                if (kind >= 0 && i > start) {
                    at += segment(ops, text.substring(start, i), kind, embedded, cover, x + at, baseline, style);
                    start = i;
                }
                kind = k;
                cover = f;
            }
            i += Character.charCount(cp);
        }
        at += segment(ops, text.substring(start), Math.max(kind, GLYPHS), embedded, cover, x + at, baseline, style);
        queue(ops, x, x + at, baseline, style.size(), text);
        return at;
    }

    // Missing characters and fitted symbols draw apart from the glyphs, in text order so that text extracts in order
    private static int kind(FontFace face, int cp) {
        if (TextStyle.skipped(cp) && !face.symbolCode(cp)) {
            return SKIPPED;
        }
        if (!face.covers(cp)) {
            return visibleMissing(cp) ? MISSING : GLYPHS;
        }
        return face.symbolStandIn() && face.symbolFit(cp) != null ? FITTED : GLYPHS;
    }

    private float segment(StringBuilder ops, String text, int kind, PdfFonts.Embedded embedded, FontFace cover,
            float x, float baseline, TextStyle style) throws IOException {
        return switch (kind) {
            case MISSING -> missing(ops, text, x, baseline, style, embedded.face());
            case BORROWED -> borrowed(ops, text, cover, x, baseline, style, embedded.face());
            case FITTED -> fitted(ops, text, embedded, x, baseline, style);
            default -> glyphs(ops, text, embedded, x, baseline, style);
        };
    }

    private float glyphs(StringBuilder ops, String text, PdfFonts.Embedded embedded, float x, float baseline,
            TextStyle style) throws IOException {
        FontFace face = embedded.face();
        float size = style.size();
        float th = style.horizontalScale() / 100f;
        boolean perGlyph = face.scaledPerGlyph();
        float scale = perGlyph ? 1 : face.glyphStretch();
        float unit = size * th * scale / 1000f;
        StringBuilder tj = new StringBuilder(text.length() * 4 + 32);
        if (!perGlyph) {
            tj.append('[');
        }
        boolean segment = !perGlyph;
        int[] glyphs = new int[text.length()];
        int[] codes = new int[text.length()];
        int used = 0;
        boolean open = false;
        boolean any = false;
        float pending = 0;
        float drawn = 0;
        int previous = -1;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (TextStyle.skipped(cp) && !face.symbolCode(cp)) {
                continue;
            }
            float advance = face.advance(cp) * size / face.unitsPerEm() * th + style.charSpacing();
            if (style.kerning() && previous >= 0) {
                float kern = face.kerning(previous, cp) * size / face.unitsPerEm() * th;
                pending += kern;
                drawn += kern;
            }
            int glyph = face.covers(cp) ? face.glyph(cp) : 0;
            if (glyph > 0) {
                if (perGlyph) {
                    float s = Math.round(face.glyphScale(cp) * 200) / 200f;
                    if (segment && (cp == ' ' || cp == 0xA0)) {
                        s = scale;
                    }
                    if (!segment || s != scale) {
                        if (open) {
                            tj.append('>');
                            open = false;
                        }
                        if (segment) {
                            tj.append("] TJ\n");
                        }
                        scale = s;
                        unit = size * th * scale / 1000f;
                        Numbers.append(tj, th * scale * 100).append(" Tz ");
                        if (style.charSpacing() != 0) {
                            Numbers.append(tj, style.charSpacing() / (th * scale)).append(" Tc ");
                        }
                        tj.append('[');
                        segment = true;
                    }
                }
                if (pending != 0) {
                    if (open) {
                        tj.append('>');
                        open = false;
                    }
                    Numbers.append(tj.append(' '), -pending / unit).append(' ');
                    pending = 0;
                }
                if (!open) {
                    tj.append('<');
                    open = true;
                }
                Numbers.hex(tj, glyph, 4);
                glyphs[used] = glyph;
                codes[used++] = face.encodedCodePoint(cp);
                any = true;
                previous = cp;
                pending += (face.advance(cp) - face.glyphAdvance(glyph) * scale) * size / face.unitsPerEm() * th;
            } else {
                pending += advance;
                previous = -1;
            }
            drawn += advance;
            if (cp == ' ' && style.wordSpacing() != 0) {
                pending += style.wordSpacing();
                drawn += style.wordSpacing();
            }
        }
        if (!any) {
            return drawn;
        }
        if (open) {
            tj.append('>');
        }
        tj.append("] TJ\n");
        output.fonts().used(embedded, glyphs, codes, used);
        begin(ops, style, outline(face, size));
        font(ops, embedded, size);
        if (!perGlyph && style.charSpacing() != 0) {
            Numbers.append(ops, style.charSpacing() / (th * scale)).append(" Tc\n");
        }
        if (!perGlyph && th * scale != 1) {
            Numbers.append(ops, th * scale * 100).append(" Tz\n");
        }
        matrix(ops, 1, face.syntheticItalic() ? ITALIC_SKEW : 0, -1, x, baseline).append(tj).append("ET\nQ\n");
        return drawn;
    }

    // Characters no font has show a missing-glyph box, as Word shows them, and still extract as their text
    private float missing(StringBuilder ops, String text, float x, float baseline, TextStyle style, FontFace face)
            throws IOException {
        PdfFonts.Embedded tofu = output.fonts().missing(face);
        FontFace boxFace = tofu.face();
        float size = style.size();
        float th = style.horizontalScale() / 100f;
        float natural = boxFace.glyphAdvance(0) * size / boxFace.unitsPerEm();
        float skew = face.syntheticItalic() ? ITALIC_SKEW : 0;
        StringBuilder b = new StringBuilder("0 Tc\n");
        float drawn = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (TextStyle.skipped(cp) && !face.symbolCode(cp)) {
                continue;
            }
            float box = face.advance(cp) * size / face.unitsPerEm() * th;
            float tz = natural > 0 ? Math.min(100, 100 * box / natural) : 100;
            Numbers.append(b, tz).append(" Tz ");
            matrix(b, 1, skew, -1, x + drawn, baseline).setLength(b.length() - 1);
            b.append(" [<");
            Numbers.hex(b, output.fonts().missingCode(tofu, cp), 4).append(">] TJ\n");
            drawn += box + style.charSpacing();
        }
        begin(ops, style, 0);
        font(ops, tofu, size);
        ops.append(b).append("ET\nQ\n");
        return drawn;
    }

    // A character the face lacks but another installed font has, drawn from that font in the space measured for it
    private float borrowed(StringBuilder ops, String text, FontFace cover, float x, float baseline, TextStyle style,
            FontFace face) throws IOException {
        PdfFonts.Embedded embedded = output.fonts().font(cover);
        FontFace drawn = embedded.face();
        float size = style.size();
        float th = style.horizontalScale() / 100f;
        float skew = face.syntheticItalic() ? ITALIC_SKEW : 0;
        int[] glyphs = new int[text.length()];
        int[] codes = new int[text.length()];
        int n = 0;
        StringBuilder b = new StringBuilder("0 Tc\n");
        float advanced = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (TextStyle.skipped(cp) && !face.symbolCode(cp)) {
                continue;
            }
            float box = face.advance(cp) * size / face.unitsPerEm() * th;
            int glyph = drawn.covers(cp) ? drawn.glyph(cp) : 0;
            if (glyph > 0) {
                float natural = drawn.glyphAdvance(glyph) * size / drawn.unitsPerEm() * th;
                float fit = natural > box && natural > 0 ? box / natural : 1;
                Numbers.append(b, 100 * th * fit).append(" Tz ");
                matrix(b, 1, skew, -1, x + advanced + Math.max(0, box - natural) / 2, baseline)
                        .setLength(b.length() - 1);
                b.append(" [<");
                Numbers.hex(b, glyph, 4).append(">] TJ\n");
                glyphs[n] = glyph;
                codes[n++] = cp;
            }
            advanced += box + style.charSpacing();
        }
        if (n > 0) {
            output.fonts().used(embedded, glyphs, codes, n);
            begin(ops, style, outline(drawn, size));
            font(ops, embedded, size);
            ops.append(b).append("ET\nQ\n");
        }
        return advanced;
    }

    // Look-alikes of symbol-font glyphs, each scaled and moved to fill the symbol font's own glyph box
    private float fitted(StringBuilder ops, String text, PdfFonts.Embedded embedded, float x, float baseline,
            TextStyle style) throws IOException {
        FontFace face = embedded.face();
        int[] ids = new int[text.length()];
        int[] codes = new int[text.length()];
        int n = 0;
        float size = style.size();
        float th = style.horizontalScale() / 100f;
        float skew = face.syntheticItalic() ? ITALIC_SKEW : 0;
        StringBuilder b = new StringBuilder("100 Tz 0 Tc\n");
        float drawn = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (TextStyle.skipped(cp) && !face.symbolCode(cp)) {
                continue;
            }
            float[] fit = face.symbolFit(cp);
            if (fit != null) {
                ids[n] = face.glyph(cp);
                codes[n] = face.encodedCodePoint(cp);
                matrix(b, fit[0] * th, skew * fit[1], -fit[1], x + drawn + fit[2] * size * th + skew * fit[3] * size,
                        baseline - fit[3] * size).setLength(b.length() - 1);
                b.append(" [<");
                Numbers.hex(b, ids[n++], 4).append(">] TJ\n");
            }
            drawn += face.advance(cp) * size / face.unitsPerEm() * th + style.charSpacing();
            if (cp == ' ') {
                drawn += style.wordSpacing();
            }
        }
        if (n == 0) {
            return drawn;
        }
        output.fonts().used(embedded, ids, codes, n);
        begin(ops, style, outline(face, size));
        font(ops, embedded, size);
        ops.append(b).append("ET\nQ\n");
        return drawn;
    }

    // Opens a text object in the text's colour; a positive outline also strokes the glyphs that wide
    private void begin(StringBuilder b, TextStyle style, float outline) {
        Color color = style.color();
        float a = color.getAlpha() / 255f;
        b.append("q\n");
        if (a < 1) {
            b.append('/').append(resources().add(output.alpha(Math.max(0, a), Math.max(0, a))).getName())
                    .append(" gs\n");
        }
        rgb(b, color).append(" rg\n");
        if (outline > 0) {
            rgb(b, color).append(" RG ");
            Numbers.append(b, outline).append(" w 1 j\n");
        }
        b.append("BT\n");
        if (hiddenText) {
            b.append("3 Tr\n");
        } else if (outline > 0) {
            b.append("2 Tr\n");
        }
    }

    private static StringBuilder rgb(StringBuilder b, Color c) {
        Numbers.append(b, unit(c.getRed())).append(' ');
        Numbers.append(b, unit(c.getGreen())).append(' ');
        return Numbers.append(b, unit(c.getBlue()));
    }

    private static StringBuilder matrix(StringBuilder b, float a, float c, float d, float e, float f) {
        Numbers.append(b, a).append(" 0 ");
        Numbers.append(b, c).append(' ');
        Numbers.append(b, d).append(' ');
        Numbers.append(b, e).append(' ');
        return Numbers.append(b, f).append(" Tm\n");
    }

    // Synthetic bold and the extra weight of a lighter stand-in are both drawn as an outline stroke
    private static float outline(FontFace face, float size) {
        return size * ((face.syntheticBold() ? BOLD_STROKE : 0) + face.embolden());
    }

    // A character no installed font has shows the font's missing-glyph box, as Word shows it, instead of vanishing
    private static boolean visibleMissing(int cp) {
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.isISOControl(cp)) {
            return false;
        }
        int type = Character.getType(cp);
        return type != Character.FORMAT && type != Character.NON_SPACING_MARK && type != Character.ENCLOSING_MARK
                && type != Character.UNASSIGNED && type != Character.SURROGATE && !(cp >= 0xFE00 && cp <= 0xFE0F)
                && !(cp >= 0xE0100 && cp <= 0xE01EF) && cp != 0xFFFE && cp != 0xFFFF;
    }

    public float drawGlyphs(GlyphRun run, float x, float baseline, TextStyle style) throws IOException {
        return drawGlyphs(run, x, baseline, style, false);
    }

    public float drawGlyphs(GlyphRun run, float x, float baseline, TextStyle style, boolean actualText)
            throws IOException {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(style, "style");
        open();
        if (run.size() == 0) {
            return 0;
        }
        PdfFonts.Embedded embedded = output.fonts().font(run.face());
        FontFace face = embedded.face();
        float size = style.size();
        float th = style.horizontalScale() / 100f;
        if (!face.sameProgram(run.face())) {
            String visual = run.rightToLeft() ? new StringBuilder(run.text()).reverse().toString() : run.text();
            return text(visual, x, baseline, style.face(face).charSpacing(0).wordSpacing(0).kerning(false));
        }
        float scale = size / face.unitsPerEm() * th;
        float rise = size / face.unitsPerEm();
        float tz = th * run.stretch();
        float skew = face.syntheticItalic() ? ITALIC_SKEW : 0;
        int[] codes = output.fonts().used(embedded, run);
        StringBuilder ops = new StringBuilder();
        if (actualText) {
            ops.append("/Span <</ActualText ").append(textString(run.text())).append(">> BDC\n");
        }
        begin(ops, style, outline(face, size));
        font(ops, embedded, size);
        if (tz != 1) {
            Numbers.append(ops, tz * 100).append(" Tz\n");
        }
        StringBuilder tj = null;
        float pen = 0;
        float line = Float.NaN;
        int[][] reading = readingOrder(run, actualText ? new int[run.size()] : run.groups(), upright());
        int[] order = reading[0];
        int[] marks = reading[1];
        for (int k = 0; k < run.size(); k++) {
            int i = order[k];
            int glyph = run.glyph(i);
            if (glyph > 0) {
                float gx = run.x(i) * scale;
                float gy = run.y(i) * rise;
                if (gy != line) {
                    if (tj != null) {
                        ops.append(tj.append("] TJ\n"));
                    }
                    matrix(ops, 1, skew, -1, x + gx + skew * gy, baseline - gy);
                    tj = new StringBuilder("[");
                    pen = gx;
                    line = gy;
                }
                float adjust = -(gx - pen) / (size * tz) * 1000;
                if (Math.abs(adjust) >= 0.5f) {
                    Numbers.append(tj.append(' '), adjust).append(' ');
                    pen = gx;
                }
                if (tj.charAt(tj.length() - 1) == '>') {
                    tj.setLength(tj.length() - 1);
                } else {
                    tj.append('<');
                }
                Numbers.hex(tj, codes[i], 4).append('>');
                pen += Math.round(face.glyphAdvance(glyph) * 1000f / face.unitsPerEm()) / 1000f * size * tz;
            }
            if (marks[k] != 0) {
                if (tj != null) {
                    ops.append(tj.append("] TJ\n"));
                    tj = null;
                }
                ops.append(marks[k] == SILENT ? "/Span <</ActualText <FEFF>>> BDC\n" : "EMC\n");
                line = Float.NaN;
            }
        }
        if (tj != null) {
            ops.append(tj.append("] TJ\n"));
        }
        ops.append("ET\nQ\n");
        if (actualText) {
            ops.append("EMC\n");
        }
        float width = run.advance() * scale;
        queue(ops, x, x + width, baseline, size, run.text());
        return width;
    }

    // Glyphs in reading order, right-to-left runs from their right end as Word writes them; a group draws the
    // glyph that carries its text first and marks the others as saying nothing (SILENT opens, SPOKEN closes)
    private static int[][] readingOrder(GlyphRun run, int[] groups, boolean upright) {
        int n = run.size();
        List<int[]> segments = new ArrayList<>();
        for (int i = 0; i < n; ) {
            int end = groups[i] > 0 ? groups[i] : i + 1;
            segments.add(new int[] {i, end});
            i = end;
        }
        if (run.rightToLeft() && upright) {
            Collections.reverse(segments);
            segments.sort(Comparator.comparingInt(s -> firstCharacter(run, s)));
        }
        int[] order = new int[n];
        int[] marks = new int[n];
        int k = 0;
        for (int[] s : segments) {
            if (s[1] - s[0] == 1) {
                order[k++] = s[0];
                continue;
            }
            int carrier = run.carrier(s[0], s[1]);
            marks[k] = SILENT;
            order[k++] = carrier;
            for (int i = s[0]; i < s[1]; i++) {
                if (i != carrier) {
                    order[k++] = i;
                }
            }
            marks[k - 1] = SPOKEN;
        }
        return new int[][] {order, marks};
    }

    private static int firstCharacter(GlyphRun run, int[] segment) {
        int first = Integer.MAX_VALUE;
        for (int i = segment[0]; i < segment[1]; i++) {
            first = Math.min(first, run.cluster(i));
        }
        return first;
    }

    // Text waits for the next drawing so that the pieces of a line reach the page in reading order
    private void queue(StringBuilder ops, float x0, float x1, float baseline, float size, String text) {
        if (!ops.isEmpty()) {
            pending.add(new TextOrder.Piece(ops.toString(), Math.min(x0, x1), Math.max(x0, x1), baseline, size,
                    text, upright()));
        }
    }

    // Viewers find right-to-left lines only in upright text; rotated text keeps the order it is drawn in
    private boolean upright() {
        int rotated = AffineTransform.TYPE_FLIP | AffineTransform.TYPE_QUADRANT_ROTATION
                | AffineTransform.TYPE_GENERAL_ROTATION | AffineTransform.TYPE_GENERAL_TRANSFORM;
        return (ctm.getType() & rotated) == 0;
    }

    private void flush() throws IOException {
        if (pending.isEmpty()) {
            return;
        }
        List<TextOrder.Piece> pieces = new ArrayList<>(pending);
        pending.clear();
        for (TextOrder.Piece p : TextOrder.reading(pieces)) {
            raw(p.ops());
        }
    }

    public float text(List<FontRun> runs, float x, float baseline, TextStyle style) throws IOException {
        Objects.requireNonNull(runs, "runs");
        float at = x;
        for (FontRun r : runs) {
            at += text(r.text(), at, baseline, style.face(r.face()));
        }
        return at - x;
    }

    public void underline(float x, float baseline, float length, TextStyle style) throws IOException {
        FontFace face = style.face();
        float top = baseline - style.points(face.metrics().underlinePosition());
        float thick = Math.max(0.25f, style.points(face.metrics().underlineThickness()));
        rect(x, top, length, thick, Fill.solid(style.color()), null);
    }

    public void strikeout(float x, float baseline, float length, TextStyle style) throws IOException {
        FontFace face = style.face();
        float top = baseline - style.points(face.metrics().strikeoutPosition());
        float thick = Math.max(0.25f, style.points(face.metrics().strikeoutSize()));
        rect(x, top, length, thick, Fill.solid(style.color()), null);
    }

    public void line(float x1, float y1, float x2, float y2, Stroke stroke) throws IOException {
        draw(new Line2D.Float(x1, y1, x2, y2), null, Objects.requireNonNull(stroke, "stroke"));
    }

    public void rect(float x, float y, float w, float h, Fill fill, Stroke stroke) throws IOException {
        draw(new Rectangle2D.Float(x, y, w, h), fill, stroke);
    }

    public void roundRect(float x, float y, float w, float h, float rx, float ry, Fill fill, Stroke stroke)
            throws IOException {
        draw(new RoundRectangle2D.Float(x, y, w, h, 2 * Math.max(0, rx), 2 * Math.max(0, ry)), fill, stroke);
    }

    public void ellipse(float x, float y, float w, float h, Fill fill, Stroke stroke) throws IOException {
        draw(new Ellipse2D.Float(x, y, w, h), fill, stroke);
    }

    public void draw(Shape shape, Fill fill, Stroke stroke) throws IOException {
        Objects.requireNonNull(shape, "shape");
        ready();
        if (fill == null && stroke == null) {
            return;
        }
        if (fill != null && fill.gradient() != null) {
            gradient(shape, fill.gradient());
            fill = null;
            if (stroke == null) {
                return;
            }
        }
        cs.saveGraphicsState();
        try {
            float fa = fill == null ? 1 : fill.color().getAlpha() / 255f;
            float sa = stroke == null ? 1 : stroke.color().getAlpha() / 255f;
            alpha(fa, sa);
            if (fill != null) {
                setFill(fill.color());
            }
            if (stroke != null) {
                applyStroke(stroke);
            }
            boolean evenOdd = append(shape);
            if (fill != null && stroke != null) {
                if (evenOdd) {
                    cs.fillAndStrokeEvenOdd();
                } else {
                    cs.fillAndStroke();
                }
            } else if (fill != null) {
                if (evenOdd) {
                    cs.fillEvenOdd();
                } else {
                    cs.fill();
                }
            } else {
                cs.stroke();
            }
        } finally {
            cs.restoreGraphicsState();
        }
    }

    public void image(DecodedPicture picture, float x, float y, float w, float h) throws IOException {
        image(picture, x, y, w, h, Crop.NONE, 0, false, false, 1);
    }

    public void image(DecodedPicture picture, float x, float y, float w, float h, Crop crop, float rotation,
            boolean flipH, boolean flipV, float alpha) throws IOException {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(crop, "crop");
        ready();
        if (!(w > 0 && h > 0) || !(alpha > 0)) {
            return;
        }
        cs.saveGraphicsState();
        try {
            alpha(Math.min(1, alpha), 1);
            AffineTransform t = new AffineTransform();
            if (rotation != 0 || flipH || flipV) {
                double cx = x + w / 2.0;
                double cy = y + h / 2.0;
                t.translate(cx, cy);
                t.rotate(Math.toRadians(rotation));
                t.scale(flipH ? -1 : 1, flipV ? -1 : 1);
                t.translate(-cx, -cy);
                cs.transform(new Matrix(t));
            }
            if (crop.clips()) {
                cs.addRect(x, y, w, h);
                cs.clip();
            }
            float fw = w / (1 - crop.left() - crop.right());
            float fh = h / (1 - crop.top() - crop.bottom());
            float fx = x - crop.left() * fw;
            float fy = y - crop.top() * fh;
            if (picture.vector()) {
                placeForm(picture.form(), picture.naturalWidth(), picture.naturalHeight(), fx, fy, fw, fh);
            } else {
                cs.drawImage(picture.image(), new Matrix(fw, 0, 0, -fh, fx, fy + fh));
            }
        } finally {
            cs.restoreGraphicsState();
        }
    }

    public void form(PDFormXObject form, float x, float y, float w, float h) throws IOException {
        Objects.requireNonNull(form, "form");
        ready();
        PDRectangle box = form.getBBox();
        float bw = box == null ? w : box.getWidth();
        float bh = box == null ? h : box.getHeight();
        if (!(w > 0 && h > 0 && bw > 0 && bh > 0)) {
            return;
        }
        cs.saveGraphicsState();
        try {
            placeForm(form, bw, bh, x, y, w, h);
        } finally {
            cs.restoreGraphicsState();
        }
    }

    public boolean link(float x, float y, float w, float h, String url) throws IOException {
        open();
        String safe = SafeLinks.safeUrl(url);
        if (safe == null || !(w > 0 && h > 0) || page == null) {
            return false;
        }
        PDAnnotationLink link = new PDAnnotationLink();
        link.setRectangle(pdfRect(x, y, w, h));
        link.setBorderStyle(PdfOutput.noBorder());
        link.setPrinted(true);
        PDActionURI action = new PDActionURI();
        action.setURI(safe);
        link.setAction(action);
        addAnnotation(page, link);
        return true;
    }

    public void linkTo(float x, float y, float w, float h, String destination) {
        Objects.requireNonNull(destination, "destination");
        open();
        if (w > 0 && h > 0 && page != null) {
            output.link(page, pdfRect(x, y, w, h), destination);
        }
    }

    public void linkToPage(float x, float y, float w, float h, int pageIndex, float yTop) {
        open();
        if (w > 0 && h > 0 && page != null) {
            output.link(page, pdfRect(x, y, w, h), pageIndex, 0, yTop);
        }
    }

    public void destination(String name, float x, float yTop) {
        Objects.requireNonNull(name, "name");
        open();
        if (page == null) {
            return;
        }
        Point2D p = ctm.transform(new Point2D.Float(x, yTop), null);
        output.destination(name, index, (float) p.getX(), (float) p.getY());
    }

    public PDPageContentStream stream() {
        open();
        try {
            flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return cs;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            flush();
            while (!saved.isEmpty()) {
                saved.pop();
                cs.restoreGraphicsState();
            }
            cs.restoreGraphicsState();
        } finally {
            cs.close();
            output.closed(this);
        }
    }

    static void addAnnotation(PDPage page, PDAnnotation annotation) throws IOException {
        List<PDAnnotation> annotations = new ArrayList<>(page.getAnnotations());
        annotations.add(annotation);
        page.setAnnotations(annotations);
    }

    private void placeForm(PDFormXObject form, float bw, float bh, float x, float y, float w, float h)
            throws IOException {
        PDRectangle box = form.getBBox();
        float bx = box == null ? 0 : box.getLowerLeftX();
        float by = box == null ? 0 : box.getLowerLeftY();
        cs.transform(new Matrix(w / bw, 0, 0, -h / bh, x - bx * w / bw, y + h + by * h / bh));
        cs.drawForm(form);
    }

    private void open() {
        if (closed) {
            throw new IllegalStateException("The page was closed");
        }
    }

    private void ready() throws IOException {
        open();
        flush();
    }

    private void font(StringBuilder b, PdfFonts.Embedded embedded, float size) {
        String name = fontNames.get(embedded.font());
        if (name == null) {
            name = fontName(resources(), embedded.font());
            fontNames.put(embedded.font(), name);
        }
        Numbers.append(b.append('/').append(name).append(' '), size).append(" Tf\n");
    }

    // What PDResources.add(PDFont) names a font, for a font dictionary without a PDFont around it
    static String fontName(PDResources resources, COSDictionary font) {
        COSDictionary fonts = resources.getCOSObject().getCOSDictionary(COSName.FONT);
        if (fonts != null) {
            for (Map.Entry<COSName, COSBase> e : fonts.entrySet()) {
                COSBase v = e.getValue();
                if (v == font || v instanceof COSObject o && o.getObject() == font) {
                    return e.getKey().getName();
                }
            }
        }
        String key = "F1";
        if (fonts == null) {
            fonts = new COSDictionary();
            resources.getCOSObject().setItem(COSName.FONT, fonts);
        } else {
            int n = fonts.keySet().size();
            do {
                key = "F" + ++n;
            } while (fonts.containsKey(key));
        }
        fonts.setItem(COSName.getPDFName(key), font);
        return key;
    }

    @SuppressWarnings("deprecation")
    private void raw(String operators) throws IOException {
        cs.appendRawCommands(operators);
    }

    static String textString(String text) {
        StringBuilder b = new StringBuilder("<FEFF");
        for (byte x : text.getBytes(StandardCharsets.UTF_16BE)) {
            Numbers.hex(b, x & 0xFF, 2);
        }
        return b.append('>').toString();
    }

    private void alpha(float fill, float stroke) throws IOException {
        if (fill < 1 || stroke < 1) {
            cs.setGraphicsStateParameters(output.alpha(Math.max(0, fill), Math.max(0, stroke)));
        }
    }

    private void applyStroke(Stroke stroke) throws IOException {
        setStroke(stroke.color());
        cs.setLineWidth(stroke.width());
        cs.setLineCapStyle(stroke.cap().ordinal());
        cs.setLineJoinStyle(stroke.join().ordinal());
        cs.setMiterLimit(stroke.miterLimit());
        float[] dash = stroke.dashPattern();
        if (dash.length > 0) {
            cs.setLineDashPattern(dash, stroke.dashPhase());
        }
    }

    private boolean append(Shape shape) throws IOException {
        PathIterator it = shape.getPathIterator(null);
        float[] c = new float[6];
        float lastX = 0;
        float lastY = 0;
        while (!it.isDone()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> {
                    cs.moveTo(c[0], c[1]);
                    lastX = c[0];
                    lastY = c[1];
                }
                case PathIterator.SEG_LINETO -> {
                    cs.lineTo(c[0], c[1]);
                    lastX = c[0];
                    lastY = c[1];
                }
                case PathIterator.SEG_QUADTO -> {
                    cs.curveTo(lastX + 2f / 3f * (c[0] - lastX), lastY + 2f / 3f * (c[1] - lastY),
                            c[2] + 2f / 3f * (c[0] - c[2]), c[3] + 2f / 3f * (c[1] - c[3]), c[2], c[3]);
                    lastX = c[2];
                    lastY = c[3];
                }
                case PathIterator.SEG_CUBICTO -> {
                    cs.curveTo(c[0], c[1], c[2], c[3], c[4], c[5]);
                    lastX = c[4];
                    lastY = c[5];
                }
                case PathIterator.SEG_CLOSE -> cs.closePath();
                default -> {
                }
            }
            it.next();
        }
        return it.getWindingRule() == PathIterator.WIND_EVEN_ODD;
    }

    private void gradient(Shape shape, Gradient g) throws IOException {
        PDShading shading;
        try {
            shading = shading(g);
        } catch (IOException | RuntimeException e) {
            shading = null;
        }
        if (shading == null) {
            draw(shape, Fill.solid(g.average()), null);
            return;
        }
        cs.saveGraphicsState();
        try {
            float a = 0;
            for (Gradient.Stop s : g.stops()) {
                a += s.color().getAlpha() / 255f;
            }
            alpha(a / g.stops().size(), 1);
            boolean evenOdd = append(shape);
            if (evenOdd) {
                cs.clipEvenOdd();
            } else {
                cs.clip();
            }
            cs.shadingFill(shading);
        } finally {
            cs.restoreGraphicsState();
        }
    }

    /** Fills with a gradient whose stops differ in opacity, the opacities drawn as a luminosity soft mask. */
    public void gradientWithAlpha(Shape shape, Gradient g) throws IOException {
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(g, "gradient");
        ready();
        Rectangle2D b = shape.getBounds2D();
        PDShading colour;
        PDShading mask;
        try {
            colour = shading(recolour(g, false));
            mask = shading(recolour(g, true));
        } catch (IOException | RuntimeException e) {
            colour = null;
            mask = null;
        }
        if (colour == null || mask == null || b.isEmpty()) {
            draw(shape, Fill.of(g), null);
            return;
        }
        PDFormXObject form = new PDFormXObject(output.document());
        form.setBBox(new PDRectangle((float) b.getX(), (float) b.getY(), (float) b.getWidth(), (float) b.getHeight()));
        PDResources resources = new PDResources();
        COSName name = resources.add(mask);
        form.setResources(resources);
        COSDictionary group = new COSDictionary();
        group.setItem(COSName.TYPE, COSName.GROUP);
        group.setItem(COSName.S, COSName.TRANSPARENCY);
        group.setItem(COSName.CS, COSName.DEVICEGRAY);
        form.getCOSObject().setItem(COSName.GROUP, group);
        try (OutputStream os = form.getContentStream().createOutputStream()) {
            os.write(("/" + name.getName() + " sh\n").getBytes(StandardCharsets.US_ASCII));
        }
        COSDictionary smask = new COSDictionary();
        smask.setItem(COSName.TYPE, COSName.MASK);
        smask.setItem(COSName.S, COSName.LUMINOSITY);
        smask.setItem(COSName.G, form);
        PDExtendedGraphicsState state = new PDExtendedGraphicsState();
        state.getCOSObject().setItem(COSName.SMASK, smask);
        cs.saveGraphicsState();
        try {
            cs.setGraphicsStateParameters(state);
            if (append(shape)) {
                cs.clipEvenOdd();
            } else {
                cs.clip();
            }
            cs.shadingFill(colour);
        } finally {
            cs.restoreGraphicsState();
        }
    }

    // The same gradient fully opaque, or its opacities as greys
    private static Gradient recolour(Gradient g, boolean alpha) {
        List<Gradient.Stop> stops = new ArrayList<>();
        for (Gradient.Stop s : g.stops()) {
            Color c = s.color();
            stops.add(new Gradient.Stop(s.offset(), alpha ? new Color(c.getAlpha(), c.getAlpha(), c.getAlpha())
                    : new Color(c.getRed(), c.getGreen(), c.getBlue())));
        }
        return new Gradient(g.kind(), g.x1(), g.y1(), g.x2(), g.y2(), g.radius(), stops);
    }

    private static PDShading shading(Gradient g) throws IOException {
        List<Gradient.Stop> stops = new ArrayList<>(g.stops());
        if (stops.size() < 2) {
            return null;
        }
        List<Float> offsets = new ArrayList<>();
        float last = -1;
        for (Gradient.Stop s : stops) {
            float o = Math.max(0, Math.min(1, s.offset()));
            if (o <= last) {
                o = Math.min(1, last + 1e-4f);
            }
            offsets.add(o);
            last = o;
        }
        PDFunction function;
        if (stops.size() == 2 && offsets.get(0) == 0 && offsets.get(1) == 1) {
            function = pair(stops.get(0).color(), stops.get(1).color());
        } else {
            COSArray functions = new COSArray();
            COSArray bounds = new COSArray();
            COSArray encode = new COSArray();
            Color previous = stops.get(0).color();
            float start = 0;
            for (int i = 0; i < stops.size(); i++) {
                float o = offsets.get(i);
                if (o <= start && i > 0) {
                    previous = stops.get(i).color();
                    continue;
                }
                if (o > start) {
                    functions.add(pair(previous, stops.get(i).color()));
                    if (o < 1) {
                        bounds.add(new COSFloat(o));
                    }
                    encode.add(new COSFloat(0));
                    encode.add(new COSFloat(1));
                    start = o;
                }
                previous = stops.get(i).color();
            }
            if (start < 1) {
                functions.add(pair(previous, previous));
                encode.add(new COSFloat(0));
                encode.add(new COSFloat(1));
            }
            if (functions.size() - 1 != bounds.size()) {
                return null;
            }
            if (functions.size() == 1) {
                function = PDFunction.create(functions.getObject(0));
            } else {
                COSDictionary dict = new COSDictionary();
                dict.setInt(COSName.FUNCTION_TYPE, 3);
                dict.setItem(COSName.DOMAIN, floats(0, 1));
                dict.setItem(COSName.FUNCTIONS, functions);
                dict.setItem(COSName.BOUNDS, bounds);
                dict.setItem(COSName.ENCODE, encode);
                function = new PDFunctionType3(dict);
            }
        }
        COSArray extend = new COSArray();
        extend.add(COSBoolean.TRUE);
        extend.add(COSBoolean.TRUE);
        if (g.kind() == Gradient.Kind.LINEAR) {
            PDShadingType2 axial = new PDShadingType2(new COSDictionary());
            axial.setShadingType(PDShading.SHADING_TYPE2);
            axial.setColorSpace(PDDeviceRGB.INSTANCE);
            axial.setCoords(floats(g.x1(), g.y1(), g.x2(), g.y2()));
            axial.setFunction(function);
            axial.setExtend(extend);
            return axial;
        }
        PDShadingType3 radial = new PDShadingType3(new COSDictionary());
        radial.setShadingType(PDShading.SHADING_TYPE3);
        radial.setColorSpace(PDDeviceRGB.INSTANCE);
        radial.setCoords(floats(g.x1(), g.y1(), 0, g.x2(), g.y2(), g.radius()));
        radial.setFunction(function);
        radial.setExtend(extend);
        return radial;
    }

    private static PDFunctionType2 pair(Color a, Color b) {
        COSDictionary dict = new COSDictionary();
        dict.setInt(COSName.FUNCTION_TYPE, 2);
        dict.setItem(COSName.DOMAIN, floats(0, 1));
        dict.setItem(COSName.C0, floats(office(a)));
        dict.setItem(COSName.C1, floats(office(b)));
        dict.setInt(COSName.N, 1);
        return new PDFunctionType2(dict);
    }

    // Office writes colour components with three significant digits (128 as 0.502), so renderers round alike
    private static float[] office(Color c) {
        return new float[] {unit(c.getRed()), unit(c.getGreen()), unit(c.getBlue())};
    }

    private void setFill(Color c) throws IOException {
        cs.setNonStrokingColor(new PDColor(office(c), PDDeviceRGB.INSTANCE));
    }

    private void setStroke(Color c) throws IOException {
        cs.setStrokingColor(new PDColor(office(c), PDDeviceRGB.INSTANCE));
    }

    private static final float[] UNITS = new float[256];

    static {
        for (int i = 1; i < 255; i++) {
            UNITS[i] = new BigDecimal(i / 255.0).round(new MathContext(3)).floatValue();
        }
        UNITS[255] = 1;
    }

    private static float unit(int component) {
        return UNITS[Math.max(0, Math.min(255, component))];
    }

    private static COSArray floats(float... values) {
        COSArray a = new COSArray();
        a.setFloatArray(values);
        return a;
    }

    private PDRectangle pdfRect(float x, float y, float w, float h) {
        double[] pts = {x, y, x + w, y, x, y + h, x + w, y + h};
        ctm.transform(pts, 0, pts, 0, 4);
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (int i = 0; i < 8; i += 2) {
            minX = Math.min(minX, pts[i]);
            maxX = Math.max(maxX, pts[i]);
            minY = Math.min(minY, pts[i + 1]);
            maxY = Math.max(maxY, pts[i + 1]);
        }
        return new PDRectangle((float) minX, (float) (height - maxY), (float) (maxX - minX), (float) (maxY - minY));
    }
}
