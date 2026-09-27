package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import stirling.software.officeconvert.table.PageContent.PdfWord;
import stirling.software.officeconvert.table.TableDetection;

final class TablePlausibility {

    private static final int PROSE_LINE = 25;

    private TablePlausibility() {}

    static boolean plausible(TableDetection.Found t, List<PdfWord> words) {
        if (t.rows() < 2 || t.cols() < 2) {
            return false;
        }
        if (t.ruled()) {
            return true;
        }
        int filled = 0;
        int longCells = 0;
        for (TableDetection.FoundCell c : t.cells()) {
            if (!c.text().isBlank()) {
                filled++;
                if (c.text().split("\\s+").length > 12) {
                    longCells++;
                }
            }
        }
        return t.rows() >= 3 && filled >= 5 && longCells * 3 <= filled && !textColumns(t) && !mixedRows(t, words)
                && !proseAcrossEdges(t, words) && !independentSides(t, words);
    }

    static boolean independentSides(TableDetection.Found t, List<PdfWord> words) {
        for (int e = 0; e < t.cols() - 1; e++) {
            int leftOnly = 0;
            int rightOnly = 0;
            int rows = 0;
            for (int r = 0; r < t.rows(); r++) {
                boolean left = false;
                boolean right = false;
                boolean spans = false;
                for (TableDetection.FoundCell c : t.cells()) {
                    if (c.row() != r || c.text().isBlank()) {
                        continue;
                    }
                    int last = c.col() + c.colSpan() - 1;
                    spans |= c.col() <= e && last > e;
                    left |= last <= e;
                    right |= c.col() > e;
                }
                if (spans || !left && !right) {
                    continue;
                }
                rows++;
                leftOnly += left && !right ? 1 : 0;
                rightOnly += right && !left ? 1 : 0;
            }
            if (leftOnly >= 2 && rightOnly >= 2 && (leftOnly + rightOnly) * 4 >= rows && gutterRunsOn(t, e, words)) {
                return true;
            }
        }
        return false;
    }

    private static boolean gutterRunsOn(TableDetection.Found t, int e, List<PdfWord> words) {
        float edge = t.colEdges()[e + 1];
        float gl = -Float.MAX_VALUE;
        float gr = Float.MAX_VALUE;
        float size = 0;
        List<PdfWord> outside = new ArrayList<>();
        for (PdfWord w : words) {
            float cy = (w.top() + w.bottom()) / 2f;
            if (cy < t.top() || cy > t.bottom()) {
                outside.add(w);
            } else if (w.x() >= t.left() - 1 && w.right() <= t.right() + 1) {
                if (w.right() <= edge) {
                    gl = Math.max(gl, w.right());
                } else if (w.x() >= edge) {
                    gr = Math.min(gr, w.x());
                } else {
                    return false;
                }
                size = Math.max(size, w.fontSize());
            }
        }
        if (gl == -Float.MAX_VALUE || gr == Float.MAX_VALUE || gr - gl < 6) {
            return false;
        }
        float need = Math.max(3.6f * size, 0.5f * (t.bottom() - t.top()));
        List<PdfWord> below = outside.stream().filter(w -> w.top() >= t.bottom())
                .sorted(Comparator.comparingDouble(PdfWord::top)).toList();
        List<PdfWord> above = outside.stream().filter(w -> w.bottom() <= t.top())
                .sorted(Comparator.comparingDouble(PdfWord::bottom).reversed()).toList();
        return runsOn(below, gl + 1, gr - 1, t.bottom(), need, true) || runsOn(above, gl + 1, gr - 1, t.top(), need, false);
    }

    private static boolean runsOn(List<PdfWord> away, float gl, float gr, float from, float need, boolean down) {
        int left = 0;
        int right = 0;
        float reached = from;
        for (PdfWord w : away) {
            if (w.x() < gr && w.right() > gl) {
                break;
            }
            if (w.right() <= gl) {
                left++;
            } else {
                right++;
            }
            reached = down ? Math.max(reached, w.bottom()) : Math.min(reached, w.top());
        }
        return Math.abs(reached - from) >= need && left >= 2 && right >= 2;
    }

    static boolean proseAcrossEdges(TableDetection.Found t, List<PdfWord> words) {
        float[] edges = t.colEdges();
        List<PdfWord> inside = new ArrayList<>();
        for (PdfWord w : words) {
            float cx = (w.x() + w.right()) / 2f;
            float cy = (w.top() + w.bottom()) / 2f;
            if (cx >= t.left() && cx <= t.right() && cy >= t.top() && cy <= t.bottom()) {
                inside.add(w);
            }
        }
        inside.sort(Comparator.comparingDouble(PdfWord::baseline).thenComparingDouble(PdfWord::x));
        int twoSided = 0;
        int flowing = 0;
        for (int e = 1; e < edges.length - 1; e++) {
            float edge = edges[e];
            for (int i = 0; i < inside.size(); ) {
                int j = i;
                while (j < inside.size() && Math.abs(inside.get(j).baseline() - inside.get(i).baseline()) < 1.5f) {
                    j++;
                }
                PdfWord left = null;
                PdfWord right = null;
                for (PdfWord w : inside.subList(i, j)) {
                    if (w.right() <= edge + 1 && (left == null || w.right() > left.right())) {
                        left = w;
                    }
                    if (w.x() >= edge - 1 && (right == null || w.x() < right.x())) {
                        right = w;
                    }
                }
                if (left != null && right != null && left != right) {
                    twoSided++;
                    if (right.x() - left.right() < 0.5f * (left.bottom() - left.top())) {
                        flowing++;
                    }
                }
                i = j;
            }
        }
        return flowing >= 3 && flowing * 5 >= twoSided;
    }

    static boolean mixedRows(TableDetection.Found t, List<PdfWord> words) {
        float[][] range = new float[t.rows()][];
        for (TableDetection.FoundCell c : t.cells()) {
            float size = cellSize(c, words);
            if (size > 0 && c.row() < t.rows()) {
                float[] r = range[c.row()];
                range[c.row()] = r == null ? new float[] {size, size, 1} : new float[] {Math.min(r[0], size), Math.max(r[1], size), r[2] + 1};
            }
        }
        int shared = 0;
        int mixed = 0;
        for (float[] r : range) {
            if (r != null && r[2] >= 2) {
                shared++;
                mixed += r[1] > r[0] * 1.3f ? 1 : 0;
            }
        }
        return shared >= 2 && mixed * 2 >= shared;
    }

    private static float cellSize(TableDetection.FoundCell c, List<PdfWord> words) {
        float size = 0;
        for (PdfWord w : words) {
            float cx = (w.x() + w.right()) / 2f;
            float cy = (w.top() + w.bottom()) / 2f;
            if (cx >= c.left() && cx <= c.right() && cy >= c.top() && cy <= c.bottom()) {
                size = Math.max(size, w.fontSize());
            }
        }
        return size;
    }

    static boolean textColumns(TableDetection.Found t) {
        String[][] grid = grid(t);
        int content = 0;
        for (int c = 0; c < t.cols(); c++) {
            int filled = 0;
            int lines = 0;
            int broken = 0;
            for (int r = 0; r < t.rows(); r++) {
                String s = grid[r][c];
                if (s.isEmpty()) {
                    continue;
                }
                filled++;
                if (s.length() >= PROSE_LINE && s.split("\\s+").length >= 4) {
                    lines++;
                }
                if (r + 1 < t.rows() && brokenWord(s) && startsLower(grid[r + 1][c])) {
                    broken++;
                }
            }
            if (filled < 3) {
                continue;
            }
            if (lines * 5 < filled * 3 && broken < 2) {
                return false;
            }
            content++;
        }
        return content >= 2;
    }

    private static String[][] grid(TableDetection.Found t) {
        String[][] grid = new String[t.rows()][t.cols()];
        for (String[] row : grid) {
            Arrays.fill(row, "");
        }
        for (TableDetection.FoundCell c : t.cells()) {
            if (c.row() < t.rows() && c.col() < t.cols()) {
                grid[c.row()][c.col()] = c.text().strip();
            }
        }
        return grid;
    }

    private static boolean brokenWord(String s) {
        int n = s.length();
        return n >= 2 && s.charAt(n - 1) == '-' && Character.isLetter(s.charAt(n - 2));
    }

    private static boolean startsLower(String s) {
        return !s.isEmpty() && Character.isLowerCase(s.charAt(0));
    }
}
