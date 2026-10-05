package stirling.software.officeconvert.layout;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.extract.ImageBudget;
import stirling.software.officeconvert.extract.PageGraphics.ImageDraw;
import stirling.software.officeconvert.extract.PageGraphics.Rule;

final class ScanRules {

    private static final int MAX_SIDE = 3600;

    private static final int DARK = 210;

    private static final int GAP = 2;

    private static final float MIN_LENGTH = 40f;

    private static final float MAX_THICKNESS = 4f;

    private static final float BAND = 30f;

    private static final int MAX_RULES = 400;

    private ScanRules() {}

    static List<Rule> find(ImageDraw scan) {
        if (scan.quarterTurns() != 0 || scan.flipH() || scan.flipV() || !ImageBudget.affordable(scan.image())) {
            return List.of();
        }
        BufferedImage img;
        try {
            int sub = Math.max(1, (Math.max(scan.image().getWidth(), scan.image().getHeight()) + MAX_SIDE - 1) / MAX_SIDE);
            img = scan.image().getImage(null, sub);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        if (img == null || img.getWidth() < 2 || img.getHeight() < 2) {
            return List.of();
        }
        int w = img.getWidth();
        int h = img.getHeight();
        boolean[] dark = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int lum = (((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
                dark[y * w + x] = lum < DARK;
            }
        }
        float sx = (scan.right() - scan.x()) / w;
        float sy = (scan.bottom() - scan.top()) / h;
        List<Rule> out = new ArrayList<>();
        for (int[] g : groups(dark, w, h, true, Math.round(MIN_LENGTH / sx))) {
            add(out, true, g, scan.top(), sy, scan.x(), sx);
        }
        for (int[] g : groups(dark, w, h, false, Math.round(MIN_LENGTH / sy))) {
            add(out, false, g, scan.x(), sx, scan.top(), sy);
        }
        if (out.size() > MAX_RULES) {
            return List.of();
        }
        List<Rule> kept = new ArrayList<>();
        for (Rule r : out) {
            if (anchored(r, out) && !doubled(r, out)) {
                kept.add(r);
            }
        }
        return kept;
    }

    private static boolean anchored(Rule r, List<Rule> all) {
        boolean crossed = false;
        boolean startTied = false;
        boolean endTied = false;
        for (Rule p : all) {
            if (p.horizontal() == r.horizontal() || p.pos() < r.start() - TIE || p.pos() > r.end() + TIE
                    || r.pos() < p.start() - TIE || r.pos() > p.end() + TIE) {
                continue;
            }
            crossed = true;
            startTied |= Math.abs(p.pos() - r.start()) <= TIE;
            endTied |= Math.abs(p.pos() - r.end()) <= TIE;
        }
        return !crossed || startTied && endTied;
    }

    private static final float TIE = 4f;

    private static boolean doubled(Rule r, List<Rule> all) {
        for (Rule p : all) {
            if (p != r && p.horizontal() == r.horizontal() && Math.abs(p.pos() - r.pos()) <= TIE && p.length() > r.length()
                    && p.start() <= r.start() + TIE && p.end() >= r.end() - TIE) {
                return true;
            }
        }
        return false;
    }

    private static void add(List<Rule> out, boolean horizontal, int[] g, float across0, float acrossScale, float along0,
            float alongScale) {
        float thickness = (g[3] - g[2] + 1) * acrossScale;
        float start = along0 + g[0] * alongScale;
        float end = along0 + (g[1] + 1) * alongScale;
        if (thickness <= MAX_THICKNESS) {
            out.add(new Rule(horizontal, across0 + (g[2] + g[3] + 1) / 2f * acrossScale, start, end, thickness, 0));
        } else if (thickness <= BAND) {
            out.add(new Rule(horizontal, across0 + (g[2] + 0.5f) * acrossScale, start, end, acrossScale, 0));
            out.add(new Rule(horizontal, across0 + (g[3] + 0.5f) * acrossScale, start, end, acrossScale, 0));
        }
    }

    private static List<int[]> groups(boolean[] dark, int w, int h, boolean horizontal, int minLength) {
        int lines = horizontal ? h : w;
        int along = horizontal ? w : h;
        List<int[]> open = new ArrayList<>();
        List<int[]> done = new ArrayList<>();
        for (int i = 0; i < lines; i++) {
            List<int[]> runs = runs(dark, w, horizontal, i, lines, along, Math.max(8, minLength));
            List<int[]> next = new ArrayList<>();
            for (int[] r : runs) {
                int[] host = null;
                for (int[] g : open) {
                    if (g[3] == i - 1 && Math.min(g[1], r[1]) - Math.max(g[0], r[0]) >= 0.5f * (r[1] - r[0])) {
                        host = g;
                        break;
                    }
                }
                if (host == null) {
                    host = new int[] {r[0], r[1], i, i};
                } else {
                    open.remove(host);
                    host[0] = Math.min(host[0], r[0]);
                    host[1] = Math.max(host[1], r[1]);
                    host[3] = i;
                }
                next.add(host);
            }
            done.addAll(open);
            open = next;
        }
        done.addAll(open);
        return done;
    }

    private static boolean at(boolean[] dark, int w, boolean horizontal, int line, int k) {
        return horizontal ? dark[line * w + k] : dark[k * w + line];
    }

    private static List<int[]> runs(boolean[] dark, int w, boolean horizontal, int line, int lines, int along,
            int minLength) {
        List<int[]> out = new ArrayList<>();
        int start = -1;
        int lastDark = -1;
        int count = 0;
        for (int k = 0; k <= along; k++) {
            boolean d = k < along && (at(dark, w, horizontal, line, k) || line + 1 < lines && at(dark, w, horizontal, line + 1, k));
            if (!d && k < along) {
                continue;
            }
            if (start >= 0 && (k == along || k - lastDark > GAP + 1)) {
                if (lastDark - start + 1 >= minLength && count >= SOLID * (lastDark - start + 1)) {
                    out.add(new int[] {start, lastDark});
                }
                start = -1;
            }
            if (d) {
                if (start < 0) {
                    start = k;
                    count = 0;
                }
                lastDark = k;
                count++;
            }
        }
        return out;
    }

    private static final float SOLID = 0.8f;
}
