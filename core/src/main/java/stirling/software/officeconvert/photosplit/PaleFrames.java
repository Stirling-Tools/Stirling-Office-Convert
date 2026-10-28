package stirling.software.officeconvert.photosplit;

final class PaleFrames {

    private static final int FRAME_BAND = 8;
    private static final int FRAME_BINS = 16;
    private static final int MIN_FRAME_BINS = 12;
    private static final int MAX_REGION_AREA_MULTIPLE = 8;

    private PaleFrames() {}

    static void seed(byte[] mask, byte[] faint, byte[] photo, int w, int h, PhotoSplitter.Options options) {
        var regions = Region.label(mask.clone(), w, h, (long) MAX_REGION_AREA_MULTIPLE * w * h);
        if (regions == null) {
            return;
        }
        for (Region region : regions) {
            PhotoSplitter.checkpoint();
            if (region.count < Math.max(64, options.minContourArea())) {
                continue;
            }
            RotatedRect rect = region.rect();
            if (rect.area() < options.minArea() || region.count > 0.25 * rect.area()) {
                continue;
            }
            double a = Math.toRadians(rect.angle());
            double c = Math.cos(a);
            double s = Math.sin(a);
            int border = 0;
            for (int y = 0; y < region.height(); y++) {
                PhotoSplitter.checkpoint();
                for (int x = 0; x < region.width(); x++) {
                    if (region.mask[y * region.width() + x] == 0) {
                        continue;
                    }
                    double dx = region.minX + x + 0.5 - rect.cx();
                    double dy = region.minY + y + 0.5 - rect.cy();
                    double u = dx * c + dy * s;
                    double v = -dx * s + dy * c;
                    if (Math.min(Math.abs(Math.abs(u) - rect.width() / 2),
                            Math.abs(Math.abs(v) - rect.height() / 2)) <= FRAME_BAND) {
                        border++;
                    }
                }
            }
            if (border < 0.9 * region.count) {
                continue;
            }
            int x0 = w, y0 = h, x1 = 0, y1 = 0;
            for (var point : rect.corners()) {
                x0 = Math.min(x0, (int) Math.floor(point.x));
                y0 = Math.min(y0, (int) Math.floor(point.y));
                x1 = Math.max(x1, (int) Math.ceil(point.x));
                y1 = Math.max(y1, (int) Math.ceil(point.y));
            }
            if (x0 <= 1 || y0 <= 1 || x1 >= w - 2 || y1 >= h - 2) {
                continue;
            }
            int bw = x1 - x0 + 1;
            int bh = y1 - y0 + 1;
            int[][] edges = new int[4][FRAME_BINS];
            int supported = 0;
            int covered = 0;
            int area = 0;
            for (int y = y0; y <= y1; y++) {
                PhotoSplitter.checkpoint();
                for (int x = x0; x <= x1; x++) {
                    double dx = x + 0.5 - rect.cx();
                    double dy = y + 0.5 - rect.cy();
                    double u = dx * c + dy * s;
                    double v = -dx * s + dy * c;
                    int index = y * w + x;
                    if (Math.abs(u) <= rect.width() / 2 && Math.abs(v) <= rect.height() / 2) {
                        area++;
                        supported += photo[index];
                        covered += mask[index];
                    }
                    if (faint[index] == 0) {
                        continue;
                    }
                    edge(edges[0], v + rect.height() / 2, u, rect.width());
                    edge(edges[1], u - rect.width() / 2, v, rect.height());
                    edge(edges[2], v - rect.height() / 2, u, rect.width());
                    edge(edges[3], u + rect.width() / 2, v, rect.height());
                }
            }
            if (supported < Math.max(options.minContourArea(), 0.02 * area) || covered >= 0.75 * area
                    || !coherent(edges, rect)) {
                continue;
            }
            byte[] interior = new byte[Math.multiplyExact(bw, bh)];
            for (int y = y0; y <= y1; y++) {
                PhotoSplitter.checkpoint();
                for (int x = x0; x <= x1; x++) {
                    double dx = x + 0.5 - rect.cx();
                    double dy = y + 0.5 - rect.cy();
                    if (Math.abs(dx * c + dy * s) <= rect.width() / 2
                            && Math.abs(-dx * s + dy * c) <= rect.height() / 2) {
                        interior[(y - y0) * bw + x - x0] = photo[y * w + x];
                    }
                }
            }
            var supportedParts = Region.label(interior, bw, bh, 2L * bw * bh);
            if (supportedParts == null) {
                continue;
            }
            int parts = 0;
            for (Region part : supportedParts) {
                if (part.count >= Math.max(options.minContourArea(), 0.02 * area)) {
                    parts++;
                }
            }
            if (parts != 1) {
                continue;
            }
            for (int y = y0; y <= y1; y++) {
                PhotoSplitter.checkpoint();
                for (int x = x0; x <= x1; x++) {
                    double dx = x + 0.5 - rect.cx();
                    double dy = y + 0.5 - rect.cy();
                    if (Math.abs(dx * c + dy * s) <= rect.width() / 2
                            && Math.abs(-dx * s + dy * c) <= rect.height() / 2) {
                        mask[y * w + x] = 1;
                    }
                }
            }
        }
    }

    static boolean boundary(Region part, RotatedRect rect) {
        int[][] edges = new int[4][FRAME_BINS];
        double angle = Math.toRadians(rect.angle());
        double c = Math.cos(angle), s = Math.sin(angle);
        for (int y = 0; y < part.height(); y++) {
            PhotoSplitter.checkpoint();
            for (int x = 0; x < part.width(); x++) {
                if (part.mask[y * part.width() + x] == 0) {
                    continue;
                }
                double dx = part.minX + x + 0.5 - rect.cx();
                double dy = part.minY + y + 0.5 - rect.cy();
                double u = dx * c + dy * s;
                double v = -dx * s + dy * c;
                edge(edges[0], v + rect.height() / 2, u, rect.width());
                edge(edges[1], u - rect.width() / 2, v, rect.height());
                edge(edges[2], v - rect.height() / 2, u, rect.width());
                edge(edges[3], u + rect.width() / 2, v, rect.height());
            }
        }
        return coherent(edges, rect);
    }

    private static void edge(int[] bins, double distance, double along, double length) {
        if (Math.abs(distance) <= FRAME_BAND) {
            int bin = (int) Math.floor((along / length + 0.5) * bins.length);
            if (bin >= 0 && bin < bins.length) {
                bins[bin]++;
            }
        }
    }

    private static boolean coherent(int[][] edges, RotatedRect rect) {
        for (int side = 0; side < edges.length; side++) {
            double length = side % 2 == 0 ? rect.width() : rect.height();
            double minimum = Math.max(2, length / edges[side].length * 0.1);
            int filled = 0;
            for (int n : edges[side]) {
                if (n >= minimum) {
                    filled++;
                }
            }
            if (filled < MIN_FRAME_BINS) {
                return false;
            }
        }
        return true;
    }

    static void recover(byte[] mask, byte[] faint, byte[] photo, int w, int h, PhotoSplitter.Options options) {
        int disconnected = 0;
        int required = Math.max(64, options.minContourArea());
        for (int i = 0; i < mask.length && disconnected < required; i++) {
            if ((i & 0x3FFFF) == 0) {
                PhotoSplitter.checkpoint();
            }
            if (faint[i] != 0 && mask[i] == 0) {
                disconnected++;
            }
        }
        if (disconnected < required) {
            return;
        }
        PhotoSplitter.checkpoint();
        Morphology.dilate(faint, w, h, 2);
        Morphology.erode(faint, w, h, 2);
        Filters.fillHoles(faint, w, h);
        for (Region region : Region.label(faint, w, h)) {
            PhotoSplitter.checkpoint();
            if (region.minX <= 1 || region.minY <= 1 || region.maxX >= w - 2 || region.maxY >= h - 2
                    || region.count < options.minArea()) {
                continue;
            }
            RotatedRect rect = region.rect();
            if (region.count < 0.95 * rect.area()) {
                continue;
            }
            int supported = 0;
            int covered = 0;
            for (int y = 0; y < region.height(); y++) {
                int base = (y + region.minY) * w + region.minX;
                for (int x = 0; x < region.width(); x++) {
                    if (region.mask[y * region.width() + x] != 0) {
                        supported += photo[base + x];
                        covered += mask[base + x];
                    }
                }
            }
            if (supported < Math.max(options.minContourArea(), 0.02 * region.count) || covered >= 0.75 * region.count) {
                continue;
            }
            byte[] interior = new byte[region.mask.length];
            for (int y = 0; y < region.height(); y++) {
                PhotoSplitter.checkpoint();
                int base = (y + region.minY) * w + region.minX;
                for (int x = 0; x < region.width(); x++) {
                    int index = y * region.width() + x;
                    interior[index] = (byte) (photo[base + x] & region.mask[index]);
                }
            }
            int parts = 0;
            for (Region part : Region.label(interior, region.width(), region.height())) {
                if (part.count >= Math.max(options.minContourArea(), 0.02 * region.count)) {
                    parts++;
                }
            }
            if (parts != 1) {
                continue;
            }
            for (int y = 0; y < region.height(); y++) {
                PhotoSplitter.checkpoint();
                int base = (y + region.minY) * w + region.minX;
                for (int x = 0; x < region.width(); x++) {
                    mask[base + x] |= region.mask[y * region.width() + x];
                }
            }
        }
    }
}
