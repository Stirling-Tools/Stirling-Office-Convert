package stirling.software.officeconvert.photosplit;

final class Documents {

    private static final int PAPER_LUMA = 200;
    private static final int PAPER_CHROMA = 24;
    private static final int INK_LUMA = 120;
    private static final double PAPER_SHARE = 0.55;
    private static final double INK_MIN = 0.02;
    private static final double INK_MAX = 0.35;
    private static final double TRANSITIONS_PER_PIXEL = 0.003;

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
                && (double) transitions / total >= TRANSITIONS_PER_PIXEL;
    }
}
