package stirling.software.officeconvert.extract;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.extract.PageGraphics.Fill;

final class TouchingDots {

    private static final float CELL = 3f;

    private static final float TOUCH = 0.25f;

    private TouchingDots() {}

    static List<Fill> keep(List<Fill> dots) {
        if (dots.size() < 2) {
            return List.of();
        }
        Map<Long, List<Fill>> grid = new HashMap<>();
        for (Fill d : dots) {
            grid.computeIfAbsent(key(cell(d.x()), cell(d.top())), k -> new ArrayList<>()).add(d);
        }
        List<Fill> kept = new ArrayList<>();
        for (Fill d : dots) {
            if (touched(d, grid)) {
                kept.add(d);
            }
        }
        return kept;
    }

    private static boolean touched(Fill d, Map<Long, List<Fill>> grid) {
        int cx = cell(d.x());
        int cy = cell(d.top());
        for (int x = cx - 1; x <= cx + 1; x++) {
            for (int y = cy - 1; y <= cy + 1; y++) {
                for (Fill o : grid.getOrDefault(key(x, y), List.of())) {
                    if (o != d && Math.max(o.x() - d.right(), d.x() - o.right()) <= TOUCH
                            && Math.max(o.top() - d.bottom(), d.top() - o.bottom()) <= TOUCH) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int cell(float v) {
        return (int) Math.floor(v / CELL);
    }

    private static long key(int x, int y) {
        return ((long) x << 32) ^ (y & 0xFFFFFFFFL);
    }
}
