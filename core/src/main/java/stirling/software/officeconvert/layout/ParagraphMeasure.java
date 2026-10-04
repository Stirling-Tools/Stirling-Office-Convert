package stirling.software.officeconvert.layout;

import static stirling.software.officeconvert.layout.LineTraits.EDGE;
import static stirling.software.officeconvert.layout.LineTraits.endsWithHyphen;
import static stirling.software.officeconvert.layout.LineTraits.spaceWidth;

import java.util.List;

import stirling.software.officeconvert.model.Paragraph.Align;

final class ParagraphMeasure {

    private final DocStats stats;

    ParagraphMeasure(DocStats stats) {
        this.stats = stats;
    }

    void measure(LineRoom room, ParaDraft p) {
        List<Line> lines = p.lines;
        float colLeft = p.colLeft;
        float colRight = p.colRight;
        Line first = lines.getFirst();
        p.pitch = lines.size() >= 2 ? p.medianPitch() : stats.pitchFor(first.size);
        p.endsHyphenated = endsWithHyphen(p.last());
        boolean wrapped = false;
        for (Line l : lines) {
            wrapped |= room.narrowed(l);
        }

        if (lines.size() == 1) {
            measureSingle(room, p, first);
            if (endsOnTheRight(p, first)) {
                p.rtl = true;
                p.align = Align.RIGHT;
                p.left = 0;
                p.first = 0;
                p.right = Math.max(0, p.colRight - first.right);
            }
            return;
        }
        int rtl = 0;
        for (Line l : lines) {
            rtl += LineTraits.rtl(l) ? 1 : 0;
        }
        if (rtl * 2 > lines.size() || startsRight(room, p)) {
            p.rtl = true;
            measureRtl(room, p, wrapped);
            return;
        }
        float bodyRel = Float.MAX_VALUE;
        for (int i = 1; i < lines.size(); i++) {
            bodyRel = Math.min(bodyRel, room.rel(lines.get(i)));
        }
        boolean flushLeft = true;
        for (int i = 1; i < lines.size(); i++) {
            flushLeft &= Math.abs(room.rel(lines.get(i)) - bodyRel) <= EDGE;
        }
        float maxGap = -Float.MAX_VALUE;
        float minGap = Float.MAX_VALUE;
        float maxRight = -Float.MAX_VALUE;
        for (int i = 0; i < lines.size() - 1; i++) {
            Line l = lines.get(i);
            float gap = room.wrap(l) - textRight(l);
            maxGap = Math.max(maxGap, gap);
            minGap = Math.min(minGap, gap);
            maxRight = Math.max(maxRight, textRight(l));
        }
        boolean flushRight = maxGap - minGap <= EDGE;
        if (!wrapped) {
            flushRight = true;
            for (int i = 0; i < lines.size() - 1; i++) {
                flushRight &= Math.abs(textRight(lines.get(i)) - maxRight) <= EDGE;
            }
        }
        boolean centred = !wrapped;
        float centreRef = first.centre();
        for (Line l : lines) {
            centred &= Math.abs(l.centre() - centreRef) <= 2.5f;
        }
        boolean leftVaries = !flushLeft || Math.abs(room.rel(first) - bodyRel) > EDGE;

        float leftSpread = 0;
        for (Line l : lines) {
            leftSpread = Math.max(leftSpread, Math.abs(l.x - first.x));
        }
        if (centred && leftVaries && leftSpread > 3 * EDGE) {
            p.align = Align.CENTER;
            centreIndents(p, centreRef, colLeft, colRight);
            return;
        }
        if (!wrapped && flushRight && !flushLeft && Math.abs(p.last().right - maxRight) <= EDGE) {
            p.align = Align.RIGHT;
            p.left = 0;
            p.first = 0;
            p.right = Math.max(0, colRight - maxRight);
            return;
        }
        boolean firstFull = room.wrap(first) - first.right <= EDGE + 1f;
        boolean justified = flushRight && (lines.size() >= 3 || firstFull && LineTraits.looksStretched(first));
        if (justified) {
            p.justifySlack = fitSlack(lines, maxRight);
        }
        p.align = justified ? Align.JUSTIFY : Align.LEFT;
        p.left = bodyRel;
        p.first = room.rel(first) - bodyRel;
        if (wrapped) {
            p.right = justified ? Math.max(0, minGap) : 0;
        } else if (justified) {
            p.right = Math.max(0, colRight - maxRight);
        } else {
            float widest = maxRight;
            for (Line l : lines) {
                widest = Math.max(widest, textRight(l));
            }
            p.right = Math.max(0, colRight - wrapEdge(lines, widest, room.measure, colRight));
        }
        if (!wrapped && maxRight > colRight) {
            p.right = -(maxRight - colRight);
        }
    }

    private boolean startsRight(LineRoom room, ParaDraft p) {
        int rtl = 0;
        int ltr = 0;
        int endsRtl = 0;
        boolean anyRtl = false;
        for (Line l : p.lines) {
            int d = LogicalOrder.direction(l);
            rtl += d > 0 ? 1 : 0;
            ltr += d < 0 ? 1 : 0;
            endsRtl += d == 0 && LogicalOrder.endsRtl(l) ? 1 : 0;
            anyRtl |= LogicalOrder.hasRtl(l);
        }
        if (!anyRtl || !stats.scripts.rightToLeft()) {
            return false;
        }
        Line last = p.last();
        boolean lastStartsRight = room.right(last) - last.right <= EDGE && last.x - room.left(last) > EDGE;
        return lastStartsRight && rtl + endsRtl >= ltr && (rtl + ltr < p.lines.size() || rtl > 0);
    }

    private boolean endsOnTheRight(ParaDraft p, Line l) {
        return stats.scripts.rightToLeft() && LogicalOrder.direction(l) == 0 && LogicalOrder.endsRtl(l)
                && Math.abs(p.colRight - l.right) <= 3f && !l.hasTabs();
    }

    private static void measureRtl(LineRoom room, ParaDraft p, boolean wrapped) {
        List<Line> lines = p.lines;
        Line first = lines.getFirst();
        Line last = p.last();
        float bodyRel = Float.MAX_VALUE;
        for (int i = 1; i < lines.size(); i++) {
            bodyRel = Math.min(bodyRel, room.right(lines.get(i)) - lines.get(i).right);
        }
        boolean flushStart = true;
        for (int i = 1; i < lines.size(); i++) {
            flushStart &= Math.abs(room.right(lines.get(i)) - lines.get(i).right - bodyRel) <= EDGE;
        }
        float maxGap = -Float.MAX_VALUE;
        float minGap = Float.MAX_VALUE;
        float minX = Float.MAX_VALUE;
        for (int i = 0; i < lines.size() - 1; i++) {
            Line l = lines.get(i);
            float gap = l.x - room.left(l);
            maxGap = Math.max(maxGap, gap);
            minGap = Math.min(minGap, gap);
            minX = Math.min(minX, l.x);
        }
        boolean flushEnd = maxGap - minGap <= EDGE;
        if (!wrapped) {
            flushEnd = true;
            for (int i = 0; i < lines.size() - 1; i++) {
                flushEnd &= Math.abs(lines.get(i).x - minX) <= EDGE;
            }
        }
        boolean centred = !wrapped;
        float centreRef = first.centre();
        float spread = 0;
        for (Line l : lines) {
            centred &= Math.abs(l.centre() - centreRef) <= 2.5f;
            spread = Math.max(spread, Math.abs(l.right - first.right));
        }
        boolean startVaries = !flushStart || Math.abs(room.right(first) - first.right - bodyRel) > EDGE;
        if (centred && startVaries && spread > 3 * EDGE) {
            p.align = Align.CENTER;
            centreIndents(p, centreRef, p.colLeft, p.colRight);
            return;
        }
        if (!wrapped && flushEnd && !flushStart && Math.abs(last.x - minX) <= EDGE) {
            p.align = Align.LEFT;
            p.first = 0;
            p.right = 0;
            p.left = Math.max(0, minX - p.colLeft);
            return;
        }
        boolean firstFull = first.x - room.left(first) <= EDGE + 1f;
        boolean justified = flushEnd && (lines.size() >= 3 || firstFull && LineTraits.looksStretched(first));
        p.align = justified ? Align.JUSTIFY : Align.RIGHT;
        p.right = Math.max(0, bodyRel);
        p.first = room.right(first) - first.right - p.right;
        if (wrapped) {
            p.left = justified ? Math.max(0, minGap) : 0;
        } else if (justified) {
            p.left = Math.max(0, minX - p.colLeft);
        } else {
            p.left = Math.max(0, wrapEdgeRtl(lines, minX, p.colLeft) - p.colLeft);
        }
    }

    private static float wrapEdgeRtl(List<Line> lines, float minX, float colLeft) {
        float lower = -Float.MAX_VALUE;
        for (int i = 0; i + 1 < lines.size(); i++) {
            Line l = lines.get(i);
            Word next = lines.get(i + 1).words.getLast();
            lower = Math.max(lower, l.x - spaceWidth(l) - next.width());
        }
        float edge = lower == -Float.MAX_VALUE ? colLeft : (lower + minX) / 2f;
        return Math.max(colLeft, Math.min(edge, minX - 0.5f));
    }

    static void alignRtlStarts(LineRoom room, List<ParaDraft> paras) {
        for (int i = 0; i < paras.size(); i++) {
            ParaDraft p = paras.get(i);
            Line l = p.first();
            float gap = room.right(l) - l.right;
            if (p.lines.size() == 1 && p.align == Align.LEFT && gap > EDGE && LineTraits.rtl(l) && !LineTraits.startsListItem(l)
                    && (sharesStart(paras, i - 1, l) || sharesStart(paras, i + 1, l))) {
                p.align = Align.RIGHT;
                p.right = gap;
                p.left = 0;
                p.first = 0;
                p.rtl = true;
            }
        }
    }

    private static boolean sharesStart(List<ParaDraft> paras, int k, Line l) {
        if (k < 0 || k >= paras.size()) {
            return false;
        }
        for (Line o : paras.get(k).lines) {
            float start = LineTraits.startsListItem(o) ? o.words.get(Marker.nextIndex(o)).right : o.right;
            if (LineTraits.rtl(o) && Math.abs(start - l.right) <= EDGE) {
                return true;
            }
        }
        return false;
    }

    private static void measureSingle(LineRoom room, ParaDraft p, Line l) {
        float colLeft = p.colLeft;
        float colRight = p.colRight;
        float colCentre = (colLeft + colRight) / 2f;
        float width = colRight - colLeft;
        float leftGap = room.rel(l);
        float rightGap = colRight - l.right;
        if (!room.narrowed(l) && !l.hasTabs()) {
            if (Math.abs(l.centre() - colCentre) <= Math.max(2.5f, width * 0.012f) && leftGap > 12f) {
                p.align = Align.CENTER;
                centreIndents(p, l.centre(), colLeft, colRight);
                return;
            }
            boolean rtl = LineTraits.rtl(l);
            if (Math.abs(rightGap) <= 3f && (leftGap > width * 0.3f || LineTraits.rtl(l))) {
                p.align = Align.RIGHT;
                p.right = Math.max(0, rightGap);
                return;
            }
        }
        if (rightGap < 3f) {
            p.right = Math.min(0, rightGap) - 3f;
        }
        p.align = Align.LEFT;
        p.left = leftGap;
        p.first = 0;
    }

    private static void centreIndents(ParaDraft p, float centre, float colLeft, float colRight) {
        float colCentre = (colLeft + colRight) / 2f;
        float shift = centre - colCentre;
        p.left = 0;
        p.first = 0;
        p.right = 0;
        if (shift > 1f) {
            p.left = 2 * shift;
        } else if (shift < -1f) {
            p.right = -2 * shift;
        }
    }

    private static float wrapEdge(List<Line> lines, float maxRight, float measure, float colRight) {
        float lower = maxRight;
        float upper = Float.MAX_VALUE;
        for (int i = 0; i + 1 < lines.size(); i++) {
            Line l = lines.get(i);
            Line after = lines.get(i + 1);
            Word next = after.words.getFirst();
            float unit = LineTraits.ideographic(after) ? next.first().width : spaceWidth(l) + next.width();
            upper = Math.min(upper, textRight(l) + unit);
        }
        float edge = upper == Float.MAX_VALUE ? Math.max(measure, maxRight) : (lower + upper) / 2f;
        if (edge < lower) {
            edge = lower + 1f;
        }
        return Math.min(Math.max(edge, lower + 0.5f), Math.max(colRight, lower + 0.5f));
    }

    static final String HANGING = "\u3001\u3002\uFF0C\uFF0E\uFF1A\uFF1B\uFF01\uFF1F\u300D\u300F\uFF09\u3015\u3011\u300B\u3009\uFF5D\uFF3D";

    static float textRight(Line l) {
        Word w = l.words.getLast();
        if (w.glyphs.size() < 2 || HANGING.indexOf(w.last().text.charAt(0)) < 0 || !LineTraits.unspaced(l)) {
            return l.right;
        }
        return w.glyphs.get(w.glyphs.size() - 2).right();
    }

    private static float fitSlack(List<Line> lines, float edge) {
        float low = -Float.MAX_VALUE;
        float high = Float.MAX_VALUE;
        for (int i = 0; i + 1 < lines.size(); i++) {
            Line l = lines.get(i);
            float space = spaceWidth(l);
            float natural = 0;
            for (Word w : l.words) {
                natural += w.width();
            }
            natural += (l.words.size() - 1) * space;
            for (int wi = 1; wi < l.words.size(); wi++) {
                natural += l.gaps[wi] == Line.SPACE && l.sentenceSpace(wi) ? space : 0;
            }
            float avail = edge - l.x;
            low = Math.max(low, natural - avail);
            if (!endsWithHyphen(l)) {
                high = Math.min(high, natural + space + lines.get(i + 1).words.getFirst().width() - avail);
            }
        }
        if (low == -Float.MAX_VALUE) {
            return Float.NaN;
        }
        if (high == Float.MAX_VALUE) {
            high = low + 3f;
        }
        if (low > 1.5f) {
            return Math.min(low + 0.2f, 3f);
        }
        return high <= low ? Math.max(0, low) : Math.clamp((low + high) / 2f, 0f, 1.5f);
    }
}
