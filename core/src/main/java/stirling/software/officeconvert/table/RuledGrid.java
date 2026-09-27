package stirling.software.officeconvert.table;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.table.DraftTable.Borders;
import stirling.software.officeconvert.table.PageContent.PdfWord;
import stirling.software.officeconvert.table.PageContent.Ruling;

final class RuledGrid {

    static final float LINE_TOLERANCE = 2f;

    private static final float EDGE_COVERAGE = 0.6f;

    final float[] xs;
    final float[] ys;
    private final boolean[][] vertical;
    private final boolean[][] horizontal;

    private RuledGrid(float[] xs, float[] ys, boolean[][] vertical, boolean[][] horizontal) {
        this.xs = xs;
        this.ys = ys;
        this.vertical = vertical;
        this.horizontal = horizontal;
    }

    static RuledGrid measure(float[] xs, float[] ys, List<Ruling> hs, List<Ruling> vs) {
        int rows = ys.length - 1;
        int cols = xs.length - 1;
        float tolerance = LINE_TOLERANCE + 0.5f;
        boolean[][] vertical = new boolean[rows][cols + 1];
        boolean[][] horizontal = new boolean[rows + 1][cols];
        for (int c = 0; c <= cols; c++) {
            List<Ruling> near = near(vs, xs[c], tolerance);
            for (int r = 0; r < rows; r++) {
                vertical[r][c] =
                        Rulings.coverage(near, xs[c], ys[r], ys[r + 1], tolerance) >= EDGE_COVERAGE;
            }
        }
        for (int r = 0; r <= rows; r++) {
            List<Ruling> near = near(hs, ys[r], tolerance);
            for (int c = 0; c < cols; c++) {
                horizontal[r][c] =
                        Rulings.coverage(near, ys[r], xs[c], xs[c + 1], tolerance) >= EDGE_COVERAGE;
            }
        }
        return new RuledGrid(xs, ys, vertical, horizontal);
    }

    private static List<Ruling> near(List<Ruling> rulings, float pos, float tolerance) {
        List<Ruling> near = new ArrayList<>();
        for (Ruling r : rulings) {
            if (Math.abs(r.pos() - pos) <= tolerance) {
                near.add(r);
            }
        }
        return near;
    }

    int rows() {
        return ys.length - 1;
    }

    int cols() {
        return xs.length - 1;
    }

    int rowAt(float y) {
        return band(ys, y);
    }

    int colAt(float x) {
        return band(xs, x);
    }

    static int band(float[] edges, float v) {
        int idx = Arrays.binarySearch(edges, v);
        if (idx < 0) {
            idx = -idx - 2;
        }
        return Math.clamp(idx, 0, edges.length - 2);
    }

    void addTextColumnLines(List<PdfWord> words) {
        int rows = rows();
        List<List<PdfWord>> byRow = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            byRow.add(new ArrayList<>());
        }
        for (PdfWord w : words) {
            byRow.get(rowAt(w.centreY())).add(w);
        }
        for (int c = 1; c < xs.length - 1; c++) {
            float x = xs[c];
            boolean[] crossed = new boolean[rows];
            int crossing = 0;
            int splitting = 0;
            for (int r = 0; r < rows; r++) {
                if (vertical[r][c]) {
                    continue;
                }
                boolean left = false;
                boolean right = false;
                for (PdfWord w : byRow.get(r)) {
                    crossed[r] |= w.x() < x - 1f && w.right() > x + 1f;
                    left |= w.right() <= x + 1f && w.x() >= xs[c - 1] - 1f;
                    right |= w.x() >= x - 1f && w.right() <= xs[c + 1] + 1f;
                }
                if (crossed[r]) {
                    crossing++;
                } else if (left && right) {
                    splitting++;
                }
            }
            if (splitting > 0 && splitting > crossing) {
                for (int r = 0; r < rows; r++) {
                    vertical[r][c] |= !crossed[r];
                }
            }
        }
    }

    int[][] anchors() {
        int rows = rows();
        int cols = cols();
        UnionFind groups = new UnionFind(rows * cols);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (c + 1 < cols && !vertical[r][c + 1]) {
                    groups.union(r * cols + c, r * cols + c + 1);
                }
                if (r + 1 < rows && !horizontal[r + 1][c]) {
                    groups.union(r * cols + c, (r + 1) * cols + c);
                }
            }
        }
        Map<Integer, int[]> bounds = new HashMap<>();
        Map<Integer, Integer> sizes = new HashMap<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int g = groups.find(r * cols + c);
                int[] b = bounds.computeIfAbsent(g, k -> new int[] {rows, cols, -1, -1});
                b[0] = Math.min(b[0], r);
                b[1] = Math.min(b[1], c);
                b[2] = Math.max(b[2], r);
                b[3] = Math.max(b[3], c);
                sizes.merge(g, 1, Integer::sum);
            }
        }
        int[][] anchor = new int[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int[] b = bounds.get(groups.find(r * cols + c));
                int area = (b[2] - b[0] + 1) * (b[3] - b[1] + 1);
                boolean rectangle = area == sizes.get(groups.find(r * cols + c));
                anchor[r][c] = rectangle ? b[0] * cols + b[1] : r * cols + c;
            }
        }
        return anchor;
    }

    List<int[]> openOutline() {
        int rows = rows();
        int cols = cols();
        List<int[]> out = new ArrayList<>();
        for (int side = 0; side < 2; side++) {
            boolean[] edge = horizontal[side == 0 ? 0 : rows];
            if (anyDrawn(edge)) {
                for (int c = 0; c < cols; c++) {
                    if (!edge[c]) {
                        out.add(new int[] {side == 0 ? 0 : rows - 1, c});
                    }
                }
            }
        }
        for (int side = 0; side < 2; side++) {
            int c = side == 0 ? 0 : cols;
            boolean drawn = false;
            for (int r = 0; r < rows; r++) {
                drawn |= vertical[r][c];
            }
            for (int r = 0; drawn && r < rows; r++) {
                if (!vertical[r][c]) {
                    out.add(new int[] {r, side == 0 ? 0 : cols - 1});
                }
            }
        }
        return out;
    }

    private static boolean anyDrawn(boolean[] edge) {
        for (boolean e : edge) {
            if (e) {
                return true;
            }
        }
        return false;
    }

    Borders borders(int r0, int c0, int r1, int c1) {
        return new Borders(
                mostlyDrawn(horizontal[r0], c0, c1),
                mostlyDrawn(horizontal[r1 + 1], c0, c1),
                mostlyDrawnDown(c0, r0, r1),
                mostlyDrawnDown(c1 + 1, r0, r1));
    }

    boolean[] verticalsInRow(int r) {
        return vertical[r];
    }

    private static boolean mostlyDrawn(boolean[] edges, int from, int to) {
        int n = 0;
        for (int i = from; i <= to; i++) {
            if (edges[i]) {
                n++;
            }
        }
        return n * 2 >= to - from + 1;
    }

    private boolean mostlyDrawnDown(int col, int fromRow, int toRow) {
        int n = 0;
        for (int r = fromRow; r <= toRow; r++) {
            if (vertical[r][col]) {
                n++;
            }
        }
        return n * 2 >= toRow - fromRow + 1;
    }
}
