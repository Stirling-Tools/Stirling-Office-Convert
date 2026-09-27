package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class LineGroups {

    private LineGroups() {}

    static List<Line> joinRows(List<Line> segs) {
        segs.sort(Comparator.comparingDouble((Line l) -> l.baseline).thenComparingDouble(l -> l.x));
        List<Line> out = new ArrayList<>();
        int i = 0;
        while (i < segs.size()) {
            int j = i + 1;
            while (j < segs.size()
                    && Math.abs(segs.get(j).baseline - segs.get(i).baseline)
                            < Math.max(1f, segs.get(i).size * 0.2f)) {
                j++;
            }
            out.add(LineBuilder.join(segs.subList(i, j)));
            i = j;
        }
        return out;
    }

    static Line fromWords(List<Word> words) {
        List<Word> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingDouble(w -> w.x));
        return Leaders.classifyLeaders(new Line(sorted, new byte[sorted.size()]));
    }

    static List<Line> linesOf(List<Word> words) {
        List<Word> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingDouble((Word w) -> w.first().baseline).thenComparingDouble(w -> w.x));
        List<Line> out = new ArrayList<>();
        int i = 0;
        while (i < sorted.size()) {
            int j = i + 1;
            float base = sorted.get(i).first().baseline;
            float tol = Math.max(1f, sorted.get(i).size() * 0.3f);
            while (j < sorted.size() && Math.abs(sorted.get(j).first().baseline - base) <= tol) {
                j++;
            }
            out.add(fromWords(sorted.subList(i, j)));
            i = j;
        }
        return out;
    }
}
