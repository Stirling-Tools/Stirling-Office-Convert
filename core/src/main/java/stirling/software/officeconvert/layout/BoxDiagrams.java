package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.extract.PageGraphics.Fill;

final class BoxDiagrams {

    private static final int MIN_BOXES = 3;
    private static final float HELD = 0.8f;
    private static final float FILLED = 0.08f;

    record Label(List<Line> lines, Box frame) {}

    private BoxDiagrams() {}

    static List<Label> text(Box figure, List<Fill> fills, List<Line> segments) {
        List<Box> boxes = new ArrayList<>();
        for (Fill f : fills) {
            Box b = Regions.of(f);
            if (figure.grow(1).contains(b.centreX(), b.centreY()) && b.width() >= 20 && b.height() >= 10
                    && b.width() * b.height() < 0.5f * figure.width() * figure.height()) {
                boxes.add(b);
            }
        }
        Map<Box, List<Line>> held = new LinkedHashMap<>();
        List<Label> loose = new ArrayList<>();
        int lines = 0;
        for (Line l : segments) {
            if (!figure.contains(l.centre(), (l.top + l.bottom) / 2f)) {
                continue;
            }
            lines++;
            Box home = smallestHolding(boxes, l);
            if (home == null) {
                loose.add(new Label(List.of(l), null));
            } else {
                held.computeIfAbsent(home, k -> new ArrayList<>()).add(l);
            }
        }
        int inBoxes = held.values().stream().mapToInt(List::size).sum();
        if (held.size() < MIN_BOXES || inBoxes < HELD * lines
                || held.entrySet().stream().anyMatch(e -> !label(e.getKey(), e.getValue()))) {
            return List.of();
        }
        List<Label> out = new ArrayList<>();
        held.forEach((frame, text) -> out.add(new Label(text, frame)));
        out.addAll(loose);
        return out;
    }

    private static boolean label(Box box, List<Line> text) {
        List<Line> sorted = text.stream().sorted(Comparator.comparingDouble(l -> l.top)).toList();
        float covered = 0;
        for (int i = 0; i < sorted.size(); i++) {
            Line l = sorted.get(i);
            covered += (l.right - l.x) * (l.bottom - l.top);
            if (i > 0 && l.top - sorted.get(i - 1).bottom > 1.2f * l.size) {
                return false;
            }
        }
        return covered >= FILLED * box.width() * box.height();
    }

    private static Box smallestHolding(List<Box> boxes, Line l) {
        Box best = null;
        for (Box b : boxes) {
            boolean holds = l.x >= b.x() - 1 && l.right <= b.right() + 1 && l.top >= b.top() - 1 && l.bottom <= b.bottom() + 1;
            if (holds && (best == null || b.width() * b.height() < best.width() * best.height())) {
                best = b;
            }
        }
        return best;
    }
}
