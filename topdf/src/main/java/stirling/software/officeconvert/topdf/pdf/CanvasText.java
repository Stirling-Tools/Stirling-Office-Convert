package stirling.software.officeconvert.topdf.pdf;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;

import stirling.software.officeconvert.topdf.font.ColorEmoji;
import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.GlyphRun;
import stirling.software.officeconvert.topdf.font.PdfFonts;
import stirling.software.officeconvert.topdf.io.DecodedPicture;

final class CanvasText {

    private static final int SKIPPED = -1;

    private static final int GLYPHS = 0;

    private static final int MISSING = 1;

    private static final int FITTED = 2;

    private static final int BORROWED = 3;

    private final PdfOutput output;

    private final PDPage page;

    private final PDAppearanceStream form;

    private final PDPageContentStream cs;

    private final AffineTransform ctm;

    private final float width;

    private final float height;

    private final Map<COSDictionary, String> fontNames = new IdentityHashMap<>();

    private final List<TextOrder.Piece> pending = new ArrayList<>();

    private boolean hiddenText;

    private boolean offPageSkipped;

    private boolean skippedSince;

    CanvasText(PdfOutput output, PDPage page, PDAppearanceStream form, PDPageContentStream cs, AffineTransform ctm,
            float width, float height) {
        this.output = output;
        this.page = page;
        this.form = form;
        this.cs = cs;
        this.ctm = ctm;
        this.width = width;
        this.height = height;
    }

    private PDResources resources() {
        return page != null ? page.getResources() : form.getResources();
    }

    boolean hideText(boolean hide) {
        boolean was = hiddenText;
        hiddenText = hide;
        return was;
    }

    boolean skipOffPage(boolean skip) {
        boolean was = offPageSkipped;
        offPageSkipped = skip;
        return was;
    }

    float text(String text, float x, float baseline, TextStyle style) throws IOException {
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
        matrix(ops, 1, face.syntheticItalic() ? PdfCanvas.ITALIC_SKEW : 0, -1, x, baseline).append(tj).append("ET\nQ\n");
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

    float emoji(String text, float x, float baseline, TextStyle style, float each, boolean mono)
            throws IOException {
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
                    matrix(run, 1, drawn.syntheticItalic() ? PdfCanvas.ITALIC_SKEW : 0, -1, at, baseline)
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

    boolean visible(float x0, float x1, float baseline, float size) {
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
        float skew = face.syntheticItalic() ? PdfCanvas.ITALIC_SKEW : 0;
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
        float skew = face.syntheticItalic() ? PdfCanvas.ITALIC_SKEW : 0;
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
        float skew = face.syntheticItalic() ? PdfCanvas.ITALIC_SKEW : 0;
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
        return size * ((face.syntheticBold() ? PdfCanvas.BOLD_STROKE : 0) + face.embolden());
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

    float drawGlyphs(GlyphRun run, float x, float baseline, TextStyle style, boolean actualText)
            throws IOException {
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
        float skew = face.syntheticItalic() ? PdfCanvas.ITALIC_SKEW : 0;
        boolean logical = upright() && (tz == 1 || !GlyphOrder.arabic(run.text()));
        boolean visual = run.rightToLeft() && !logical && upright();
        int[] groups = actualText ? new int[run.size()] : visual ? GlyphOrder.visualGroups(run) : run.groups();
        int[][] parts = visual ? output.fonts().usedVisually(embedded, run, groups) : null;
        int[] codes = visual ? null : output.fonts().used(embedded, run);
        int[][] reading = GlyphOrder.readingOrder(run, groups, logical);
        int[] order = reading[0];
        int[] marks = reading[1];
        int lead = visual ? GlyphOrder.leadingBlanks(run, order, marks) : 0;
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
                    ops.append(marks[k] == GlyphOrder.SILENT ? "/Span <</ActualText <FEFF>>> BDC\n" : "EMC\n");
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

    void flush() throws IOException {
        if (pending.isEmpty()) {
            return;
        }
        List<TextOrder.Piece> pieces = new ArrayList<>(pending);
        pending.clear();
        for (TextOrder.Piece p : TextOrder.reading(pieces)) {
            raw(p.ops());
        }
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
}
