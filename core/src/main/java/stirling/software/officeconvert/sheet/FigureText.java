package stirling.software.officeconvert.sheet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import stirling.software.officeconvert.layout.Box;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.Word;

final class FigureText {

    private static final float SLACK = 1f;

    private FigureText() {}

    static List<Line> lines(Box box, List<Line> segments, Set<Line> skip) {
        List<Line> out = new ArrayList<>();
        for (Line seg : segments) {
            if (skip.contains(seg)) {
                continue;
            }
            float cy = (seg.top + seg.bottom) / 2f;
            if (cy < box.top() - SLACK || cy > box.bottom() + SLACK) {
                continue;
            }
            List<Word> words = new ArrayList<>();
            List<Byte> gaps = new ArrayList<>();
            for (int i = 0; i < seg.words.size(); i++) {
                Word w = seg.words.get(i);
                float cx = (w.x + w.right) / 2f;
                if (cx >= box.x() - SLACK && cx <= box.right() + SLACK) {
                    gaps.add(words.isEmpty() ? Line.SPACE : seg.gaps[i]);
                    words.add(w);
                }
            }
            if (!words.isEmpty()) {
                byte[] g = new byte[gaps.size()];
                for (int i = 0; i < g.length; i++) {
                    g[i] = gaps.get(i);
                }
                out.add(new Line(words, g));
            }
        }
        out.sort(Comparator.comparingDouble((Line l) -> l.baseline).thenComparingDouble(l -> l.x));
        return rows(out);
    }

    private static List<Line> rows(List<Line> lines) {
        List<Line> out = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            int j = i + 1;
            while (j < lines.size() && Math.abs(lines.get(j).baseline - lines.get(i).baseline) <= 2f) {
                j++;
            }
            List<Line> row = new ArrayList<>(lines.subList(i, j));
            row.sort(Comparator.comparingDouble(l -> l.x));
            List<Word> words = new ArrayList<>();
            List<Byte> gaps = new ArrayList<>();
            for (Line l : row) {
                for (int k = 0; k < l.words.size(); k++) {
                    gaps.add(words.isEmpty() ? Line.SPACE : k == 0 ? Line.TAB : l.gaps[k]);
                    words.add(l.words.get(k));
                }
            }
            byte[] g = new byte[gaps.size()];
            for (int k = 0; k < g.length; k++) {
                g[k] = gaps.get(k);
            }
            out.add(row.size() == 1 ? row.getFirst() : new Line(words, g));
            i = j;
        }
        return out;
    }
}
