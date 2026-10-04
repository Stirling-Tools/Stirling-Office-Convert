package stirling.software.officeconvert.layout;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.extract.ImageBudget;
import stirling.software.officeconvert.extract.PageGraphics.ImageDraw;

final class ScanRegions {

    private static final int GRID = 120;

    private ScanRegions() {}

    static List<Box> find(ImageDraw scan, List<Line> ocrLines, float pageW, float pageH) {
        if (scan.quarterTurns() != 0 || scan.flipH() || scan.flipV() || !plausibleShape(scan)
                || !ImageBudget.affordable(scan.image())) {
            return List.of();
        }
        BufferedImage img;
        try {
            int sub = Math.max(1, scan.image().getWidth() / (GRID * 4));
            img = scan.image().getImage(null, sub);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        if (img == null) {
            return List.of();
        }
        float boxW = scan.right() - scan.x();
        float boxH = scan.bottom() - scan.top();
        int cols = GRID;
        int rows = Math.max(1, Math.round(GRID * boxH / boxW));
        boolean[][] whole = inkCells(img, cols, rows);
        boolean[][] ink = new boolean[rows][];
        for (int r = 0; r < rows; r++) {
            ink[r] = whole[r].clone();
        }
        maskWords(ink, ocrLines, scan, cols, rows);
        List<Box> out = new ArrayList<>();
        for (int[] c : components(ink)) {
            int area = (c[2] - c[0] + 1) * (c[3] - c[1] + 1);
            Box b = box(c, scan, cols, rows, pageW, pageH);
            if (c[4] >= 0.35f * area && b.area() >= 0.012f * pageW * pageH && b.width() >= 20 && b.height() >= 14) {
                out.add(b);
            }
        }
        for (int[] c : clusters(components(whole), CLUSTER_GAP)) {
            int area = (c[2] - c[0] + 1) * (c[3] - c[1] + 1);
            Box b = box(c, scan, cols, rows, pageW, pageH);
            if (c[4] < SPARSE * area || b.area() < 0.012f * pageW * pageH || inside(c, ink) < BARE * c[4]
                    || wordsIn(b, ocrLines) > FEW_WORDS || b.width() > MAX_ASPECT * b.height()
                    || b.height() > MAX_ASPECT * b.width()) {
                continue;
            }
            out.removeIf(o -> o.x() >= b.x() - 1 && o.right() <= b.right() + 1 && o.top() >= b.top() - 1
                    && o.bottom() <= b.bottom() + 1);
            out.add(b);
        }
        return out;
    }

    private static final int CLUSTER_GAP = 3;

    private static final float SPARSE = 0.15f;

    private static final int FEW_WORDS = 8;

    private static final float BARE = 0.6f;

    private static Box box(int[] c, ImageDraw scan, int cols, int rows, float pageW, float pageH) {
        float boxW = scan.right() - scan.x();
        float boxH = scan.bottom() - scan.top();
        float x = scan.x() + boxW * c[0] / cols;
        float top = scan.top() + boxH * c[1] / rows;
        float right = scan.x() + boxW * (c[2] + 1) / cols;
        float bottom = scan.top() + boxH * (c[3] + 1) / rows;
        return new Box(Math.max(0, x), Math.max(0, top), Math.min(pageW, right), Math.min(pageH, bottom));
    }

    private static int inside(int[] c, boolean[][] ink) {
        int n = 0;
        for (int r = c[1]; r <= c[3]; r++) {
            for (int k = c[0]; k <= c[2]; k++) {
                n += ink[r][k] ? 1 : 0;
            }
        }
        return n;
    }

    private static int wordsIn(Box b, List<Line> lines) {
        int n = 0;
        for (Line l : lines) {
            for (Word w : l.words) {
                float cx = (w.x + w.right) / 2f;
                float cy = (l.top + l.bottom) / 2f;
                n += cx >= b.x() && cx <= b.right() && cy >= b.top() && cy <= b.bottom() ? 1 : 0;
            }
        }
        return n;
    }

    private static List<int[]> clusters(List<int[]> parts, int gap) {
        List<int[]> out = new ArrayList<>();
        for (int[] p : parts) {
            if (p[4] >= 2) {
                out.add(p.clone());
            }
        }
        boolean merged = true;
        if (out.size() >= MAX_PARTS) {
            return List.of();
        }
        while (merged) {
            merged = false;
            for (int i = 0; i < out.size() && !merged; i++) {
                for (int j = i + 1; j < out.size(); j++) {
                    int[] a = out.get(i);
                    int[] b = out.get(j);
                    if (b[0] <= a[2] + gap && a[0] <= b[2] + gap && b[1] <= a[3] + gap && a[1] <= b[3] + gap) {
                        a[0] = Math.min(a[0], b[0]);
                        a[1] = Math.min(a[1], b[1]);
                        a[2] = Math.max(a[2], b[2]);
                        a[3] = Math.max(a[3], b[3]);
                        a[4] += b[4];
                        out.remove(j);
                        merged = true;
                        break;
                    }
                }
            }
        }
        return out;
    }

    private static final int MAX_PARTS = 600;

    private static final float MAX_ASPECT = 8;

    private static boolean plausibleShape(ImageDraw scan) {
        float boxW = scan.right() - scan.x();
        float boxH = scan.bottom() - scan.top();
        int w = scan.image().getWidth();
        int h = scan.image().getHeight();
        return boxW > 0 && boxH > 0 && w > 0 && h > 0
                && boxH / boxW <= MAX_ASPECT && boxW / boxH <= MAX_ASPECT
                && (float) h / w <= MAX_ASPECT && (float) w / h <= MAX_ASPECT;
    }

    private static boolean[][] inkCells(BufferedImage img, int cols, int rows) {
        boolean[][] ink = new boolean[rows][cols];
        int w = img.getWidth();
        int h = img.getHeight();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int x0 = c * w / cols;
                int x1 = Math.max(x0 + 1, (c + 1) * w / cols);
                int y0 = r * h / rows;
                int y1 = Math.max(y0 + 1, (r + 1) * h / rows);
                int dark = 0;
                int total = 0;
                for (int y = y0; y < Math.min(h, y1); y++) {
                    for (int x = x0; x < Math.min(w, x1); x++) {
                        int rgb = img.getRGB(x, y);
                        int red = (rgb >> 16) & 0xFF;
                        int green = (rgb >> 8) & 0xFF;
                        int blue = rgb & 0xFF;
                        int max = Math.max(red, Math.max(green, blue));
                        int min = Math.min(red, Math.min(green, blue));
                        if (max - min > 40 || (red * 299 + green * 587 + blue * 114) / 1000 < 190) {
                            dark++;
                        }
                        total++;
                    }
                }
                ink[r][c] = total > 0 && dark >= 0.3f * total;
            }
        }
        return ink;
    }

    private static void maskWords(boolean[][] ink, List<Line> lines, ImageDraw scan, int cols, int rows) {
        float boxW = scan.right() - scan.x();
        float boxH = scan.bottom() - scan.top();
        for (Line l : lines) {
            for (Word w : l.words) {
                int c0 = (int) Math.floor((w.x - scan.x()) / boxW * cols) - 1;
                int c1 = (int) Math.ceil((w.right - scan.x()) / boxW * cols) + 1;
                int r0 = (int) Math.floor((l.top - scan.top()) / boxH * rows) - 1;
                int r1 = (int) Math.ceil((l.bottom - scan.top()) / boxH * rows) + 1;
                for (int r = Math.max(0, r0); r < Math.min(rows, r1); r++) {
                    for (int c = Math.max(0, c0); c < Math.min(cols, c1); c++) {
                        ink[r][c] = false;
                    }
                }
            }
        }
    }

    private static List<int[]> components(boolean[][] ink) {
        int rows = ink.length;
        int cols = ink[0].length;
        boolean[][] seen = new boolean[rows][cols];
        List<int[]> out = new ArrayList<>();
        int[] stack = new int[rows * cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (!ink[r][c] || seen[r][c]) {
                    continue;
                }
                int[] comp = {c, r, c, r, 0};
                int top = 0;
                stack[top++] = r * cols + c;
                seen[r][c] = true;
                while (top > 0) {
                    int cell = stack[--top];
                    int cr = cell / cols;
                    int cc = cell % cols;
                    comp[0] = Math.min(comp[0], cc);
                    comp[1] = Math.min(comp[1], cr);
                    comp[2] = Math.max(comp[2], cc);
                    comp[3] = Math.max(comp[3], cr);
                    comp[4]++;
                    for (int dr = -1; dr <= 1; dr++) {
                        for (int dc = -1; dc <= 1; dc++) {
                            int nr = cr + dr;
                            int nc = cc + dc;
                            if (nr >= 0 && nr < rows && nc >= 0 && nc < cols && ink[nr][nc] && !seen[nr][nc]) {
                                seen[nr][nc] = true;
                                stack[top++] = nr * cols + nc;
                            }
                        }
                    }
                }
                out.add(comp);
            }
        }
        return out;
    }
}
