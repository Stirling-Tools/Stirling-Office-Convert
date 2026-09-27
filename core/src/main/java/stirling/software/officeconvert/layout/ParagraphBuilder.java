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
        List<ParaDraft> out = new ArrayList<>();
        if (lines.isEmpty()) {
            return out;
        }
        LineRoom room = new LineRoom(lines, colLeft, colRight, obstacles);
        ParaDraft para = null;
        for (Line line : lines) {
            if (para == null || breaksBefore(room, para, line)) {
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
        return out;
    }

    private boolean breaksBefore(LineRoom room, ParaDraft para, Line cur) {
        Line prev = para.last();
        float size = Math.max(prev.size, cur.size);

        float pitch = cur.baseline - prev.baseline;
        float expected = para.lines.size() >= 2 ? para.medianPitch() : stats.pitchFor(prev.size);
        if (pitch > expected * 1.28f + 0.8f || pitch < prev.size * 0.5f) {
            return true;
        }
        if (changesLook(prev, cur, size) || startsListItem(cur) || isFillLine(cur) || isFillLine(prev)
                || continuesNumbering(para.lines.getFirst(), cur)) {
            return true;
        }

        boolean hyphenated = endsWithHyphen(prev);
        boolean centred = !room.narrowed(prev) && !room.narrowed(cur) && room.centred(prev) && room.centred(cur);
        float prevRel = room.rel(prev);
        float curRel = room.rel(cur);
        float bodyRel = para.lines.size() >= 2 ? room.rel(para.lines.get(1)) : prevRel;

        if (para.lines.size() >= 2) {
            if (curRel > bodyRel + 0.6f * size && !centred) {
                return true;
            }
            if (curRel < bodyRel - EDGE && !centred) {
                return true;
            }
        } else if (curRel > prevRel + EDGE && !centred && !prev.hasTabs() && !startsListItem(prev)) {
            float wrap = Math.max(room.wrap(prev), Math.min(prev.right, cur.right));
            if (prev.right + spaceWidth(prev) + cur.words.getFirst().width() < wrap - EDGE && !hyphenated) {
                return true;
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
        if (prev.right + spaceWidth(prev) + firstWord.width() < right - EDGE) {
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

    private static boolean rtlBreak(LineRoom room, Line prev, Line cur) {
        if (cur.words.size() > 1) {
            Marker m = Marker.parse(cur.words.getLast().text);
            if (m != null && m.isBullet()) {
                return true;
            }
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
