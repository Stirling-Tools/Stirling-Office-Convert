package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.io.Writer;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorN;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor;
import org.apache.pdfbox.contentstream.operator.color.SetStrokingColor;
import org.apache.pdfbox.contentstream.operator.color.SetStrokingColorN;
import org.apache.pdfbox.contentstream.operator.color.SetStrokingColorSpace;
import org.apache.pdfbox.contentstream.operator.color.SetStrokingDeviceCMYKColor;
import org.apache.pdfbox.contentstream.operator.color.SetStrokingDeviceGrayColor;
import org.apache.pdfbox.contentstream.operator.color.SetStrokingDeviceRGBColor;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;

final class GlyphCollector extends PDFTextStripper {

    private static final int MAX_PAGE_GLYPHS = 200_000;

    private static final float MIN_SPACE_EM = 0.15f;

    private static final float MAX_SPACE_EM = 0.4f;

    private static final float DEFAULT_SPACE_EM = 0.27f;

    private static final float ITALIC_SHEAR = 0.12f;

    interface PageSink {
        void accept(int pageIndex, PDPage page, List<RawGlyph> glyphs) throws IOException;
    }

    record RawGlyph(Glyph glyph, int direction, boolean invisible, float originX, float originY) {}

    private final FontResolver fonts;
    private final PageSink sink;
    private final UnicodeRecovery recovery;
    private final Map<org.apache.pdfbox.cos.COSDictionary, float[]> metrics = new WeakHashMap<>();
    private List<RawGlyph> current = new ArrayList<>();
    private boolean inPage;
    private int seq;
    private int pageIndex;
    private int nextIndex;
    private PDDocument document;
    private OperatorBudget budget = new OperatorBudget("reading text");

    private GlyphCollector(FontResolver fonts, PageSink sink) {
        this.fonts = fonts;
        this.sink = sink;
        this.recovery = new UnicodeRecovery(fonts);
        addOperator(new SetStrokingColorSpace(this));
        addOperator(new SetNonStrokingColorSpace(this));
        addOperator(new SetStrokingDeviceCMYKColor(this));
        addOperator(new SetNonStrokingDeviceCMYKColor(this));
        addOperator(new SetStrokingDeviceRGBColor(this));
        addOperator(new SetNonStrokingDeviceRGBColor(this));
        addOperator(new SetStrokingDeviceGrayColor(this));
        addOperator(new SetNonStrokingDeviceGrayColor(this));
        addOperator(new SetStrokingColor(this));
        addOperator(new SetStrokingColorN(this));
        addOperator(new SetNonStrokingColor(this));
        addOperator(new SetNonStrokingColorN(this));
        setSortByPosition(false);
        setShouldSeparateByBeads(false);
        setSuppressDuplicateOverlappingText(false);
    }

    static void read(PDDocument doc, int first, int last, FontResolver fonts, PageSink sink)
            throws IOException {
        GlyphCollector collector = new GlyphCollector(fonts, sink);
        collector.document = doc;
        collector.setStartPage(first + 1);
        collector.setEndPage(last + 1);
        collector.nextIndex = first;
        collector.writeText(doc, Writer.nullWriter());
        collector.blankPagesBefore(last + 1);
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
        if (!budget.run(operator)) {
            return;
        }
        try {
            super.processOperator(operator, operands);
        } catch (IOException | RuntimeException e) {
            BrokenOperators.skip(operator, e);
        }
    }

    @Override
    public void showForm(PDFormXObject form) throws IOException {
        if (budget.form()) {
            super.showForm(form);
        }
    }

    @Override
    public void showTransparencyGroup(PDTransparencyGroup form) throws IOException {
        if (budget.form()) {
            super.showTransparencyGroup(form);
        }
    }

    @Override
    public void processPage(PDPage page) throws IOException {
        try {
            super.processPage(page);
        } catch (IOException e) {
            if (!inPage) {
                throw e;
            }
            BrokenOperators.brokenStream(e);
            endPage(page);
        }
    }

    @Override
    protected void writePage() throws IOException {
        Annotations.show(this, getCurrentPage());
        super.writePage();
    }

    @Override
    protected void startPage(PDPage page) throws IOException {
        budget = new OperatorBudget("reading text");
        inPage = true;
        pageIndex = getCurrentPageNo() - 1;
        blankPagesBefore(pageIndex);
        current = new ArrayList<>();
        seq = 0;
    }

    @Override
    protected void endPage(PDPage page) throws IOException {
        inPage = false;
        List<RawGlyph> glyphs = current;
        current = new ArrayList<>();
        nextIndex = pageIndex + 1;
        sink.accept(pageIndex, page, glyphs);
    }

    private void blankPagesBefore(int index) throws IOException {
        while (nextIndex < index) {
            int blank = nextIndex++;
            sink.accept(blank, document.getPage(blank), List.of());
        }
    }

    @Override
    protected void processTextPosition(TextPosition tp) {
        String unicode = clean(recovery.text(tp, tp.getUnicode()));
        if (unicode == null) {
            return;
        }
        PDGraphicsState gs = getGraphicsState();
        RenderingMode mode = gs.getTextState().getRenderingMode();
        boolean invisible = mode == RenderingMode.NEITHER || mode == RenderingMode.NEITHER_CLIP;
        PDColor colour =
                mode.isStroke() && !mode.isFill() ? gs.getStrokingColor() : gs.getNonStrokingColor();
        int rgb = toRgb(colour);

        PDFont pdFont = tp.getFont();
        FontInfo font = fonts.resolve(pdFont);
        float size = fontSize(tp);
        float[] m = metrics(pdFont);
        float space = spaceWidth(tp, pdFont, size, m[2]);

        boolean bold = font.bold() || mode == RenderingMode.FILL_STROKE && strokeIsVisible(gs, size);
        boolean italic = font.italic() || isSheared(tp);
        float width = tp.getWidthDirAdj();
        if (!(width > 0)) {
            width = Math.max(size * 0.1f, 0.1f);
        }
        if (!onPage(tp, size, width)) {
            return;
        }
        Glyph g =
                new Glyph(
                        unicode,
                        tp.getXDirAdj(),
                        width,
                        tp.getYDirAdj(),
                        size,
                        size * m[0],
                        size * m[1],
                        font,
                        rgb,
                        seq++,
                        space,
                        bold,
                        italic);
        g.hscale = horizontalScale(tp);
        int[] codes = tp.getCharacterCodes();
        if (font.icons() && codes != null && codes.length == 1) {
            g.icon = IconShape.of(pdFont, codes[0], size, rgb);
        }
        Matrix tm = tp.getTextMatrix();
        if (current.size() >= MAX_PAGE_GLYPHS) {
            throw new PageTooComplexException("More than " + MAX_PAGE_GLYPHS + " characters on one page");
        }
        current.add(
                new RawGlyph(
                        g,
                        Math.floorMod(Math.round(tp.getDir()), 360),
                        invisible,
                        tm.getTranslateX(),
                        tm.getTranslateY()));
    }

    private boolean onPage(TextPosition tp, float size, float width) {
        float x = tp.getXDirAdj();
        float y = tp.getYDirAdj();
        if (!(size > 0) || !Float.isFinite(x) || !Float.isFinite(y)) {
            return false;
        }
        float w = tp.getPageWidth();
        float h = tp.getPageHeight();
        if (Math.round(tp.getDir()) == 0 && getCurrentPage().getRotation() % 360 == 0) {
            return x < w + 1 && x + width > -1 && y > -1 && y - size < h + 1;
        }
        float side = Math.max(w, h) + size;
        return x < side && x + width > -size && y > -size && y - size < side;
    }

    private static boolean strokeIsVisible(PDGraphicsState gs, float size) {
        Matrix ctm = gs.getCurrentTransformationMatrix();
        float lw = gs.getLineWidth() * (float) Math.sqrt(Math.abs(
                ctm.getScaleX() * ctm.getScaleY() - ctm.getShearX() * ctm.getShearY()));
        return lw > size * 0.005f;
    }

    private static boolean isSheared(TextPosition tp) {
        if (Math.round(tp.getDir()) != 0) {
            return false;
        }
        Matrix tm = tp.getTextMatrix();
        float d = tm.getScaleY();
        return d > 0 && Math.abs(tm.getShearY()) < 0.01f * d && Math.abs(tm.getShearX() / d) > ITALIC_SHEAR;
    }

    private float[] metrics(PDFont font) {
        if (font == null) {
            return new float[] {0.8f, 0.2f, Float.NaN};
        }
        final PDFont f = font;
        return metrics.computeIfAbsent(
                font.getCOSObject(),
                key -> {
                    float ascent = 0.8f;
                    float descent = 0.2f;
                    PDFontDescriptor fd = f.getFontDescriptor();
                    if (fd != null) {
                        float a = fd.getAscent() / 1000f;
                        float d = Math.abs(fd.getDescent()) / 1000f;
                        if (a >= 0.5f && a <= 1.2f) {
                            ascent = a;
                        }
                        if (d >= 0.05f && d <= 0.45f) {
                            descent = d;
                        }
                    }
                    return new float[] {ascent, descent, GlyphShapes.spaceEm(f)};
                });
    }

    private static float spaceWidth(TextPosition tp, PDFont font, float size, float spaceEm) {
        float space = !Float.isNaN(spaceEm) ? spaceEm * size * horizontalScale(tp)
                : font instanceof PDType3Font ? tp.getWidthOfSpace() : size * DEFAULT_SPACE_EM;
        return space > 0 ? Math.clamp(space, size * MIN_SPACE_EM, size * MAX_SPACE_EM) : size * DEFAULT_SPACE_EM;
    }

    static float horizontalScale(TextPosition tp) {
        boolean vertical = Math.round(tp.getDir()) % 180 != 0;
        float along = vertical ? tp.getYScale() : tp.getXScale();
        float across = vertical ? tp.getXScale() : tp.getYScale();
        float s = across > 0.5f ? along / across : 1f;
        return Float.isFinite(s) && s > 0.2f && s < 5f ? s : 1f;
    }

    static float fontSize(TextPosition tp) {
        float size = Math.abs(tp.getYScale());
        if (Math.round(tp.getDir()) % 180 != 0) {
            size = Math.abs(tp.getXScale());
        }
        if (size <= 0.5f || size > 400f) {
            size = tp.getFontSizeInPt();
        }
        if (size <= 0.5f || size > 400f) {
            size = Math.max(tp.getHeightDir() * 1.4f, 4f);
        }
        return size;
    }

    private static int toRgb(PDColor colour) {
        if (colour == null) {
            return 0;
        }
        try {
            return colour.toRGB() & 0xFFFFFF;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private static final String CP1252 =
            "\u20AC\u0000\u201A\u0192\u201E\u2026\u2020\u2021\u02C6\u2030\u0160\u2039\u0152\u0000\u017D\u0000"
                    + "\u0000\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u02DC\u2122\u0161\u203A\u0153\u0000\u017E\u0178";

    static String clean(String unicode) {
        if (unicode == null || unicode.isEmpty()) {
            return null;
        }
        boolean needsWork = false;
        for (int i = 0; i < unicode.length(); i++) {
            char c = unicode.charAt(i);
            if (c < 0x20 || c >= 0x80 && c <= 0x9F || (c >= 0xFB00 && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF)
                    || c == 0xFFFE || c == 0xFFFF || c == 0xFFFD || bidiControl(c)) {
                needsWork = true;
                break;
            }
        }
        if (!needsWork) {
            return unicode;
        }
        StringBuilder sb = new StringBuilder(unicode.length() + 2);
        for (int i = 0; i < unicode.length(); i++) {
            char c = unicode.charAt(i);
            if (c >= 0xFB00 && c <= 0xFDFF || c >= 0xFE70 && c <= 0xFEFE) {
                sb.append(Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFKC));
            } else if (c == '\t' || c == '\n' || c == '\r') {
                sb.append(' ');
            } else if (c >= 0x80 && c <= 0x9F) {
                char w = CP1252.charAt(c - 0x80);
                if (w != 0) {
                    sb.append(w);
                }
            } else if (c >= 0x20 && c != 0xFFFE && c != 0xFFFF && c != 0xFFFD && !bidiControl(c)) {
                sb.append(c);
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static boolean bidiControl(char c) {
        return c >= 0x202A && c <= 0x202E || c >= 0x2066 && c <= 0x2069;
    }
}
