package stirling.software.officeconvert.photosplit;

final class Crop {

    private Crop() {}

    static ScanImage of(ScanImage scan, RotatedRect rect, double levelAngle) {
        int w = Math.max(1, (int) Math.round(rect.width()));
        int h = Math.max(1, (int) Math.round(rect.height()));
        if (Math.abs(rect.angle()) < levelAngle) {
            int x0 = (int) Math.round(rect.cx() - w / 2.0);
            int y0 = (int) Math.round(rect.cy() - h / 2.0);
            int x1 = Math.min(scan.width, x0 + w);
            int y1 = Math.min(scan.height, y0 + h);
            x0 = Math.max(0, x0);
            y0 = Math.max(0, y0);
            return scan.crop(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0));
        }
        double c = Math.cos(Math.toRadians(rect.angle()));
        double s = Math.sin(Math.toRadians(rect.angle()));
        int[] out = new int[w * h];
        double[] wx = new double[4];
        double[] wy = new double[4];
        for (int v = 0; v < h; v++) {
            double dv = v + 0.5 - h / 2.0;
            for (int u = 0; u < w; u++) {
                double du = u + 0.5 - w / 2.0;
                double sx = rect.cx() + du * c - dv * s - 0.5;
                double sy = rect.cy() + du * s + dv * c - 0.5;
                out[v * w + u] = CubicWarp.sample(scan, sx, sy, wx, wy);
            }
        }
        return new ScanImage(w, h, out);
    }
}
