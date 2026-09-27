package stirling.software.officeconvert.build;

import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.ParaDraft;
import stirling.software.officeconvert.layout.Word;
import stirling.software.officeconvert.model.Paragraph.Align;

final class Continuation {

    private Continuation() {}

    static boolean looksUnfinished(ParaDraft d, float bodySize) {
        if (d.role != ParaDraft.Role.BODY && d.role != ParaDraft.Role.LIST || d.last().size > 1.25f * bodySize) {
            return false;
        }
        String t = d.last().words.getLast().text;
        char end = t.isEmpty() ? '.' : t.charAt(t.length() - 1);
        boolean nearlyFull = d.last().right >= d.colRight - Math.max(0, d.right) - 7 * d.last().size;
        return d.endsHyphenated || nearlyFull && ".!?:;\"”".indexOf(end) < 0;
    }

    static boolean continues(ParaDraft prev, ParaDraft next) {
        if (next.role != ParaDraft.Role.BODY || next.marker != null) {
            return false;
        }
        Line a = prev.last();
        Line b = next.first();
        if (Math.abs(a.size - b.size) > 0.3f || a.font.mono() != b.font.mono() || a.bold != b.bold) {
            return false;
        }
        float bodyLeft = prev.colLeft + prev.left;
        float nextBody = next.colLeft + next.left;
        boolean indentedStart = next.first > 0.5f * b.size;
        float room = prev.colRight - Math.max(0, prev.right);
        boolean wrapped = prev.endsHyphenated || a.right + 0.25f * a.size + b.words.getFirst().width() > room - 1;
        return wrapped && !indentedStart && Math.abs((next.colLeft + next.left + next.first) - bodyLeft) < 0.6f * b.size + 2
                && Math.abs(nextBody - bodyLeft) < b.size + 2;
    }

    static boolean exactFonts(ParaDraft d) {
        int exact = 0;
        int all = 0;
        for (Line l : d.lines) {
            for (Word w : l.words) {
                for (Glyph g : w.glyphs) {
                    all++;
                    exact += g.font.exact() ? 1 : 0;
                }
            }
        }
        return all > 0 && exact * 10 >= all * 9;
    }

    static ParaDraft join(ParaDraft prev, ParaDraft next, boolean keepPage) {
        int at = prev.lines.size();
        if (keepPage) {
            prev.pageBreaks.set(at);
        }
        next.hardBreaks.stream().forEach(i -> prev.hardBreaks.set(at + i));
        next.pageBreaks.stream().forEach(i -> prev.pageBreaks.set(at + i));
        prev.lines.addAll(next.lines);
        List<Float> diffs = new ArrayList<>();
        for (int i = 1; i < prev.lines.size(); i++) {
            float d = prev.lines.get(i).baseline - prev.lines.get(i - 1).baseline;
            if (d > 0 && d < prev.lines.get(i).size * 3f) {
                diffs.add(d);
            }
        }
        if (!diffs.isEmpty()) {
            diffs.sort(Float::compare);
            prev.pitch = diffs.get(diffs.size() / 2);
        }
        prev.endsHyphenated = next.endsHyphenated;
        if (next.lines.size() >= 2 || prev.align == Align.LEFT && next.align == Align.JUSTIFY) {
            prev.align = next.align == Align.JUSTIFY ? Align.JUSTIFY : prev.align;
        }
        return prev;
    }
}
