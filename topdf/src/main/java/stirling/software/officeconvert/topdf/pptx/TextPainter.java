package stirling.software.officeconvert.topdf.pptx;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import stirling.software.officeconvert.topdf.font.BidiRuns;
import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.GlyphRun;
import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.TextStyle;

final class TextPainter {

    private TextPainter() {}

    static void draw(PdfCanvas canvas, TextBlock block, float left, float top, Deck deck) throws IOException {
        AffineTransform ctm = canvas.transform();
        Rectangle2D page = new Rectangle2D.Float(0, 0, canvas.width(), canvas.height());
        for (Line l : block.lines) {
            deck.job().checkpoint();
            float baseline = top + l.baseline;
            float x = left + l.column + l.x + l.shift;
            if (offPage(ctm, page, l, left + l.column, x, baseline)) {
                continue;
            }
            Para.Bullet bullet = l.para.bullet();
            if (l.first && bullet != null && !l.para.isEmpty()) {
                float bx = left + l.column + block.bulletX(l) + l.shift;
                Piece b = bullet.piece();
                if (bullet.picture() != null) {
                    float h = bullet.height();
                    canvas.image(bullet.picture(), bx, baseline - h, bullet.width(), h);
                } else {
                    canvas.text(b.text(), bx, baseline, b.style());
                }
            }
            drawLine(canvas, l, x, baseline, deck);
        }
    }

    // Lines that overflow a box far past the slide are clipped by the page anyway, so they are not written
    private static boolean offPage(AffineTransform ctm, Rectangle2D page, Line l, float start, float x,
            float baseline) {
        float h = Math.max(1, l.ascent + l.descent);
        float pad = 4 * h + 72;
        float x0 = Math.min(start, x) - pad;
        float x1 = Math.max(start, x) + Math.max(0, l.width) + pad;
        Rectangle2D box = new Rectangle2D.Float(x0, baseline - l.ascent - pad, x1 - x0, h + 2 * pad);
        return !ctm.createTransformedShape(box).intersects(page);
    }

    private static void drawLine(PdfCanvas canvas, Line l, float x, float baseline, Deck deck) throws IOException {
        if (l.contentEnd > l.start) {
            String text = l.chars.text(l.start, l.contentEnd, true);
            if (l.para.rtl() || BidiRuns.needed(text)) {
                drawBidi(canvas, l, text, x, baseline, deck);
                return;
            }
        }
        Chars ch = l.chars;
        int i = l.start;
        while (i < l.contentEnd) {
            int cp = ch.codePoints[i];
            if (cp == '\t' || cp == '\n') {
                x += ch.advances[i];
                i++;
                continue;
            }
            int pi = ch.piece[i];
            int j = i;
            int spaces = 0;
            while (j < l.contentEnd && ch.piece[j] == pi && ch.codePoints[j] != '\t' && ch.codePoints[j] != '\n') {
                if (ch.codePoints[j] == ' ') {
                    spaces++;
                }
                j++;
            }
            float w = ch.width(i, j) + spaces * l.extraPerSpace;
            segment(canvas, ch, i, j, x, baseline, w, l.extraPerSpace, deck);
            x += w;
            i = j;
        }
        if (l.hyphenated()) {
            Piece p = ch.pieces.get(ch.piece[l.contentEnd - 1]);
            float y = p.rise() != 0 ? baseline - p.rise() * p.style().size() * 1.5f : baseline;
            if (p.style().color().getAlpha() > 0) {
                canvas.text("-", x, y, p.style());
            }
        }
    }

    private static void drawBidi(PdfCanvas canvas, Line l, String text, float x, float baseline, Deck deck)
            throws IOException {
        Chars ch = l.chars;
        int[] index = new int[text.length() + 1];
        int k = 0;
        for (int i = l.start; i < l.contentEnd; i++) {
            int n = Character.charCount(ch.codePoints[i]);
            for (int j = 0; j < n; j++) {
                index[k++] = i;
            }
        }
        index[k] = l.contentEnd;
        for (BidiRuns.Run run : BidiRuns.visual(BidiRuns.logical(text, l.para.rtl()))) {
            int a = index[run.start()];
            int b = index[run.end()];
            List<int[]> parts = new ArrayList<>();
            int i = a;
            while (i < b) {
                int cp = ch.codePoints[i];
                if (cp == '\t' || cp == '\n') {
                    parts.add(new int[] {i, i + 1});
                    i++;
                    continue;
                }
                int j = i;
                while (j < b && ch.piece[j] == ch.piece[i] && ch.codePoints[j] != '\t' && ch.codePoints[j] != '\n') {
                    j++;
                }
                parts.add(new int[] {i, j});
                i = j;
            }
            if (run.rightToLeft()) {
                Collections.reverse(parts);
            }
            for (int[] part : parts) {
                int cp = ch.codePoints[part[0]];
                if (cp == '\t' || cp == '\n') {
                    x += ch.advances[part[0]];
                    continue;
                }
                Piece p = ch.pieces.get(ch.piece[part[0]]);
                String t = ch.text(part[0], part[1]);
                TextStyle style = p.style();
                if (run.rightToLeft() || FontFace.needsShaping(t)) {
                    GlyphRun g = style.face().shape(t, run.rightToLeft());
                    float w = g.width(style.size()) * (style.horizontalScale() / 100f)
                            + t.codePointCount(0, t.length()) * style.charSpacing();
                    float y = p.rise() != 0 ? baseline - p.rise() * style.size() * 1.5f : baseline;
                    if (style.color().getAlpha() > 0 && !t.isBlank()) {
                        canvas.drawGlyphs(g, x, y, style);
                    }
                    if (p.underline() && w > 0) {
                        canvas.underline(x, y, w, style);
                    }
                    x += w;
                } else {
                    float w = ch.width(part[0], part[1]);
                    segment(canvas, ch, part[0], part[1], x, baseline, w, 0, deck);
                    x += w;
                }
            }
        }
    }

    // Each glyph of an emulated face is stretched to the advance of the face it stands in for and centred in it;
    // glyphs that line up anyway share one text object
    private static void emulated(PdfCanvas canvas, Chars ch, int from, int to, float x, float y, TextStyle style,
            float wordSpacing) throws IOException {
        Placement at = place(ch, from, to, x, style, wordSpacing);
        float[] origin = at.origin();
        float[] scale = at.scale();
        TextStyle plain = style.kerning(false).charSpacing(0).wordSpacing(0);
        int start = from;
        float flow = Float.NaN;
        for (int m = from; m <= to; m++) {
            int k = m - from;
            boolean joins = m < to && k > 0 && scale[k] == scale[k - 1] && Math.abs(origin[k] - flow) < 0.05f;
            if (m > start && !joins) {
                canvas.text(ch.text(start, m), origin[start - from], y, plain.horizontalScale(scale[start - from]));
                start = m;
            }
            if (m < to) {
                flow = origin[k] + at.natural()[k];
            }
        }
    }

    record Placement(float[] origin, float[] natural, float[] scale) {}

    static Placement place(Chars ch, int from, int to, float x, TextStyle style, float wordSpacing) {
        int n = to - from;
        float[] origin = new float[n];
        float[] natural = new float[n];
        float[] scale = new float[n];
        FontFace face = style.face();
        float em = style.size() / face.unitsPerEm();
        float pen = x;
        for (int m = from; m < to; m++) {
            int cp = ch.codePoints[m];
            float step = ch.advances[m] + (cp == ' ' ? wordSpacing : 0);
            float box = step - style.charSpacing();
            float plain = face.advance(cp) * em;
            float glyphScale = style.horizontalScale();
            if (plain > 0) {
                float fit = 100 * box / plain;
                glyphScale = cp == ' ' ? Math.max(10, Math.min(400, fit)) : Math.max(50, Math.min(200, fit));
            }
            float nat = plain * glyphScale / 100f;
            float centred = pen + (box - nat) / 2;
            origin[m - from] = cp == ' ' || m == from && centred < pen ? pen : centred;
            natural[m - from] = nat;
            scale[m - from] = glyphScale;
            pen += step;
        }
        return new Placement(origin, natural, scale);
    }

    private static void glyphs(PdfCanvas canvas, Chars ch, int from, int to, float x, float y, Piece p,
            TextStyle style, float wordSpacing) throws IOException {
        if (p.metrics() != null) {
            emulated(canvas, ch, from, to, x, y, style.wordSpacing(0), wordSpacing);
        } else {
            canvas.text(ch.text(from, to), x, y, style);
        }
    }

    // The shadow of the glyph outlines, soft or hard as for shapes; never a second copy of the text
    private static void shadow(PdfCanvas canvas, Chars ch, int from, int to, float x, float y, Piece p,
            float wordSpacing, Deck deck) throws IOException {
        Path2D outline = outlines(ch, from, to, x, y, p, wordSpacing);
        Rectangle2D b = outline.getBounds2D();
        if (b.isEmpty()) {
            return;
        }
        Shadows.draw(deck, canvas, p.shadow(), outline, new AffineTransform(), b);
    }

    private static Path2D outlines(Chars ch, int from, int to, float x, float y, Piece p, float wordSpacing) {
        TextStyle style = p.style();
        FontFace face = style.face();
        float scale = style.size() / face.unitsPerEm();
        float skew = face.syntheticItalic() ? 0.2126f : 0;
        Placement at = p.metrics() != null ? place(ch, from, to, x, style, wordSpacing) : null;
        Path2D outline = new Path2D.Float();
        float pen = x;
        for (int i = from; i < to; i++) {
            int cp = ch.codePoints[i];
            int glyph = face.covers(cp) ? face.glyph(cp) : 0;
            Shape g = glyph > 0 ? face.glyphOutline(glyph) : null;
            if (g != null) {
                float h = scale * (at != null ? at.scale()[i - from] : style.horizontalScale()) / 100f;
                float gx = at != null ? at.origin()[i - from] : pen;
                AffineTransform t = new AffineTransform(h, 0, -skew * scale, scale, gx, y);
                outline.append(t.createTransformedShape(g), false);
            }
            pen += ch.advances[i] + (cp == ' ' ? wordSpacing : 0);
        }
        return outline;
    }

    private static void segment(PdfCanvas canvas, Chars ch, int from, int to, float x, float baseline, float width,
            float wordSpacing, Deck deck) throws IOException {
        Piece p = ch.pieces.get(ch.piece[from]);
        String text = ch.text(from, to);
        TextStyle style = wordSpacing != 0 ? p.style().wordSpacing(wordSpacing) : p.style();
        float size = style.size();
        float y = baseline;
        if (p.rise() != 0) {
            y -= p.rise() * size * 1.5f;
        }
        FontFace face = style.face();
        if (p.highlight() != null && p.highlight().getAlpha() > 0) {
            float asc = style.points(face.metrics().winAscent());
            float desc = style.points(face.metrics().winDescent());
            canvas.rect(x, y - asc, width, asc + desc, Fill.solid(p.highlight()), null);
        }
        if (!text.isBlank() && style.color().getAlpha() > 0) {
            if (p.shadow() != null) {
                shadow(canvas, ch, from, to, x, y, p, wordSpacing, deck);
            }
            glyphs(canvas, ch, from, to, x, y, p, style, wordSpacing);
        }
        if (!text.isBlank() && p.outline() != null) {
            Path2D outline = outlines(ch, from, to, x, y, p, wordSpacing);
            if (!outline.getBounds2D().isEmpty()) {
                canvas.draw(outline, null, p.outline());
            }
        }
        if (p.underline() && width > 0) {
            canvas.underline(x, y, width, style);
        }
        if (p.strike() && width > 0) {
            canvas.strikeout(x, y, width, style);
        }
        if (p.link() != null && width > 0) {
            canvas.link(x, y - size, width, size * 1.25f, p.link());
        }
    }
}
