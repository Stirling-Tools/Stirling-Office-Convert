package stirling.software.officeconvert.topdf.pdf;

import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
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

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.util.Matrix;

import stirling.software.officeconvert.model.FormField;
import stirling.software.officeconvert.topdf.font.ColorEmoji;
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

    private final AffineTransform ctm = new AffineTransform();

    private final CanvasLinks links;

    private final List<TextOrder.Piece> pending = new ArrayList<>();

    private boolean closed;

    private boolean hiddenText;

    private boolean offPageSkipped;

    private boolean skippedSince;

    private final String marked;

    PdfCanvas(PdfOutput output, PDPage page, int index) throws IOException {
        this.output = output;
        this.marked = null;
        this.page = page;
        this.form = null;
        this.index = index;
        this.width = page.getMediaBox().getWidth();
        this.height = page.getMediaBox().getHeight();
        this.links = new CanvasLinks(output, page, index, height, ctm);
        this.cs = new PDPageContentStream(output.document(), page, AppendMode.OVERWRITE, true);
        cs.saveGraphicsState();
        cs.transform(new Matrix(1, 0, 0, -1, 0, height));
    }

    // A canvas whose drawing becomes a form XObject: the same coordinates as a page, links and destinations left out
    PdfCanvas(PdfOutput output, float width, float height, float margin) throws IOException {
        this(output, width, height, margin, null);
    }

    PdfCanvas(PdfOutput output, float width, float height, float margin, String marked) throws IOException {
        this.output = output;
        this.marked = marked;
        this.page = null;
        this.form = new PDAppearanceStream(output.document());
        this.index = -1;
        this.width = width;
        this.height = height;
        this.links = new CanvasLinks(output, null, -1, height, ctm);
        form.setBBox(new PDRectangle(-margin, -margin, width + 2 * margin, height + 2 * margin));
        form.setResources(new PDResources());
        this.cs = new PDPageContentStream(output.document(), form,
                form.getContentStream().createOutputStream(COSName.FLATE_DECODE));
        if (marked != null) {
            cs.beginMarkedContent(COSName.getPDFName(marked));
        }
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

    public boolean skipOffPage(boolean skip) {
        boolean was = offPageSkipped;
        offPageSkipped = skip;
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
        ctm.setTransform(saved.pop());
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
        skippedSince = false;
        PdfFonts.Embedded embedded = output.fonts().font(style.face());
        FontFace face = embedded.face();
        StringBuilder ops = new StringBuilder(text.length() * 5 + 96);
        int kind = SKIPPED;
        FontFace cover = null;
        int start = 0;
        float at = 0;
        ColorEmoji colour = output.fonts().colorEmoji();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int k = kind(face, cp);
            int emoji = k == MISSING && colour != null ? colour.run(text, i) : i;
            if (emoji > i) {
                if (kind >= 0 && i > start) {
                    at += segment(ops, text.substring(start, i), kind, embedded, cover, x + at, baseline, style);
                }
                at += emoji(ops, text.substring(i, emoji), colour, x + at, baseline, style, face);
                kind = SKIPPED;
                cover = null;
                start = emoji;
                i = emoji;
                continue;
            }
            FontFace f = null;
            if (k == MISSING) {
                f = kind == BORROWED && common(cp) && cover.covers(cp) ? cover : output.fonts().fallback(cp, face);
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
        queue(ops, x, x + at, baseline, style.size(), text, false);
        return at;
    }

    private static boolean common(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.COMMON || script == Character.UnicodeScript.INHERITED;
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
        boolean placed = face.emulated() && !perGlyph;
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
        double skipped = 0;
        double spacing = style.charSpacing() == 0 ? 0
                : Numbers.written(style.charSpacing() / (th * scale)) * 1000 / Numbers.written(size);
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
            if (glyph > 0 && !perGlyph && !visible(x + drawn, x + drawn + advance, baseline, size)) {
                if (pending != 0 && !(placed && Math.abs(pending / unit) < 0.5f)) {
                    skipped -= Numbers.written(-pending / unit);
                    pending = 0;
                }
                float width = placed
                        ? output.fonts().advance(embedded, output.fonts().encoded(embedded, glyph,
                                face.encodedCodePoint(cp),
                                Math.round(face.advance(cp) * 100000f / face.unitsPerEm() / scale) / 100f))
                        : Math.round(face.glyphAdvance(glyph) * (1000f / face.unitsPerEm()));
                float own = placed ? width / 1000f * face.unitsPerEm() : face.glyphAdvance(glyph);
                skipped += width + spacing;
                pending += (face.advance(cp) - own * scale) * size / face.unitsPerEm() * th;
                previous = cp;
            } else if (glyph > 0) {
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
                boolean flush = pending != 0 && !(placed && Math.abs(pending / unit) < 0.5f);
                if (flush || skipped != 0) {
                    if (open) {
                        tj.append('>');
                        open = false;
                    }
                    float shift = flush ? -pending / unit : 0;
                    if (skipped == 0) {
                        Numbers.append(tj.append(' '), shift).append(' ');
                    } else {
                        Numbers.fine(tj.append(' '), shift - skipped).append(' ');
                    }
                    pending = flush ? 0 : pending;
                    skipped = 0;
                }
                if (!open) {
                    tj.append('<');
                    open = true;
                }
                int code = placed
                        ? output.fonts().encoded(embedded, glyph, face.encodedCodePoint(cp),
                                Math.round(face.advance(cp) * 100000f / face.unitsPerEm() / scale) / 100f)
                        : output.fonts().encoded(embedded, glyph, face.encodedCodePoint(cp));
                Numbers.hex(tj, code, 4);
                glyphs[used] = glyph;
                codes[used++] = face.encodedCodePoint(cp);
                any = true;
                previous = cp;
                float own = placed ? output.fonts().advance(embedded, code) / 1000f * face.unitsPerEm()
                        : face.glyphAdvance(glyph);
                pending += (face.advance(cp) - own * scale) * size / face.unitsPerEm() * th;
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

    private float emoji(StringBuilder ops, String text, ColorEmoji colour, float x, float baseline, TextStyle style,
            FontFace face) throws IOException {
        float size = style.size();
        float th = style.horizontalScale() / 100f;
        PdfFonts.Embedded tofu = output.fonts().missing(face);
        FontFace boxFace = tofu.face();
        float natural = boxFace.glyphAdvance(0) * size / boxFace.unitsPerEm();
        StringBuilder b = new StringBuilder("0 Tc\n");
        boolean carried = false;
        float pen = x;
        for (int i = 0; i < text.length(); ) {
            int end = ColorEmoji.clusterEnd(text, i);
            int[] glyphs = end <= i ? null : colour.glyphs(text.substring(i, end));
            if (glyphs == null) {
                end = i + Character.charCount(text.codePointAt(i));
            }
            float room = 0;
            for (int k = i; k < end; ) {
                int cp = text.codePointAt(k);
                k += Character.charCount(cp);
                if (!TextStyle.skipped(cp)) {
                    room += face.advance(cp) * size / face.unitsPerEm() * th + style.charSpacing();
                }
            }
            boolean seen = visible(pen, pen + room, baseline, size);
            if (glyphs != null && !hiddenText && seen) {
                float wide = colour.advance(glyphs) * size * th;
                float fit = wide > room && wide > 0 ? room / wide : 1;
                float drawn = pen + Math.max(0, room - wide * fit) / 2;
                for (int g : glyphs) {
                    ColorEmoji.Bitmap bitmap = colour.bitmap(g);
                    DecodedPicture pic = bitmap == null ? null : output.emoji(bitmap);
                    if (pic != null) {
                        float w = bitmap.width() * size * th * fit;
                        float h = bitmap.height() * size * fit;
                        float top = baseline - bitmap.top() * size * fit;
                        ops.append("q\n");
                        Numbers.append(ops, w).append(" 0 0 ");
                        Numbers.append(ops, -h).append(' ');
                        Numbers.append(ops, drawn + bitmap.left() * size * th * fit).append(' ');
                        Numbers.append(ops, top + h).append(" cm /");
                        ops.append(resources().add(pic.image()).getName()).append(" Do\nQ\n");
                    }
                    drawn += colour.advance(g) * size * th * fit;
                }
            }
            if (room > 0 && natural > 0 && seen) {
                Numbers.append(b, 100 * room / natural).append(" Tz ");
                matrix(b, 1, 0, -1, pen, baseline).setLength(b.length() - 1);
                b.append(" [<");
                Numbers.hex(b, output.fonts().missingCode(tofu, text.substring(i, end)), 4).append(">] TJ\n");
                carried = true;
            }
            pen += room;
            i = end;
        }
        if (!carried && offPageSkipped) {
            return pen - x;
        }
        boolean was = hideText(true);
        try {
            begin(ops, style, 0);
        } finally {
            hideText(was);
        }
        font(ops, tofu, size);
        ops.append(b).append("ET\nQ\n");
        return pen - x;
    }

    public float emoji(String text, float x, float baseline, TextStyle style, float each, boolean mono)
            throws IOException {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(style, "style");
        open();
        if (text.isEmpty()) {
            return 0;
        }
        ColorEmoji colour = output.fonts().colorEmoji();
        FontFace face = output.fonts().font(style.face()).face();
        float size = style.size();
        float th = style.horizontalScale() / 100f;
        PdfFonts.Embedded tofu = output.fonts().missing(face);
        FontFace boxFace = tofu.face();
        float natural = boxFace.glyphAdvance(0) * size / boxFace.unitsPerEm();
        StringBuilder images = new StringBuilder();
        StringBuilder run = new StringBuilder("0 Tc\n");
        PdfFonts.Embedded current = null;
        int mode = -1;
        float stroke = 0;
        boolean any = false;
        float pen = x;
        for (int i = 0; i < text.length(); ) {
            int end = ColorEmoji.clusterEnd(text, i);
            if (end <= i) {
                end = i + Character.charCount(text.codePointAt(i));
            }
            String cluster = text.substring(i, end);
            int base = cluster.codePointAt(0);
            float room = each * size * th + style.charSpacing() * cluster.codePointCount(0, cluster.length());
            boolean seen = onPage(pen, pen + room, baseline - 2 * size, baseline + size);
            int[] glyphs = colour == null || mono && !joined(cluster) ? null : colour.glyphs(cluster);
            if (mono && glyphs != null && glyphs.length > 1) {
                glyphs = null;
            }
            FontFace cover = glyphs != null || ColorEmoji.regional(base) ? null
                    : face.covers(base) ? face : output.fonts().fallback(base, face);
            if (cover == null && glyphs == null && colour != null) {
                glyphs = colour.glyphs(cluster);
            }
            float carried = pen;
            if (cover != null) {
                String first = new String(Character.toChars(base));
                float own = cover.advance(base) * size / cover.unitsPerEm() * th;
                float fit = own > room && own > 0 ? room / own : 1;
                float at = pen + Math.max(0, room - own * fit) / 2;
                PdfFonts.Embedded embedded = seen ? output.fonts().font(cover) : null;
                FontFace drawn = embedded == null ? null : embedded.face();
                int glyph = drawn != null && drawn.covers(base) ? drawn.glyph(base) : 0;
                if (glyph > 0 && direct(drawn)) {
                    float line = outline(drawn, size);
                    int want = hiddenText ? 3 : line > 0 ? 2 : 0;
                    if (want == 2 && line != stroke) {
                        rgb(run, style.color()).append(" RG ");
                        Numbers.append(run, line).append(" w 1 j\n");
                        stroke = line;
                    }
                    if (want != mode) {
                        run.append(want).append(" Tr\n");
                        mode = want;
                    }
                    if (embedded != current) {
                        font(run, embedded, size);
                        current = embedded;
                    }
                    int code = drawn.encodedCodePoint(base);
                    Numbers.append(run, th * fit * 100).append(" Tz ");
                    matrix(run, 1, drawn.syntheticItalic() ? ITALIC_SKEW : 0, -1, at, baseline)
                            .setLength(run.length() - 1);
                    run.append(" [<");
                    Numbers.hex(run, output.fonts().encoded(embedded, glyph, code), 4).append(">] TJ\n");
                    output.fonts().used(embedded, new int[] {glyph}, new int[] {code}, 1);
                    any = true;
                } else if (seen) {
                    queue(emojiOps(images, run, any, style), x, pen, baseline, size, text.substring(0, i), false);
                    images.setLength(0);
                    run.setLength(0);
                    run.append("0 Tc\n");
                    current = null;
                    mode = -1;
                    stroke = 0;
                    any = false;
                    text(first, at, baseline, style.face(cover).charSpacing(0).wordSpacing(0)
                            .horizontalScale(style.horizontalScale() * fit));
                }
                carried = at + own * fit;
                cluster = cluster.substring(first.length());
            } else if (glyphs != null) {
                if (!hiddenText && seen) {
                    float wide = colour.advance(glyphs) * size * th;
                    float fit = wide > room && wide > 0 ? room / wide : 1;
                    float drawn = pen + Math.max(0, room - wide * fit) / 2;
                    for (int g : glyphs) {
                        ColorEmoji.Bitmap bitmap = colour.bitmap(g);
                        DecodedPicture pic = bitmap == null ? null : output.emoji(bitmap);
                        if (pic != null) {
                            float w = bitmap.width() * size * th * fit;
                            float h = bitmap.height() * size * fit;
                            float top = baseline - bitmap.top() * size * fit;
                            images.append("q\n");
                            Numbers.append(images, w).append(" 0 0 ");
                            Numbers.append(images, -h).append(' ');
                            Numbers.append(images, drawn + bitmap.left() * size * th * fit).append(' ');
                            Numbers.append(images, top + h).append(" cm /");
                            images.append(resources().add(pic.image()).getName()).append(" Do\nQ\n");
                        }
                        drawn += colour.advance(g) * size * th * fit;
                    }
                }
            } else if (room > 0 && natural > 0) {
                if (!hiddenText && !face.covers(base) && output.fonts().fallback(base, face) == null) {
                    output.fonts().uncovered(base);
                }
                if (seen) {
                    int want = hiddenText ? 3 : 0;
                    if (want != mode) {
                        run.append(want).append(" Tr\n");
                        mode = want;
                    }
                    if (tofu != current) {
                        font(run, tofu, size);
                        current = tofu;
                    }
                    float tz = Math.min(100, 100 * room / natural);
                    Numbers.append(run, tz).append(" Tz ");
                    matrix(run, 1, 0, -1, pen + (room - natural * tz / 100) / 2, baseline)
                            .setLength(run.length() - 1);
                    run.append(" [<");
                    Numbers.hex(run, output.fonts().missingCode(tofu, cluster), 4).append(">] TJ\n");
                    any = true;
                }
                cluster = "";
            }
            float left = pen + room - carried;
            if (seen && !cluster.isEmpty() && left > 0 && natural > 0) {
                if (mode != 3) {
                    run.append("3 Tr\n");
                    mode = 3;
                }
                if (tofu != current) {
                    font(run, tofu, size);
                    current = tofu;
                }
                Numbers.append(run, 100 * left / natural).append(" Tz ");
                matrix(run, 1, 0, -1, carried, baseline).setLength(run.length() - 1);
                run.append(" [<");
                Numbers.hex(run, output.fonts().missingCode(tofu, cluster), 4).append(">] TJ\n");
                any = true;
            }
            pen += room;
            i = end;
        }
        queue(emojiOps(images, run, any, style), x, pen, baseline, size, text, false);
        return pen - x;
    }

    private StringBuilder emojiOps(StringBuilder images, StringBuilder run, boolean any, TextStyle style) {
        StringBuilder ops = new StringBuilder(images.length() + run.length() + 32);
        ops.append(images);
        if (any) {
            begin(ops, style, 0);
            ops.append(run).append("ET\nQ\n");
        }
        return ops;
    }

    private static boolean direct(FontFace f) {
        return !f.emulated() && !f.scaledPerGlyph() && f.glyphStretch() == 1 && !f.symbolStandIn();
    }

    public boolean visible(float x0, float x1, float baseline, float size) {
        if (!offPageSkipped || form != null) {
            return true;
        }
        float left = x0 - size;
        float right = x1 + size;
        float top = baseline - 2 * size;
        float bottom = baseline + size;
        boolean seen;
        if (ctm.getShearX() == 0 && ctm.getShearY() == 0) {
            double a = left * ctm.getScaleX() + ctm.getTranslateX();
            double b = right * ctm.getScaleX() + ctm.getTranslateX();
            double c = top * ctm.getScaleY() + ctm.getTranslateY();
            double d = bottom * ctm.getScaleY() + ctm.getTranslateY();
            seen = Math.max(a, b) >= 0 && Math.min(a, b) <= width && Math.max(c, d) >= 0 && Math.min(c, d) <= height;
        } else {
            seen = onPage(left, right, top, bottom);
        }
        skippedSince |= !seen;
        return seen;
    }

    private boolean onPage(float x0, float x1, float top, float bottom) {
        if (form != null) {
            return true;
        }
        double[] p = {x0, top, x1, top, x0, bottom, x1, bottom};
        ctm.transform(p, 0, p, 0, 4);
        double minX = Math.min(Math.min(p[0], p[2]), Math.min(p[4], p[6]));
        double maxX = Math.max(Math.max(p[0], p[2]), Math.max(p[4], p[6]));
        double minY = Math.min(Math.min(p[1], p[3]), Math.min(p[5], p[7]));
        double maxY = Math.max(Math.max(p[1], p[3]), Math.max(p[5], p[7]));
        return maxX >= 0 && minX <= width && maxY >= 0 && minY <= height;
    }

    private static boolean joined(String cluster) {
        return cluster.indexOf(0x200D) >= 0 || cluster.codePoints().anyMatch(cp -> cp >= 0xE0020 && cp <= 0xE007F);
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
        boolean any = false;
        float drawn = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (TextStyle.skipped(cp) && !face.symbolCode(cp)) {
                continue;
            }
            float box = face.advance(cp) * size / face.unitsPerEm() * th;
            if (visible(x + drawn, x + drawn + box, baseline, size)) {
                float tz = natural > 0 ? Math.min(100, 100 * box / natural) : 100;
                Numbers.append(b, tz).append(" Tz ");
                matrix(b, 1, skew, -1, x + drawn, baseline).setLength(b.length() - 1);
                b.append(" [<");
                Numbers.hex(b, output.fonts().missingCode(tofu, cp), 4).append(">] TJ\n");
                any = true;
            }
            if (!hiddenText) {
                output.fonts().uncovered(cp);
            }
            drawn += box + style.charSpacing();
        }
        if (any || !offPageSkipped) {
            begin(ops, style, 0);
            font(ops, tofu, size);
            ops.append(b).append("ET\nQ\n");
        }
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
            if (glyph > 0 && visible(x + advanced, x + advanced + box, baseline, size)) {
                float natural = drawn.glyphAdvance(glyph) * size / drawn.unitsPerEm() * th;
                float fit = natural > box && natural > 0 ? box / natural : 1;
                Numbers.append(b, 100 * th * fit).append(" Tz ");
                matrix(b, 1, skew, -1, x + advanced + Math.max(0, box - natural) / 2, baseline)
                        .setLength(b.length() - 1);
                b.append(" [<");
                int code = drawn.symbolStandIn() ? drawn.encodedCodePoint(cp) : cp;
                Numbers.hex(b, output.fonts().encoded(embedded, glyph, code), 4).append(">] TJ\n");
                glyphs[n] = glyph;
                codes[n++] = code;
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
            float advance = face.advance(cp) * size / face.unitsPerEm() * th;
            if (fit != null && visible(x + drawn, x + drawn + advance, baseline, size)) {
                ids[n] = face.glyph(cp);
                codes[n] = face.encodedCodePoint(cp);
                matrix(b, fit[0] * th, skew * fit[1], -fit[1], x + drawn + fit[2] * size * th + skew * fit[3] * size,
                        baseline - fit[3] * size).setLength(b.length() - 1);
                b.append(" [<");
                Numbers.hex(b, output.fonts().encoded(embedded, ids[n], codes[n]), 4).append(">] TJ\n");
                n++;
            }
            drawn += advance + style.charSpacing();
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
        Numbers.append(b, Shadings.unit(c.getRed())).append(' ');
        Numbers.append(b, Shadings.unit(c.getGreen())).append(' ');
        return Numbers.append(b, Shadings.unit(c.getBlue()));
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
        skippedSince = false;
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
        boolean logical = upright() && (tz == 1 || !arabic(run.text()));
        boolean visual = run.rightToLeft() && !logical && upright();
        int[] groups = actualText ? new int[run.size()] : visual ? visualGroups(run) : run.groups();
        int[][] parts = visual ? output.fonts().usedVisually(embedded, run, groups) : null;
        int[] codes = visual ? null : output.fonts().used(embedded, run);
        int[][] reading = readingOrder(run, groups, logical);
        int[] order = reading[0];
        int[] marks = reading[1];
        int lead = visual ? leadingBlanks(run, order, marks) : 0;
        float width = run.advance() * scale;
        for (int from = 0, to = lead > 0 ? lead : run.size(); from < run.size(); from = to, to = run.size()) {
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
            float originX = 0;
            float originY = 0;
            double skipped = 0;
            for (int k = from; k < to; k++) {
                int i = order[k];
                int glyph = run.glyph(i);
                if (glyph > 0) {
                    float gx = run.x(i) * scale;
                    float gy = run.y(i) * rise;
                    if (gy != line) {
                        if (tj != null) {
                            ops.append(tj.append("] TJ\n"));
                            tj = null;
                        }
                        originX = x + gx + skew * gy;
                        originY = baseline - gy;
                        skipped = 0;
                        pen = gx;
                        line = gy;
                    }
                    float adjust = -(gx - pen) / (size * tz) * 1000;
                    boolean moved = Math.abs(adjust) >= 0.5f;
                    if (moved) {
                        pen = gx;
                    }
                    float units = 0;
                    if (offPageSkipped) {
                        for (int c = 0; c < (visual ? parts[i].length : 1); c++) {
                            units += output.fonts().advance(embedded, visual ? parts[i][c] : codes[i]);
                        }
                    }
                    if (!visible(x + gx, x + gx + units / 1000f * size * tz, baseline - gy, size)) {
                        skipped += units - (moved ? Numbers.written(adjust) : 0);
                    } else {
                        if (tj == null) {
                            matrix(ops, 1, skew, -1, originX, originY);
                            tj = new StringBuilder("[");
                        }
                        if (skipped != 0) {
                            Numbers.fine(tj.append(' '), (moved ? adjust : 0) - skipped).append(' ');
                            skipped = 0;
                        } else if (moved) {
                            Numbers.append(tj.append(' '), adjust).append(' ');
                        }
                        if (tj.charAt(tj.length() - 1) == '>') {
                            tj.setLength(tj.length() - 1);
                        } else {
                            tj.append('<');
                        }
                        if (visual && parts[i].length > 1) {
                            drawParts(tj, embedded, parts[i]);
                        } else {
                            Numbers.hex(tj, visual ? parts[i][0] : codes[i], 4).append('>');
                        }
                    }
                    if (visual && parts[i].length > 1) {
                        for (int code : parts[i]) {
                            pen += output.fonts().advance(embedded, code) / 1000f * size * tz;
                        }
                    } else {
                        pen += Math.round(face.glyphAdvance(glyph) * 1000f / face.unitsPerEm()) / 1000f * size * tz;
                    }
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
            if (to == lead) {
                float x0 = x + run.x(order[0]) * scale;
                float x1 = x + run.x(order[lead - 1]) * scale + face.glyphAdvance(run.glyph(order[lead - 1])) * scale;
                queue(ops, x0, x1, baseline, size, " ".repeat(lead), false);
            } else {
                queue(ops, x, x + width, baseline, size, run.text(), visual);
            }
        }
        return width;
    }

    // Glyphs in reading order, right-to-left runs from their right end as Word writes them unless horizontally
    // scaled Arabic (MuPDF measures a backward step in unscaled advances and splits such words; Hebrew does not
    // split, and drawn left to right MuPDF reads its words backwards); a group draws the glyph that carries its
    // text first and marks the others as saying nothing (SILENT opens, SPOKEN closes)
    private static int[][] readingOrder(GlyphRun run, int[] groups, boolean logical) {
        int n = run.size();
        List<int[]> segments = new ArrayList<>();
        for (int i = 0; i < n; ) {
            int end = groups[i] > 0 ? groups[i] : i + 1;
            segments.add(new int[] {i, end});
            i = end;
        }
        if (run.rightToLeft() && logical) {
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

    private static int leadingBlanks(GlyphRun run, int[] order, int[] marks) {
        int n = 0;
        while (n < order.length && marks[n] == 0 && run.glyph(order[n]) > 0) {
            String t = run.clusterText(order[n]);
            if (t == null || t.isEmpty() || !t.isBlank()) {
                break;
            }
            n++;
        }
        return n < order.length ? n : 0;
    }

    private static int[] visualGroups(GlyphRun run) {
        int[] groups = run.groups();
        int[] out = groups.clone();
        int n = run.size();
        for (int i = 0; i < n; ) {
            if (groups[i] > 0) {
                i = groups[i];
                continue;
            }
            int j = i;
            while (j < n && groups[j] == 0 && marks(run.clusterText(j))) {
                j++;
            }
            if (j > i && j < n && groups[j] == 0 && !marks(run.clusterText(j))) {
                out[i] = j + 1;
                i = j + 1;
            } else {
                i = Math.max(j, i + 1);
            }
        }
        return out;
    }

    private static boolean marks(String text) {
        return text != null && TextOrder.marks(text);
    }

    private void drawParts(StringBuilder tj, PdfFonts.Embedded embedded, int[] parts) {
        for (int c = 0; c < parts.length; c++) {
            float width = output.fonts().advance(embedded, parts[c]);
            float ahead = 0;
            for (int n = c + 1; width == 0 && n < parts.length && ahead == 0; n++) {
                ahead = output.fonts().advance(embedded, parts[n]);
            }
            if (ahead > 0) {
                Numbers.append(tj.append("> "), -ahead / 2f).append(" <");
            }
            Numbers.hex(tj, parts[c], 4);
            if (ahead > 0) {
                Numbers.append(tj.append("> "), ahead / 2f).append(" <");
            }
        }
        tj.append('>');
    }

    private static boolean arabic(String text) {
        return text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.ARABIC);
    }

    private static int firstCharacter(GlyphRun run, int[] segment) {
        int first = Integer.MAX_VALUE;
        for (int i = segment[0]; i < segment[1]; i++) {
            first = Math.min(first, run.cluster(i));
        }
        return first;
    }

    // Text waits for the next drawing so that the pieces of a line reach the page in reading order
    private void queue(StringBuilder ops, float x0, float x1, float baseline, float size, String text,
            boolean visual) {
        if (!ops.isEmpty() || skippedSince) {
            pending.add(new TextOrder.Piece(ops.toString(), Math.min(x0, x1), Math.max(x0, x1), baseline, size,
                    text, upright(), visual));
        }
        skippedSince = false;
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
        return links.link(x, y, w, h, url);
    }

    public void field(float x, float y, float w, float h, FormField field, float size, Color color,
            PDFormXObject look) {
        Objects.requireNonNull(field, "field");
        open();
        links.field(x, y, w, h, field, size, color, look);
    }

    public void linkTo(float x, float y, float w, float h, String destination) {
        Objects.requireNonNull(destination, "destination");
        open();
        links.linkTo(x, y, w, h, destination);
    }

    public void linkToPage(float x, float y, float w, float h, int pageIndex, float yTop) {
        open();
        links.linkToPage(x, y, w, h, pageIndex, yTop);
    }

    public void destination(String name, float x, float yTop) {
        Objects.requireNonNull(name, "name");
        open();
        links.destination(name, x, yTop);
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
            if (marked != null) {
                cs.endMarkedContent();
            }
        } finally {
            cs.close();
            output.closed(this);
        }
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
            shading = Shadings.shading(g);
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
            colour = Shadings.shading(Shadings.recolour(g, false));
            mask = Shadings.shading(Shadings.recolour(g, true));
        } catch (IOException | RuntimeException e) {
            colour = null;
            mask = null;
        }
        if (colour == null || mask == null || b.isEmpty()) {
            draw(shape, Fill.of(g), null);
            return;
        }
        PDExtendedGraphicsState state = Shadings.softMask(output.document(), b, mask);
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

    private void setFill(Color c) throws IOException {
        cs.setNonStrokingColor(new PDColor(Shadings.office(c), PDDeviceRGB.INSTANCE));
    }

    private void setStroke(Color c) throws IOException {
        cs.setStrokingColor(new PDColor(Shadings.office(c), PDDeviceRGB.INSTANCE));
    }
}
