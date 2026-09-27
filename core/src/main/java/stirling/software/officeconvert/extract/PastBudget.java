package stirling.software.officeconvert.extract;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class PastBudget {

    private static final int CELLS = 64;
    private static final float MIN_POINTS = 1000;

    private final float cellW;
    private final float cellH;
    private final float[] bounds = new float[CELLS * CELLS * 4];
    private final float[] points = new float[CELLS * CELLS];
    private boolean any;

    PastBudget(float pageWidth, float pageHeight) {
        cellW = Math.max(pageWidth, 1f) / CELLS;
        cellH = Math.max(pageHeight, 1f) / CELLS;
        Arrays.fill(bounds, Float.NaN);
    }

    void add(float x, float top, float right, float bottom, int pathPoints) {
        int c0 = cell(x, cellW);
        int c1 = cell(right, cellW);
        int r0 = cell(top, cellH);
        int r1 = cell(bottom, cellH);
        float share = (float) pathPoints / ((c1 - c0 + 1) * (r1 - r0 + 1));
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                points[r * CELLS + c] += share;
                int i = (r * CELLS + c) * 4;
                float l = Math.max(x, c * cellW);
                float t = Math.max(top, r * cellH);
                float rt = Math.min(right, (c + 1) * cellW);
                float b = Math.min(bottom, (r + 1) * cellH);
                if (Float.isNaN(bounds[i])) {
                    bounds[i] = l;
                    bounds[i + 1] = t;
                    bounds[i + 2] = rt;
                    bounds[i + 3] = b;
                } else {
                    bounds[i] = Math.min(bounds[i], l);
                    bounds[i + 1] = Math.min(bounds[i + 1], t);
                    bounds[i + 2] = Math.max(bounds[i + 2], rt);
                    bounds[i + 3] = Math.max(bounds[i + 3], b);
                }
            }
        }
        any = true;
    }

    private static int cell(float v, float size) {
        return Math.max(0, Math.min(CELLS - 1, (int) (v / size)));
    }

    List<PageGraphics.Area> areas() {
        List<PageGraphics.Area> out = new ArrayList<>();
        if (!any) {
            return out;
        }
        boolean[] seen = new boolean[CELLS * CELLS];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int start = 0; start < CELLS * CELLS; start++) {
            if (seen[start] || Float.isNaN(bounds[start * 4])) {
                continue;
            }
            float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            float drawn = 0;
            seen[start] = true;
            queue.add(start);
            while (!queue.isEmpty()) {
                int at = queue.poll();
                drawn += points[at];
                box[0] = Math.min(box[0], bounds[at * 4]);
                box[1] = Math.min(box[1], bounds[at * 4 + 1]);
                box[2] = Math.max(box[2], bounds[at * 4 + 2]);
                box[3] = Math.max(box[3], bounds[at * 4 + 3]);
                int r = at / CELLS;
                int c = at % CELLS;
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        int nr = r + dr;
                        int nc = c + dc;
                        int n = nr * CELLS + nc;
                        if (nr >= 0 && nr < CELLS && nc >= 0 && nc < CELLS && !seen[n] && !Float.isNaN(bounds[n * 4])) {
                            seen[n] = true;
                            queue.add(n);
                        }
                    }
                }
            }
            if (drawn >= MIN_POINTS) {
                out.add(new PageGraphics.Area(box[0], box[1], box[2], box[3]));
            }
        }
        return out;
    }
}
