package stirling.software.officeconvert.layout;

import static stirling.software.officeconvert.layout.LineTraits.EDGE;
import static stirling.software.officeconvert.layout.LineTraits.allBold;
import static stirling.software.officeconvert.layout.LineTraits.continuesNumbering;
import static stirling.software.officeconvert.layout.LineTraits.endsWithHyphen;
import static stirling.software.officeconvert.layout.LineTraits.isFillLine;
import static stirling.software.officeconvert.layout.LineTraits.noneBold;
import static stirling.software.officeconvert.layout.LineTraits.rtl;
import static stirling.software.officeconvert.layout.LineTraits.spaceWidth;
import static stirling.software.officeconvert.layout.LineTraits.startsListItem;

import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.extract.Glyph;

public final class ParagraphBuilder {

    public record Obstacle(Box box, float gapLeft, float gapRight) {}

    private final DocStats stats;
    private final ParagraphMeasure measure;

    public ParagraphBuilder(DocStats stats) {
        this.stats = stats;
        this.measure = new ParagraphMeasure(stats);
    }

    public List<ParaDraft> build(List<Line> lines, float colLeft, float colRight) {
        return build(lines, colLeft, colRight, List.of());
    }

    public List<ParaDraft> build(List<Line> lines, float colLeft, float colRight, List<Obstacle> obstacles) {
        return build(lines, colLeft, colRight, obstacles, 0);
    }

    public List<ParaDraft> build(List<Line> lines, float colLeft, float colRight, List<Obstacle> obstacles, float pitch) {
        List<ParaDraft> out = new ArrayList<>();
        if (lines.isEmpty()) {
            return out;
        }
        LineRoom room = new LineRoom(lines, colLeft, colRight, obstacles);
        ParaDraft para = null;
        for (Line line : lines) {
            line.narrowed = room.narrowed(line);
            if (para == null || breaksBefore(room, para, line, pitch)) {
                para = new ParaDraft(colLeft, colRight);
                out.add(para);
            } else if (isHardBreak(room, para.last(), line)) {
                para.hardBreaks.set(para.lines.size());
            }
            para.lines.add(line);
        }
        for (ParaDraft p : out) {
            measure.measure(room, p);
        }
        ParagraphMeasure.alignRtlStarts(room, out);
        return out;
    }

    private boolean breaksBefore(LineRoom room, ParaDraft para, Line cur, float ownPitch) {
        Line prev = para.last();
        float size = Math.max(prev.size, cur.size);

        float pitch = cur.baseline - prev.baseline;
        float expected = para.lines.size() >= 2 ? para.medianPitch() : ownPitch > 0 ? ownPitch : stats.pitchFor(prev.size);
        if (pitch > expected * 1.28f + 0.8f || pitch < prev.size * 0.5f) {
            return true;
        }
        if (changesLook(prev, cur, size) || startsListItem(cur) || isFillLine(cur) || isFillLine(prev)
                || continuesNumbering(para.lines.getFirst(), cur)) {
            return true;
        }

        boolean hyphenated = endsWithHyphen(prev);
        boolean centred = !room.narrowed(prev) && !room.narrowed(cur) && room.centred(prev) && room.centred(cur);
        boolean rtl = rtl(prev) && rtl(cur);
        float prevRel = startRel(room, prev, rtl);
        float curRel = startRel(room, cur, rtl);
        float bodyRel = para.lines.size() >= 2 ? startRel(room, para.lines.get(1), rtl) : prevRel;

        if (para.lines.size() >= 2) {
            if (curRel > bodyRel + 0.6f * size && !centred) {
                return true;
            }
            if (curRel < bodyRel - EDGE && !centred) {
                return true;
            }
        } else if (curRel > prevRel + EDGE && !centred && !prev.hasTabs() && !startsListItem(prev)) {
            if (rtl) {
                if (prev.x - spaceWidth(prev) - cur.words.getLast().width() > room.left(prev) + EDGE) {
                    return true;
                }
            } else {
                float wrap = Math.max(room.wrap(prev), Math.min(prev.right, cur.right));
                if (prev.right + spaceWidth(prev) + cur.words.getFirst().width() < wrap - EDGE && !hyphenated) {
                    return true;
                }
            }
        }
        if (centred || hyphenated) {
            return false;
        }
        if (rtl(prev) && rtl(cur)) {
            return rtlBreak(room, prev, cur);
        }
        float right = room.narrowed(prev) ? room.wrap(prev) : Math.max(room.wrap(prev), Math.max(prev.right, cur.right));
        Word firstWord = cur.words.getFirst();
        if (LineTraits.ideographic(cur)) {
            return ParagraphMeasure.textRight(prev) + leadingUnit(firstWord) < right - EDGE;
        }
        float unit = LineTraits.unspaced(cur) ? BreakUnits.first(firstWord) : firstWord.width();
        if (prev.right + spaceWidth(prev) + unit < right - EDGE) {
            return true;
        }
        return prev.hasTabs() != cur.hasTabs() && prev.hasTabs();
    }

    private static boolean changesLook(Line prev, Line cur, float size) {
        if (Math.abs(cur.size - prev.size) > 0.12f * size) {
            return true;
        }
        boolean prevAllBold = allBold(prev);
        boolean curAllBold = allBold(cur);
        if (prevAllBold != curAllBold && (prevAllBold ? noneBold(cur) : noneBold(prev))) {
            return true;
        }
        return prev.font.mono() != cur.font.mono();
    }

    private static float leadingUnit(Word w) {
        int n = w.glyphs.size() > 1 && ParagraphMeasure.HANGING.indexOf(w.glyphs.get(1).text.charAt(0)) >= 0 ? 2 : 1;
        return w.glyphs.get(n - 1).right() - w.first().x;
    }

    private static float startRel(LineRoom room, Line l, boolean rtl) {
        return rtl ? room.right(l) - l.right : room.rel(l);
    }

    private static boolean rtlBreak(LineRoom room, Line prev, Line cur) {
        if (startsListItem(cur)) {
            return true;
        }
        return prev.x - spaceWidth(prev) - cur.words.getLast().width() > room.left(prev) + EDGE;
    }

    private static boolean isHardBreak(LineRoom room, Line prev, Line cur) {
        if (room.narrowed(prev) || !room.centred(prev) || !room.centred(cur)) {
            return false;
        }
        float width = room.colRight - room.colLeft;
        return prev.width() + spaceWidth(prev) + cur.words.getFirst().width() < width - 2 * EDGE;
    }
}
