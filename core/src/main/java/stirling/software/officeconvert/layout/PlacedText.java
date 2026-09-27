package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

final class PlacedText {

    private static final float BORDER = 1.5f;

    private final ParagraphBuilder paragraphs;

    PlacedText(ParagraphBuilder paragraphs) {
        this.paragraphs = paragraphs;
    }

    List<PageLayout.TextBoxItem> rows(List<Line> lines, float pageWidth) {
        List<PageLayout.TextBoxItem> out = new ArrayList<>();
        for (Line row : LineGroups.joinRows(new ArrayList<>(lines))) {
            out.add(box(List.of(row), pageWidth));
        }
        return out;
    }

    PageLayout.TextBoxItem box(List<Line> lines, float pageWidth) {
        float x = Float.MAX_VALUE;
        float top = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        for (Line l : lines) {
            x = Math.min(x, l.x);
            top = Math.min(top, l.top);
            right = Math.max(right, l.right);
            bottom = Math.max(bottom, l.bottom);
        }
        float boxRight = Math.min(pageWidth, right + Math.max(24f, 0.3f * (right - x)));
        Box box = new Box(x - 1, top - 1, boxRight, bottom + 2);
        List<ParaDraft> paras = paragraphs.build(lines, x, boxRight);
        return new PageLayout.TextBoxItem(box, -1, x, boxRight, paras, 0f, -1, 0f, false, true);
    }

    PageLayout.TextBoxItem framed(List<Line> lines, Box frame) {
        float top = Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        List<ParaDraft> paras = new ArrayList<>();
        for (Line l : lines) {
            top = Math.min(top, l.top);
            bottom = Math.max(bottom, l.bottom);
            paras.addAll(paragraphs.build(List.of(l), frame.x(), frame.right()));
        }
        Box box = new Box(frame.x() + BORDER, Math.max(frame.top() + BORDER, top - 1), frame.right() - BORDER,
                Math.min(frame.bottom() - BORDER, bottom + 2));
        return new PageLayout.TextBoxItem(box, -1, frame.x(), frame.right(), paras, 0f, -1, 0f, false, true);
    }

    PageLayout.TextBoxItem turned(RotatedText.Block b, float pageWidth, float pageHeight) {
        PageLayout.TextBoxItem up = box(b.lines(), RotatedText.uprightWidth(b.direction(), pageWidth, pageHeight));
        return up.turned(b.direction(), RotatedText.onPage(up.box(), b.direction(), pageWidth, pageHeight));
    }

    static List<Line> belowFlow(List<Line> segments, List<Line> furniture, float flowBottom) {
        Set<Line> skip = Collections.newSetFromMap(new IdentityHashMap<>());
        skip.addAll(furniture);
        Set<Line> taken = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Line> out = new ArrayList<>();
        for (Line s : segments) {
            if (s.bottom > flowBottom + 1 && !skip.contains(s)) {
                out.add(s);
                taken.add(s);
            }
        }
        int climbed = 0;
        for (boolean grew = !out.isEmpty(); grew; ) {
            grew = false;
            for (Line s : segments) {
                if (!taken.contains(s) && !skip.contains(s) && out.stream().anyMatch(o -> stacksOn(s, o))) {
                    if (++climbed > MAX_BLOCK_ABOVE) {
                        return List.of();
                    }
                    out.add(s);
                    taken.add(s);
                    grew = true;
                }
            }
        }
        return out;
    }

    private static final int MAX_BLOCK_ABOVE = 6;

    private static boolean stacksOn(Line upper, Line lower) {
        float gap = lower.top - upper.bottom;
        boolean overlaps = upper.x < lower.right && lower.x < upper.right;
        return gap > -1 && gap < 0.6f * lower.size && overlaps && Math.abs(upper.size - lower.size) < 0.15f * lower.size;
    }
}
