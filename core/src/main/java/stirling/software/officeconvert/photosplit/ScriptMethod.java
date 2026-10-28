package stirling.software.officeconvert.photosplit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class ScriptMethod {

    private static final int DILATE_RADIUS = 4;
    private static final int CANNY_LOW = 50;
    private static final int CANNY_HIGH = 150;
    private static final int HOUGH_VOTES = 200;
    private static final double ANALYSIS_PIXELS = 4_500_000;
    private static final double ANALYSIS_SPAN = 8192;

    private ScriptMethod() {}

    record Cut(Picture picture, int x, int y, double angle) {}

    private record Box(int x, int y, int width, int height) {}

    static List<PhotoSplitter.Photo> split(Picture scan, PhotoSplitter.Options options) {
        List<PhotoSplitter.Photo> photos = new ArrayList<>();
        for (Cut cut : cuts(scan, options)) {
            PhotoSplitter.checkpoint();
            Picture p = cut.picture();
            photos.add(PhotoSplitter.Photo.of(ScanImage.image(p), cut.x(), cut.y(), p.width(), p.height(), cut.angle()));
        }
        return photos;
    }

    static List<Cut> cuts(Picture scan, PhotoSplitter.Options options) {
        PhotoSplitter.checkpoint();
        int background = background(scan);
        int border = options.borderSize();
        Picture image = border > 0 ? new Padded(scan, border, background) : scan;
        List<Box> boxes = boxes(image, background, options);
        List<Cut> cuts = new ArrayList<>();
        for (Box c : boxes) {
            PhotoSplitter.checkpoint();
            Picture crop = new Window(image, c.x(), c.y(), c.width(), c.height());
            double angle = angle(crop, options.angleThreshold());
            if (angle != 0) {
                crop = CubicWarp.rotated(crop, angle);
            }
            int x = c.x() - border;
            int y = c.y() - border;
            if (border > 0 && crop.height() > 2 * border && crop.width() > 2 * border) {
                crop = new Window(crop, border, border, crop.width() - 2 * border, crop.height() - 2 * border);
                x += border;
                y += border;
            }
            cuts.add(new Cut(crop, x, y, angle));
        }
        return cuts;
    }

    private static List<Box> boxes(Picture image, int background, PhotoSplitter.Options options) {
        int w = image.width();
        int h = image.height();
        byte[] mask = mask(image, background, options.tolerance());
        Morphology.dilate(mask, w, h, DILATE_RADIUS);
        List<Box> boxes = new ArrayList<>();
        for (OuterContours.Contour c : OuterContours.find(mask, w, h)) {
            PhotoSplitter.checkpoint();
            if ((long) c.width() * c.height() < options.minArea() || OuterContours.area(mask, w, h, c) < options.minContourArea()) {
                continue;
            }
            boxes.add(new Box(c.x(), c.y(), c.width(), c.height()));
        }
        return boxes;
    }

    static int analysisFactor(int w, int h) {
        double byArea = Math.sqrt((double) w * h / ANALYSIS_PIXELS);
        double bySpan = ((double) w + h) / ANALYSIS_SPAN;
        return (int) Math.max(1, Math.ceil(Math.max(byArea, bySpan)));
    }

    static int background(Picture scan) {
        int w = scan.width();
        int h = scan.height();
        int[] samples = {scan.rgb(0, 0), scan.rgb(w - 1, 0), scan.rgb(w - 1, h - 1), scan.rgb(0, h - 1), scan.rgb(w / 2, h / 2)};
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int[] c = new int[samples.length];
            for (int i = 0; i < samples.length; i++) {
                c[i] = samples[i] >> shift & 0xFF;
            }
            Arrays.sort(c);
            out |= c[2] << shift;
        }
        return out;
    }

    private static byte[] mask(Picture image, int background, int tolerance) {
        int[] lo = new int[3];
        int[] hi = new int[3];
        for (int k = 0; k < 3; k++) {
            int c = background >> (16 - 8 * k) & 0xFF;
            lo[k] = Math.clamp(c - (long) tolerance, 0, 255);
            hi[k] = Math.clamp(c + (long) tolerance, 0, 255);
        }
        int w = image.width();
        int h = image.height();
        byte[] mask = new byte[Math.multiplyExact(w, h)];
        for (int y = 0; y < h; y++) {
            PhotoSplitter.checkpoint();
            for (int x = 0; x < w; x++) {
                int p = image.rgb(x, y);
                int r = p >> 16 & 0xFF;
                int g = p >> 8 & 0xFF;
                int b = p & 0xFF;
                boolean inside = r >= lo[0] && r <= hi[0] && g >= lo[1] && g <= hi[1] && b >= lo[2] && b <= hi[2];
                mask[y * w + x] = inside ? 0 : (byte) 1;
            }
        }
        return mask;
    }

    static double angle(Picture crop, int threshold) {
        int factor = analysisFactor(crop.width(), crop.height());
        Picture view = factor > 1 ? shrink(crop, factor) : crop;
        int w = view.width();
        int h = view.height();
        byte[] edges = Canny.edges(luma(view), w, h, CANNY_LOW, CANNY_HIGH);
        float[] thetas = HoughLines.thetas(edges, w, h, Math.max(1, HOUGH_VOTES / factor));
        if (thetas.length == 0) {
            return 0;
        }
        float[] angles = new float[thetas.length];
        for (int i = 0; i < thetas.length; i++) {
            angles[i] = thetas[i] * 180f / (float) Math.PI - 90f;
        }
        Arrays.sort(angles);
        int n = angles.length;
        float median = n % 2 == 1 ? angles[n / 2] : (angles[n / 2 - 1] + angles[n / 2]) / 2f;
        return Math.abs(median) < threshold ? 0 : median;
    }

    private static int[] luma(Picture view) {
        int w = view.width();
        int h = view.height();
        int[] g = new int[Math.multiplyExact(w, h)];
        for (int y = 0; y < h; y++) {
            PhotoSplitter.checkpoint();
            for (int x = 0; x < w; x++) {
                int p = view.rgb(x, y);
                g[y * w + x] = ((p >> 16 & 0xFF) * 4899 + (p >> 8 & 0xFF) * 9617 + (p & 0xFF) * 1868 + 8192) >> 14;
            }
        }
        return g;
    }

    private static ScanImage shrink(Picture picture, int factor) {
        int width = picture.width();
        int height = picture.height();
        int w = (width + factor - 1) / factor;
        int h = (height + factor - 1) / factor;
        int[] out = new int[Math.multiplyExact(w, h)];
        int[] r = new int[w];
        int[] g = new int[w];
        int[] b = new int[w];
        int[] n = new int[w];
        for (int ty = 0; ty < h; ty++) {
            PhotoSplitter.checkpoint();
            Arrays.fill(r, 0);
            Arrays.fill(g, 0);
            Arrays.fill(b, 0);
            Arrays.fill(n, 0);
            for (int y = ty * factor; y < Math.min(height, (ty + 1) * factor); y++) {
                PhotoSplitter.checkpoint();
                for (int x = 0; x < width; x++) {
                    int p = picture.rgb(x, y);
                    int tx = x / factor;
                    r[tx] += p >> 16 & 0xFF;
                    g[tx] += p >> 8 & 0xFF;
                    b[tx] += p & 0xFF;
                    n[tx]++;
                }
            }
            for (int tx = 0; tx < w; tx++) {
                int k = n[tx];
                out[ty * w + tx] = (r[tx] + k / 2) / k << 16 | (g[tx] + k / 2) / k << 8 | (b[tx] + k / 2) / k;
            }
        }
        return new ScanImage(w, h, out);
    }

    private record Window(Picture base, int x0, int y0, int width, int height) implements Picture {

        @Override
        public int rgb(int x, int y) {
            return base.rgb(x0 + x, y0 + y);
        }

        @Override
        public boolean gray() {
            return false;
        }
    }

    private static final class Padded implements Picture {

        private final Picture scan;
        private final int border;
        private final int fill;
        private final int width;
        private final int height;

        Padded(Picture scan, int border, int fill) {
            long paddedWidth = scan.width() + 2L * border;
            long paddedHeight = scan.height() + 2L * border;
            if (paddedWidth > Integer.MAX_VALUE || paddedHeight > Integer.MAX_VALUE
                    || paddedWidth * paddedHeight > PhotoSplitter.MAX_PIXELS) {
                throw new IllegalArgumentException("Padded scan exceeds the " + PhotoSplitter.MAX_PIXELS + " pixel limit");
            }
            this.scan = scan;
            this.border = border;
            this.fill = fill;
            this.width = (int) paddedWidth;
            this.height = (int) paddedHeight;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public boolean gray() {
            return false;
        }

        @Override
        public int rgb(int x, int y) {
            int sx = x - border;
            int sy = y - border;
            return sx < 0 || sy < 0 || sx >= scan.width() || sy >= scan.height() ? fill : scan.rgb(sx, sy);
        }
    }
}
