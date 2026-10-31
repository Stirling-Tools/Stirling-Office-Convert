package stirling.software.officeconvert.topdf.docx;

import java.util.List;

final class ColumnBalance {

    private ColumnBalance() {}

    static boolean columnBreak(List<Block> blocks) {
        for (Block b : blocks) {
            if (b instanceof Para p) {
                for (Inline in : p.items) {
                    if (in instanceof Inline.Break br && "column".equals(br.type())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static float[] gaps(List<Placed> list, float origin) {
        float[] g = new float[list.size()];
        g[0] = Math.max(0, list.get(0).y - origin);
        for (int k = 1; k < list.size(); k++) {
            Placed a = list.get(k - 1);
            Placed b = list.get(k);
            float bottom = a.y + a.strip.height;
            g[k] = a.column == b.column && b.y >= bottom - 0.01f ? b.y - bottom : b.gap;
        }
        return g;
    }

    static boolean fits(List<Placed> list, float[] gaps, float height, int cols, boolean topSuppressed) {
        int c = 0;
        float at = 0;
        for (int k = 0; k < list.size(); k++) {
            Placed p = list.get(k);
            float g = gaps[k];
            if (at > 0 && at + g + p.strip.height > height + 0.05f) {
                c++;
                at = 0;
                g = topSuppressed ? 0 : gaps[k];
                if (c >= cols) {
                    return false;
                }
            }
            at += g + p.strip.height;
        }
        return true;
    }

    static float shortest(List<Placed> list, float[] gaps, float lo, float hi, int cols, boolean topSuppressed) {
        for (int it = 0; it < 30 && hi - lo > 0.05f; it++) {
            float mid = (lo + hi) / 2;
            if (fits(list, gaps, mid, cols, topSuppressed)) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return hi;
    }

    static float arrange(List<Placed> list, float[] gaps, float height, float[] colX, float frameTop,
            boolean topSuppressed) {
        int cols = colX.length;
        int c = 0;
        float at = 0;
        float max = 0;
        for (int k = 0; k < list.size(); k++) {
            Placed p = list.get(k);
            float g = gaps[k];
            if (at > 0 && at + g + p.strip.height > height + 0.05f) {
                c++;
                at = 0;
                g = topSuppressed ? 0 : gaps[k];
            }
            c = Math.min(c, cols - 1);
            float dx = colX[c] - colX[Math.min(p.column, colX.length - 1)];
            p.x += dx;
            p.y = frameTop + at + g;
            p.column = c;
            at += g + p.strip.height;
            max = Math.max(max, at);
        }
        return max;
    }
}
