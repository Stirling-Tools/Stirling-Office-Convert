package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.awt.geom.Rectangle2D;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.DrawObject;
import org.apache.pdfbox.contentstream.operator.markedcontent.BeginMarkedContentSequence;
import org.apache.pdfbox.contentstream.operator.markedcontent.BeginMarkedContentSequenceWithProperties;
import org.apache.pdfbox.contentstream.operator.markedcontent.EndMarkedContentSequence;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.Restore;
import org.apache.pdfbox.contentstream.operator.state.Save;
import org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters;
import org.apache.pdfbox.contentstream.operator.state.SetMatrix;
import org.apache.pdfbox.contentstream.operator.text.BeginText;
import org.apache.pdfbox.contentstream.operator.text.EndText;
import org.apache.pdfbox.contentstream.operator.text.MoveText;
import org.apache.pdfbox.contentstream.operator.text.MoveTextSetLeading;
import org.apache.pdfbox.contentstream.operator.text.NextLine;
import org.apache.pdfbox.contentstream.operator.text.SetCharSpacing;
import org.apache.pdfbox.contentstream.operator.text.SetFontAndSize;
import org.apache.pdfbox.contentstream.operator.text.SetTextHorizontalScaling;
import org.apache.pdfbox.contentstream.operator.text.SetTextLeading;
import org.apache.pdfbox.contentstream.operator.text.SetTextRenderingMode;
import org.apache.pdfbox.contentstream.operator.text.SetTextRise;
import org.apache.pdfbox.contentstream.operator.text.SetWordSpacing;
import org.apache.pdfbox.contentstream.operator.text.ShowText;
import org.apache.pdfbox.contentstream.operator.text.ShowTextAdjusted;
import org.apache.pdfbox.contentstream.operator.text.ShowTextLine;
import org.apache.pdfbox.contentstream.operator.text.ShowTextLineAndSpace;
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
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

final class GlyphCollector extends PDFStreamEngine {

    private static final int MAX_PAGE_GLYPHS = 200_000;

    private static final int MAX_SPAN_DEPTH = 256;

    private static final float MIN_SPACE_EM = 0.15f;

    private static final float MAX_SPACE_EM = 0.4f;

    private static final float DEFAULT_SPACE_EM = 0.27f;

    private static final float ITALIC_SHEAR = 0.12f;

    interface PageSink {
        void accept(int pageIndex, PDPage page, List<RawGlyph> glyphs) throws IOException;
    }

    record RawGlyph(Glyph glyph, int direction, boolean invisible, float originX, float originY) {}

    private static final String HIDDEN = new String(new char[] {'\uFFFC'});

    private static final class Span {
        final String actual;
        final int start;
        float lo = Float.MAX_VALUE;
        float hi = -Float.MAX_VALUE;
        float hiddenWidth;
        float hiddenBaseline;

        Span(String actual, int start) {
            this.actual = actual;
            this.start = start;
        }
    }

    private final FontResolver fonts;
    private final PageSink sink;
    private final UnicodeRecovery recovery;
    private final Map<org.apache.pdfbox.cos.COSDictionary, float[]> metrics = new WeakHashMap<>();
    private final Map<COSDictionary, Map<Integer, float[]>> inks = new WeakHashMap<>();
    private final Deque<Span> spans = new ArrayDeque<>();
    private Span outerActual;
    private int actualDepth;
    private int untracked;
    private List<RawGlyph> current = new ArrayList<>();
    private boolean inPage;
    private int seq;
    private int pageIndex;

    private int rotation;
    private int nextIndex;
    private PDDocument document;
    private OperatorBudget budget = new OperatorBudget("reading text");
    private final StreamRunner runner;
    private final TextPositions positions = new TextPositions();
    private int currentPageNo;
    private int startPage;
    private int endPage;

    private GlyphCollector(FontResolver fonts, PageSink sink, ParsedStreams parsed) {
        this.fonts = fonts;
        this.sink = sink;
        this.recovery = new UnicodeRecovery(fonts);
        this.runner = new StreamRunner(new StreamRunner.Host() {
            @Override
            public PDGraphicsState state() {
                return getGraphicsState();
            }

            @Override
            public Deque<PDGraphicsState> save() {
                return saveGraphicsStack();
            }

            @Override
            public void restore(Deque<PDGraphicsState> stack) {
                restoreGraphicsStack(stack);
            }

            @Override
            public void operator(Operator operator, List<COSBase> operands) throws IOException {
                processOperator(operator, operands);
            }
        }, parsed);
        addOperator(new BeginText(this));
        addOperator(new Concatenate(this));
        addOperator(new DrawObject(this));
        addOperator(new EndText(this));
        addOperator(new SetGraphicsStateParameters(this));
        addOperator(new Save(this));
        addOperator(new Restore(this));
        addOperator(new NextLine(this));
        addOperator(new SetCharSpacing(this));
        addOperator(new MoveText(this));
        addOperator(new MoveTextSetLeading(this));
        addOperator(new SetFontAndSize(this));
        addOperator(new ShowText(this));
        addOperator(new ShowTextAdjusted(this));
        addOperator(new SetTextLeading(this));
        addOperator(new SetMatrix(this));
        addOperator(new SetTextRenderingMode(this));
        addOperator(new SetTextRise(this));
        addOperator(new SetWordSpacing(this));
        addOperator(new SetTextHorizontalScaling(this));
        addOperator(new ShowTextLine(this));
        addOperator(new ShowTextLineAndSpace(this));
        addOperator(new BeginMarkedContentSequenceWithProperties(this));
        addOperator(new BeginMarkedContentSequence(this));
        addOperator(new EndMarkedContentSequence(this));
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
    }

    static void read(PDDocument doc, int first, int last, FontResolver fonts, ParsedStreams parsed, PageSink sink)
            throws IOException {
        GlyphCollector collector = new GlyphCollector(fonts, sink, parsed);
        collector.document = doc;
        collector.startPage = first + 1;
        collector.endPage = last + 1;
        collector.nextIndex = first;
        collector.currentPageNo = 1;
        for (PDPage page : doc.getPages()) {
            if (page.hasContents()) {
                collector.processPage(page);
            }
            collector.currentPageNo++;
        }
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
            runner.showForm(form);
        }
    }

    @Override
    public void showTransparencyGroup(PDTransparencyGroup form) throws IOException {
        if (budget.form()) {
            super.showTransparencyGroup(form);
        }
    }

    @Override
    protected void processTransparencyGroup(PDTransparencyGroup group) throws IOException {
        runner.processTransparencyGroup(group);
    }

    @Override
    protected void processAnnotation(PDAnnotation annotation, PDAppearanceStream appearance) throws IOException {
        runner.processAnnotation(annotation, appearance);
    }

    @Override
    public PDResources getResources() {
        return runner.resources();
    }

    @Override
    public PDPage getCurrentPage() {
        return runner.page();
    }

    @Override
    public Matrix getInitialMatrix() {
        return runner.initialMatrix();
    }

    @Override
    public boolean isShouldProcessColorOperators() {
        return runner.colors();
    }

    @Override
    public void processPage(PDPage page) throws IOException {
        if (currentPageNo < startPage || currentPageNo > endPage) {
            return;
        }
        try {
            startPage(page);
            positions.page(page);
            runner.processPage(page);
            Annotations.show(this, page);
            endPage(page);
            page.removePageResourceFromCache();
        } catch (IOException e) {
            if (!inPage) {
                throw e;
            }
            BrokenOperators.brokenStream(e);
            endPage(page);
        }
    }

    private void startPage(PDPage page) throws IOException {
        budget = new OperatorBudget("reading text");
        inPage = true;
        pageIndex = currentPageNo - 1;
        rotation = page.getRotation();
        blankPagesBefore(pageIndex);
        current = new ArrayList<>();
        spans.clear();
        outerActual = null;
        actualDepth = 0;
        untracked = 0;
        seq = 0;
    }

    @Override
    public void beginMarkedContentSequence(COSName tag, COSDictionary properties) {
        super.beginMarkedContentSequence(tag, properties);
        if (spans.size() >= MAX_SPAN_DEPTH) {
            untracked++;
            return;
        }
        String actual = properties == null ? null : properties.getString(COSName.ACTUAL_TEXT);
        Span span = new Span(actual, current.size());
        spans.push(span);
        if (actual != null && actualDepth++ == 0) {
            outerActual = span;
        }
    }

    @Override
    public void endMarkedContentSequence() {
        super.endMarkedContentSequence();
        if (untracked > 0) {
            untracked--;
            return;
        }
        Span span = spans.poll();
        if (span == null || span.actual == null) {
            return;
        }
        if (--actualDepth == 0) {
            outerActual = null;
            actualText(span);
        }
    }

    @Override
    protected void showGlyph(Matrix trm, PDFont font, int code, Vector displacement) throws IOException {
        TextPosition position = positions.of(getGraphicsState(), getTextMatrix(), trm, font, code, displacement);
        if (position != null) {
            processTextPosition(position);
        }
        Span open = outerActual;
        if (open == null || font instanceof PDSimpleFont || font.toUnicode(code) != null
                || rotation % 360 != 0 || Math.abs(trm.getShearX()) > 0.01f * Math.abs(trm.getScaleX())
                || Math.abs(trm.getShearY()) > 0.01f * Math.abs(trm.getScaleX()) || !(trm.getScaleX() > 0)) {
            return;
        }
        float x = trm.getTranslateX() - getCurrentPage().getCropBox().getLowerLeftX();
        float advance = Math.max(0, displacement.getX() * trm.getScaleX());
        open.lo = Math.min(open.lo, x);
        open.hi = Math.max(open.hi, x + advance);
        if (advance > open.hiddenWidth) {
            open.hiddenWidth = advance;
            open.hiddenBaseline = getCurrentPage().getCropBox().getUpperRightY() - trm.getTranslateY();
        }
    }

    private void actualText(Span s) {
        if (s.start < 0 || s.start >= current.size()) {
            return;
        }
        List<RawGlyph> span = current.subList(s.start, current.size());
        if (!merge(s, span)) {
            span.removeIf(r -> r.glyph().text == HIDDEN);
        }
    }

    private boolean merge(Span s, List<RawGlyph> span) {
        String text = clean(s.actual);
        if (text == null || text.isBlank()) {
            return false;
        }
        RawGlyph main = span.getFirst();
        for (RawGlyph r : span) {
            if (r.glyph().width > main.glyph().width) {
                main = r;
            }
        }
        Glyph m = main.glyph();
        StringBuilder drawn = new StringBuilder();
        boolean upright = main.direction() == 0 && rotation % 360 == 0;
        float lo = upright ? s.lo : Float.MAX_VALUE;
        float hi = upright ? s.hi : -Float.MAX_VALUE;
        for (RawGlyph r : span) {
            Glyph g = r.glyph();
            if (r.direction() != main.direction() || r.invisible() != main.invisible()
                    || Math.abs(g.baseline - m.baseline) > 0.9f * Math.max(g.size, m.size)) {
                return false;
            }
            if (g.text == HIDDEN) {
                s.hiddenWidth = Math.max(s.hiddenWidth, Float.MIN_VALUE);
            } else {
                drawn.append(g.text);
            }
            lo = Math.min(lo, g.x);
            hi = Math.max(hi, g.right());
        }
        if (joiners(text)) {
            hi = lo + 0.01f;
        }
        if (drawn.toString().equals(text) && s.hiddenWidth == 0) {
            return true;
        }
        float baseline = upright && s.hiddenWidth > m.width ? s.hiddenBaseline : m.baseline;
        Glyph merged = new Glyph(text, lo, hi - lo, baseline, m.size, m.ascent, m.descent, m.font, m.rgb, m.seq,
                m.spaceWidth, m.bold, m.italic);
        merged.hscale = m.hscale;
        span.clear();
        current.add(new RawGlyph(merged, main.direction(), main.invisible(), main.originX(), main.originY()));
        return true;
    }

    private void endPage(PDPage page) throws IOException {
        inPage = false;
        List<RawGlyph> glyphs = current;
        glyphs.removeIf(r -> r.glyph().text == HIDDEN);
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

    private void processTextPosition(TextPosition tp) {
        String unicode = clean(recovery.text(tp, tp.getUnicode()));
        if (unicode == null && outerActual != null) {
            unicode = HIDDEN;
        }
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
        if (joiners(unicode)) {
            width = 0.01f;
        }
        if (!onPage(tp, size, width)) {
            return;
        }
        float x = tp.getXDirAdj();
        float[] ink = combiningMarks(unicode) ? ink(tp) : null;
        if (ink != null) {
            x += ink[0];
            width = Math.max(ink[1] - ink[0], size * 0.05f);
        }
        Glyph g =
                new Glyph(
                        unicode,
                        x,
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

    private static boolean joiners(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= 0x200B && c <= 0x200D || c == 0x2060)) {
                return false;
            }
        }
        return true;
    }

    static boolean combiningMarks(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int type = Character.getType(cp);
            if (type != Character.NON_SPACING_MARK && type != Character.ENCLOSING_MARK) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return !s.isEmpty();
    }

    private float[] ink(TextPosition tp) {
        int[] codes = tp.getCharacterCodes();
        if (!(tp.getFont() instanceof PDVectorFont vector) || codes == null || codes.length != 1) {
            return null;
        }
        float along = Math.round(tp.getDir()) % 180 != 0 ? tp.getYScale() : tp.getXScale();
        if (!(along > 0)) {
            return null;
        }
        float[] em = inks.computeIfAbsent(tp.getFont().getCOSObject(), k -> new HashMap<>()).computeIfAbsent(codes[0], c -> {
            try {
                Rectangle2D box = vector.getNormalizedPath(c).getBounds2D();
                return box.isEmpty() ? new float[0] : new float[] {(float) box.getMinX(), (float) box.getMaxX()};
            } catch (IOException | RuntimeException e) {
                return new float[0];
            }
        });
        return em.length == 2 ? new float[] {em[0] / 1000f * along, em[1] / 1000f * along} : null;
    }

    private boolean onPage(TextPosition tp, float size, float width) {
        float x = tp.getXDirAdj();
        float y = tp.getYDirAdj();
        if (!(size > 0) || !Float.isFinite(x) || !Float.isFinite(y)) {
            return false;
        }
        float w = tp.getPageWidth();
        float h = tp.getPageHeight();
        if (Math.round(tp.getDir()) == 0 && rotation % 360 == 0) {
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
                String base = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFKC);
                boolean carrier = base.length() > 1 && (base.charAt(0) == ' ' || base.charAt(0) == '\u0640')
                        && combiningMarks(base.substring(1));
                sb.append(carrier ? base.substring(1) : base);
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
