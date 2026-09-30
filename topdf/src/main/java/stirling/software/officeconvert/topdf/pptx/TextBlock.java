package stirling.software.officeconvert.topdf.pptx;

import java.util.ArrayList;
import java.util.List;

import org.apache.poi.sl.usermodel.TabStop.TabStopType;
import org.apache.poi.sl.usermodel.TextParagraph.TextAlign;

import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontMetrics;

final class TextBlock {

    private static final float EPSILON = 0.01f;

    static final float SINGLE = 1.2f;

    static final float MULTIPLE_ASCENT = 0.75f;

    final List<Line> lines = new ArrayList<>();

    final float width;

    final boolean wrap;

    float height;

    private TextBlock(float width, boolean wrap) {
        this.width = width;
        this.wrap = wrap;
    }

    static TextBlock layout(List<Para> paras, float width, boolean wrap) {
        TextBlock b = new TextBlock(Math.max(0, width), wrap);
        for (Para p : paras) {
            b.breakLines(p);
        }
        b.place();
        return b;
    }

    // A right-to-left bullet sits at the right, its margins measured from the right edge
    float bulletX(Line l) {
        Para p = l.para;
        float start = Math.max(0, p.marL() + p.indent());
        return p.rtl() && p.bullet() != null ? width - start - p.bullet().width() : start;
    }

    private void breakLines(Para para) {
        Chars ch = new Chars(para.pieces());
        float bulletWidth = para.bullet() == null ? 0 : para.bullet().width();
        int n = ch.length;
        int i = 0;
        boolean first = true;
        int added = 0;
        while (i < n || first) {
            float x0 = lineStart(para, first, bulletWidth);
            float right = right(para, first, bulletWidth);
            float limit = wrap ? Chars.floorDevice(right) : Float.MAX_VALUE;
            float x = x0;
            int lastBreak = -1;
            int end = -1;
            boolean forced = false;
            int j = i;
            while (j < n) {
                int cp = ch.codePoints[j];
                if (cp == '\n') {
                    end = j + 1;
                    forced = true;
                    break;
                }
                float a = ch.advances[j];
                if (cp == '\t') {
                    a = tab(para, x, ch, j);
                    ch.advances[j] = a;
                }
                if (wrap && j > i && !Chars.space(cp) && x + a > limit + EPSILON) {
                    end = lastBreak >= i ? lastBreak + 1 : j;
                    while (lastBreak < i && end > i + 1 && ch.joined[end]) {
                        end--;
                    }
                    break;
                }
                x += a;
                if (ch.breakAfter[j] && (cp != FontFace.SOFT_HYPHEN || x + ch.hyphen(j) <= limit + EPSILON)) {
                    lastBreak = j;
                }
                j++;
            }
            if (end < 0) {
                end = n;
            }
            while (!forced && end < n && ch.codePoints[end] == ' ') {
                end++;
            }
            if (!forced && end < n && ch.codePoints[end] == '\n') {
                end++;
                forced = true;
            }
            Line line = new Line(para, ch, i, end, first, forced);
            line.x = x0;
            line.right = right;
            line.width = ch.width(line.start, line.contentEnd);
            lines.add(line);
            added++;
            i = end;
            first = false;
            if (i >= n) {
                if (forced) {
                    Line tail = new Line(para, ch, n, n, false, false);
                    tail.x = lineStart(para, false, 0);
                    tail.right = right(para, false, 0);
                    lines.add(tail);
                    added++;
                }
                break;
            }
        }
        if (added > 0) {
            lines.get(lines.size() - 1).last = true;
        }
        for (int k = lines.size() - added; k < lines.size(); k++) {
            Line l = lines.get(k);
            if (l.hyphenated()) {
                l.width += ch.hyphen(l.contentEnd - 1);
            }
        }
    }

    private float right(Para para, boolean first, float bulletWidth) {
        if (para.rtl()) {
            return width - start(para, first, bulletWidth);
        }
        return width - para.marR();
    }

    private float lineStart(Para para, boolean first, float bulletWidth) {
        if (para.rtl()) {
            return Math.max(0, para.marR());
        }
        return start(para, first, bulletWidth);
    }

    // How far the text of a line starts from its start edge: the margin, or the first line's indent and bullet
    private static float start(Para para, boolean first, float bulletWidth) {
        if (!first) {
            return Math.max(0, para.marL());
        }
        float start = Math.max(0, para.marL() + para.indent());
        if (para.bullet() == null) {
            return start;
        }
        float afterBullet = start + bulletWidth;
        if (para.indent() < 0 && afterBullet <= para.marL() + EPSILON) {
            return para.marL();
        }
        return afterBullet;
    }

    private static float tab(Para para, float x, Chars ch, int at) {
        float hanging = !para.rtl() && para.indent() < 0 && para.marL() > x + EPSILON ? para.marL() : -1;
        for (Para.Tab t : para.tabs()) {
            if (t.position() > x + EPSILON) {
                if (hanging >= 0 && hanging < t.position()) {
                    return hanging - x;
                }
                if (t.type() == TabStopType.LEFT) {
                    return t.position() - x;
                }
                float w = 0;
                for (int i = at + 1; i < ch.length; i++) {
                    int cp = ch.codePoints[i];
                    if (cp == '\t' || cp == '\n' || t.type() == TabStopType.DECIMAL && (cp == '.' || cp == ',')) {
                        break;
                    }
                    w += ch.advances[i];
                }
                float lead = t.type() == TabStopType.CENTER ? w / 2 : w;
                return Math.max(0, t.position() - x - lead);
            }
        }
        if (hanging >= 0) {
            return hanging - x;
        }
        float d = para.defTab() > 0 ? para.defTab() : 72;
        float next = (float) (Math.floor((x + EPSILON) / d) + 1) * d;
        return next - x;
    }

    private void place() {
        float y = 0;
        Para previous = null;
        float previousSize = 0;
        for (int k = 0; k < lines.size(); k++) {
            Line l = lines.get(k);
            metrics(l);
            if (l.first) {
                if (previous != null) {
                    y += previous.after().amount(previousSize);
                }
                if (previous != null) {
                    y += l.para.before().amount(l.size);
                }
            }
            l.baseline = y + l.ascent;
            y = l.baseline + l.descent;
            align(l);
            previous = l.para;
            previousSize = l.size;
        }
        height = lines.isEmpty() ? y : lines.get(lines.size() - 1).baseline + lines.get(lines.size() - 1).tail;
    }

    private void align(Line l) {
        Para p = l.para;
        float free = l.right - l.x - l.width;
        TextAlign a = p.align();
        switch (a) {
            case CENTER -> l.shift = free / 2;
            case RIGHT -> l.shift = free;
            case JUSTIFY, JUSTIFY_LOW, DIST, THAI_DIST -> {
                boolean all = a == TextAlign.DIST || a == TextAlign.THAI_DIST;
                int spaces = l.spaces();
                if (wrap && free > 0 && spaces > 0 && (all || !l.last && !l.forced)) {
                    l.extraPerSpace = free / spaces;
                } else if (p.rtl()) {
                    l.shift = free;
                }
            }
            default -> l.shift = 0;
        }
    }

    private static void metrics(Line l) {
        Chars ch = l.chars;
        int to = l.empty() ? l.end : l.contentEnd;
        float size = 0;
        float ascent = 0;
        float descent = 0;
        float tail = 0;
        boolean any = false;
        int lastPiece = -1;
        for (int i = l.start; i < to; i++) {
            int pi = ch.piece[i];
            if (pi == lastPiece) {
                continue;
            }
            lastPiece = pi;
            Piece p = ch.pieces.get(pi);
            float s = p.rise() != 0 ? p.size() * 1.5f : p.size();
            float[] ad = parts(l.para, p, s);
            ascent = Math.max(ascent, ad[0]);
            descent = Math.max(descent, ad[1]);
            tail = Math.max(tail, fontDescent(p, s));
            size = Math.max(size, s);
            any = true;
        }
        if (!any) {
            Piece p = l.para.empty();
            if (p == null && !l.para.pieces().isEmpty()) {
                p = l.para.pieces().get(l.para.pieces().size() - 1);
            }
            float s = p == null ? TextStyles.DEFAULT_SIZE : p.size();
            float[] ad = p == null ? new float[] {s * 0.96f, s * 0.24f} : parts(l.para, p, s);
            ascent = ad[0];
            descent = ad[1];
            tail = p == null ? descent : fontDescent(p, s);
            size = s;
        }
        l.ascent = ascent;
        l.descent = descent;
        l.tail = l.para.line().exact() ? descent : tail;
        l.size = size;
    }

    private static float share(Piece p) {
        if (p.win() != null) {
            float wa = p.win()[0];
            float wd = p.win()[1];
            return wa + wd > 0 ? wa / (wa + wd) : 0.8f;
        }
        FontMetrics m = p.style().face().metrics();
        boolean typo = m.useTypoMetrics() && m.typoAscender() > 0;
        float wa = Math.max(0, typo ? m.typoAscender() : m.winAscent());
        float wd = Math.max(0, typo ? -m.typoDescender() : m.winDescent());
        return wa + wd > 0 ? wa / (wa + wd) : 0.8f;
    }

    static float fontDescent(Piece p, float size) {
        return SINGLE * size * (1 - share(p));
    }

    static float[] parts(Para para, Piece piece, float size) {
        Para.Spacing sp = para.line();
        if (sp.exact()) {
            float h = sp.points();
            return new float[] {h * MULTIPLE_ASCENT, h * (1 - MULTIPLE_ASCENT)};
        }
        float h = SINGLE * size;
        float share = share(piece);
        if (Math.abs(sp.percent() - 1) < 1e-4) {
            return new float[] {h * share, h * (1 - share)};
        }
        float single = h * (1 - share);
        h *= Math.max(0, sp.percent());
        float ascent = sp.percent() < 1 ? Math.max(h * MULTIPLE_ASCENT, h - single) : h * MULTIPLE_ASCENT;
        return new float[] {ascent, h - ascent};
    }
}
