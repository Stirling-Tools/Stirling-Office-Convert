package stirling.software.officeconvert.build;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.PageData;
import stirling.software.officeconvert.layout.Box;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.LineBuilder;

final class FigureWords {

    private static final int MAX_CHARS = 4000;

    private FigureWords() {}

    static String text(PageData page, Box box) {
        List<Glyph> inside = new ArrayList<>();
        for (Glyph g : page.glyphs()) {
            float cx = g.x + g.width / 2f;
            if (box.contains(cx, g.baseline - g.size / 3f)) {
                inside.add(g);
            }
        }
        if (inside.isEmpty()) {
            return "";
        }
        List<Line> lines = new ArrayList<>(LineBuilder.build(inside));
        lines.sort(Comparator.comparingDouble((Line l) -> l.top).thenComparingDouble(l -> l.x));
        StringBuilder sb = new StringBuilder();
        for (Line l : lines) {
            String t = l.text().strip();
            if (t.isEmpty()) {
                continue;
            }
            if (sb.length() + t.length() > MAX_CHARS) {
                break;
            }
            sb.append(sb.isEmpty() ? "" : "\n").append(t);
        }
        return sb.toString();
    }
}
