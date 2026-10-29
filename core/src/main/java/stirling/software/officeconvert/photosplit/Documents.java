package stirling.software.officeconvert.photosplit;

final class Documents {

    private static final int PAPER_LUMA = 200;
    private static final int PAPER_CHROMA = 24;
    private static final int INK_LUMA = 120;
    private static final double PAPER_SHARE = 0.55;
    private static final double INK_MIN = 0.02;
    private static final double INK_MAX = 0.35;
    private static final double TRANSITIONS_PER_PIXEL = 0.003;
    private static final double SPARSE_INK = 0.05;
    private static final double PHOTO_TONES = 0.05;
    private static final int MARGIN_SHARE = 20;
    private static final int LINE_INK_SHARE = 100;
    private static final int MIN_LINE_INK = 2;
    private static final int MIN_LINE_RUNS = 3;
    private static final int MIN_LINES = 5;
    private static final int MIN_MARK_PIXELS = 4;
    private static final int MIN_MARKS = 8;

    private Documents() {}

    static boolean isText(ScanImage scan, RotatedRect rect) {
        double a = Math.toRadians(rect.angle());
        double c = Math.cos(a);
        double s = Math.sin(a);
        int w = (int) rect.width();
        int h = (int) rect.height();
        if (w < 8 || h < 8) {
            return false;
        }
        long paper = 0;
        long ink = 0;
        long total = 0;
        long transitions = 0;
        for (int v = 2; v < h - 2; v += 2) {
            boolean inInk = false;
            for (int u = 2; u < w - 2; u++) {
                double du = u + 0.5 - w / 2.0;
                double dv = v + 0.5 - h / 2.0;
                int x = (int) (rect.cx() + du * c - dv * s);
                int y = (int) (rect.cy() + du * s + dv * c);
                if (x < 0 || y < 0 || x >= scan.width || y >= scan.height) {
                    continue;
                }
                int p = scan.rgb[y * scan.width + x];
                int r = p >> 16 & 0xFF;
                int g = p >> 8 & 0xFF;
                int b = p & 0xFF;
                int luma = (r * 299 + g * 587 + b * 114) / 1000;
                int chroma = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                total++;
                boolean dark = luma < INK_LUMA;
                if (dark) {
                    ink++;
                } else if (luma > PAPER_LUMA && chroma < PAPER_CHROMA) {
                    paper++;
                }
                if (dark && !inInk) {
                    transitions++;
                }
                inInk = dark;
            }
        }
        if (total == 0) {
            return false;
        }
        double inkShare = (double) ink / total;
        return (double) paper / total >= PAPER_SHARE && inkShare >= INK_MIN && inkShare <= INK_MAX
                && (double) transitions / total >= TRANSITIONS_PER_PIXEL
                && (inkShare > SPARSE_INK || (double) (total - paper - ink) / total < PHOTO_TONES || marks(scan, rect, c, s, w, h) >= MIN_MARKS || lines(scan, rect) >= MIN_LINES);
    }

    private static int marks(ScanImage scan, RotatedRect rect, double c, double s, int w, int h) {
        int mu = w / MARGIN_SHARE;
        int mv = h / MARGIN_SHARE;
        int iw = w - 2 * mu;
        int ih = h - 2 * mv;
        byte[] dark = new byte[iw * ih];
        for (int v = 0; v < ih; v++) {
            for (int u = 0; u < iw; u++) {
                double du = u + mu + 0.5 - w / 2.0;
                double dv = v + mv + 0.5 - h / 2.0;
                int x = (int) (rect.cx() + du * c - dv * s);
                int y = (int) (rect.cy() + du * s + dv * c);
                if (x < 0 || y < 0 || x >= scan.width || y >= scan.height) {
                    continue;
                }
                int p = scan.rgb[y * scan.width + x];
                if (((p >> 16 & 0xFF) * 299 + (p >> 8 & 0xFF) * 587 + (p & 0xFF) * 114) / 1000 < INK_LUMA) {
                    dark[v * iw + u] = 1;
                }
            }
        }
        int marks = 0;
        for (Region mark : Region.label(dark, iw, ih)) {
            if (mark.count >= MIN_MARK_PIXELS) {
                marks++;
            }
        }
        return marks;
    }

    private static int lines(ScanImage scan, RotatedRect rect) {
        int most = 0;
        for (double angle : new double[] {rect.angle(), rect.angle() + 90, 0, 90}) {
            most = Math.max(most, lines(scan, rect, angle));
        }
        return most;
    }

    private static int lines(ScanImage scan, RotatedRect rect, double angle) {
        double a = Math.toRadians(angle);
        double c = Math.cos(a);
        double s = Math.sin(a);
        double t = Math.toRadians(angle - rect.angle());
        double tc = Math.cos(t);
        double ts = Math.sin(t);
        double halfU = rect.width() * (0.5 - 1.0 / MARGIN_SHARE);
        double halfV = rect.height() * (0.5 - 1.0 / MARGIN_SHARE);
        int reach = (int) Math.ceil(Math.hypot(rect.width(), rect.height()));
        int min = Math.max(MIN_LINE_INK, (int) Math.min(rect.width(), rect.height()) / LINE_INK_SHARE);
        int bands = 0;
        boolean inBand = false;
        for (int v = 0; v < reach; v++) {
            double dv = v + 0.5 - reach / 2.0;
            int ink = 0;
            int runs = 0;
            boolean inInk = false;
            for (int u = 0; u < reach; u++) {
                double du = u + 0.5 - reach / 2.0;
                double ru = du * tc - dv * ts;
                double rv = du * ts + dv * tc;
                int x = (int) (rect.cx() + du * c - dv * s);
                int y = (int) (rect.cy() + du * s + dv * c);
                if (Math.abs(ru) > halfU || Math.abs(rv) > halfV || x < 0 || y < 0 || x >= scan.width || y >= scan.height) {
                    inInk = false;
                    continue;
                }
                int p = scan.rgb[y * scan.width + x];
                boolean dark = ((p >> 16 & 0xFF) * 299 + (p >> 8 & 0xFF) * 587 + (p & 0xFF) * 114) / 1000 < INK_LUMA;
                if (dark) {
                    ink++;
                    if (!inInk) {
                        runs++;
                    }
                }
                inInk = dark;
            }
            boolean line = ink > min && runs >= MIN_LINE_RUNS;
            if (line && !inBand) {
                bands++;
            }
            inBand = line;
        }
        return bands;
    }
}
