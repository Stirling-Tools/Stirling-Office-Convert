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
            float gap = room.wrap(l) - l.right;
            maxGap = Math.max(maxGap, gap);
            minGap = Math.min(minGap, gap);
            maxRight = Math.max(maxRight, l.right);
        }
        boolean flushRight = maxGap - minGap <= EDGE;
        if (!wrapped) {
            flushRight = true;
            for (int i = 0; i < lines.size() - 1; i++) {
                flushRight &= Math.abs(lines.get(i).right - maxRight) <= EDGE;
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
                widest = Math.max(widest, l.right);
            }
            p.right = Math.max(0, colRight - wrapEdge(lines, widest, room.measure, colRight));
        }
        if (!wrapped && maxRight > colRight) {
            p.right = -(maxRight - colRight);
        }
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
            if (Math.abs(rightGap) <= 3f && leftGap > width * 0.3f) {
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
            Word next = lines.get(i + 1).words.getFirst();
            upper = Math.min(upper, l.right + spaceWidth(l) + next.width());
        }
        float edge = upper == Float.MAX_VALUE ? Math.max(measure, maxRight) : (lower + upper) / 2f;
        if (edge < lower) {
            edge = lower + 1f;
        }
        return Math.min(Math.max(edge, lower + 0.5f), Math.max(colRight, lower + 0.5f));
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
