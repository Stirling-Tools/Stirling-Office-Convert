package stirling.software.officeconvert.photosplit;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

final class Crop {

    private static final int STEPS = 64;
    private static final int BITS = 10;
    private static final int[][] WEIGHTS = weights();

    private Crop() {}

    static BufferedImage of(Source scan, RotatedRect rect, double levelAngle) {
        Picture view = view(scan, rect, levelAngle);
        int w = view.width();
        int h = view.height();
        boolean gray = view.gray();
        BufferedImage out = image(w, h, gray);
        byte[] data = ((DataBufferByte) out.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < h; y++) {
            PhotoSplitter.checkpoint();
            for (int x = 0; x < w; x++) {
                put(data, y * w + x, view.rgb(x, y), gray);
            }
        }
        return out;
    }

    static Picture view(Source scan, RotatedRect rect, double levelAngle) {
        int w = Math.max(1, (int) Math.round(rect.width()));
        int h = Math.max(1, (int) Math.round(rect.height()));
        if (Math.abs(rect.angle()) < levelAngle) {
            int x0 = (int) Math.round(rect.cx() - w / 2.0);
            int y0 = (int) Math.round(rect.cy() - h / 2.0);
            int x1 = Math.min(scan.width(), x0 + w);
            int y1 = Math.min(scan.height(), y0 + h);
            return new Level(scan, Math.max(0, x0), Math.max(0, y0), Math.max(1, x1 - Math.max(0, x0)),
                    Math.max(1, y1 - Math.max(0, y0)));
        }
        return new Turned(scan, rect, w, h);
    }

    private record Level(Source scan, int x0, int y0, int width, int height) implements Picture {

        @Override
        public int rgb(int x, int y) {
            return pixel(scan.rgb(Math.min(x0 + x, scan.width() - 1), Math.min(y0 + y, scan.height() - 1)), scan.gray());
        }

        @Override
        public boolean gray() {
            return scan.gray();
        }
    }

    private static final class Turned implements Picture {

        private final Source scan;
        private final RotatedRect rect;
        private final int width;
        private final int height;
        private final double c;
        private final double s;
        private final int[] px = new int[16];

        Turned(Source scan, RotatedRect rect, int width, int height) {
            this.scan = scan;
            this.rect = rect;
            this.width = width;
            this.height = height;
            this.c = Math.cos(Math.toRadians(rect.angle()));
            this.s = Math.sin(Math.toRadians(rect.angle()));
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
            return scan.gray();
        }

        @Override
        public int rgb(int u, int v) {
            int w = width;
            int h = height;
            double dv = v + 0.5 - h / 2.0;
            double rowX = rect.cx() - (w / 2.0 - 0.5) * c - dv * s - 0.5;
            double rowY = rect.cy() - (w / 2.0 - 0.5) * s + dv * c - 0.5;
            double sx = rowX + u * c;
            double sy = rowY + u * s;
            int bx = (int) Math.floor(sx);
            int by = (int) Math.floor(sy);
            int fx = (int) ((sx - bx) * STEPS + 0.5);
            int fy = (int) ((sy - by) * STEPS + 0.5);
            if (fx == STEPS) {
                fx = 0;
                bx++;
            }
            if (fy == STEPS) {
                fy = 0;
                by++;
            }
            scan.taps(bx, by, px);
            return pixel(cubic(px, WEIGHTS[fx], WEIGHTS[fy]), scan.gray());
        }
    }

    private static int pixel(int rgb, boolean gray) {
        if (!gray) {
            return rgb;
        }
        int g = rgb >> 8 & 0xFF;
        return g << 16 | g << 8 | g;
    }

    private static int cubic(int[] px, int[] wx, int[] wy) {
        int r = 0;
        int g = 0;
        int b = 0;
        for (int i = 0; i < 4; i++) {
            int rr = 0;
            int gg = 0;
            int bb = 0;
            for (int j = 0; j < 4; j++) {
                int p = px[i * 4 + j];
                rr += (p >> 16 & 0xFF) * wx[j];
                gg += (p >> 8 & 0xFF) * wx[j];
                bb += (p & 0xFF) * wx[j];
            }
            r += rr * wy[i];
            g += gg * wy[i];
            b += bb * wy[i];
        }
        int half = 1 << (2 * BITS - 1);
        return channel((r + half) >> (2 * BITS)) << 16 | channel((g + half) >> (2 * BITS)) << 8 | channel((b + half) >> (2 * BITS));
    }

    private static int channel(int v) {
        return Math.clamp(v, 0, 255);
    }

    private static BufferedImage image(int w, int h, boolean gray) {
        return new BufferedImage(w, h, gray ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_3BYTE_BGR);
    }

    private static void put(byte[] data, int i, int rgb, boolean gray) {
        if (gray) {
            data[i] = (byte) (rgb >> 8 & 0xFF);
        } else {
            data[3 * i] = (byte) rgb;
            data[3 * i + 1] = (byte) (rgb >> 8);
            data[3 * i + 2] = (byte) (rgb >> 16);
        }
    }

    private static int[][] weights() {
        int[][] out = new int[STEPS][4];
        double a = -0.5;
        int one = 1 << BITS;
        for (int k = 0; k < STEPS; k++) {
            double t = (double) k / STEPS;
            double[] w = {
                ((a * (t + 1) - 5 * a) * (t + 1) + 8 * a) * (t + 1) - 4 * a,
                ((a + 2) * t - (a + 3)) * t * t + 1,
                ((a + 2) * (1 - t) - (a + 3)) * (1 - t) * (1 - t) + 1,
                0
            };
            w[3] = 1 - w[0] - w[1] - w[2];
            int sum = 0;
            for (int j = 0; j < 3; j++) {
                out[k][j] = (int) Math.round(w[j] * one);
                sum += out[k][j];
            }
            out[k][3] = one - sum;
        }
        return out;
    }
}
