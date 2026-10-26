package stirling.software.officeconvert.photosplit;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

final class Crop {

    private static final int STEPS = 64;
    private static final int BITS = 10;
    private static final int[][] WEIGHTS = weights();

    private Crop() {}

    static BufferedImage of(Source scan, RotatedRect rect, double levelAngle) {
        int w = Math.max(1, (int) Math.round(rect.width()));
        int h = Math.max(1, (int) Math.round(rect.height()));
        boolean gray = scan.gray();
        if (Math.abs(rect.angle()) < levelAngle) {
            int x0 = (int) Math.round(rect.cx() - w / 2.0);
            int y0 = (int) Math.round(rect.cy() - h / 2.0);
            int x1 = Math.min(scan.width(), x0 + w);
            int y1 = Math.min(scan.height(), y0 + h);
            x0 = Math.max(0, x0);
            y0 = Math.max(0, y0);
            int cw = Math.max(1, x1 - x0);
            int ch = Math.max(1, y1 - y0);
            BufferedImage out = image(cw, ch, gray);
            byte[] data = ((DataBufferByte) out.getRaster().getDataBuffer()).getData();
            for (int y = 0; y < ch; y++) {
                for (int x = 0; x < cw; x++) {
                    put(data, y * cw + x, scan.rgb(Math.min(x0 + x, scan.width() - 1), Math.min(y0 + y, scan.height() - 1)), gray);
                }
            }
            return out;
        }
        double c = Math.cos(Math.toRadians(rect.angle()));
        double s = Math.sin(Math.toRadians(rect.angle()));
        BufferedImage out = image(w, h, gray);
        byte[] data = ((DataBufferByte) out.getRaster().getDataBuffer()).getData();
        int[] px = new int[16];
        for (int v = 0; v < h; v++) {
            double dv = v + 0.5 - h / 2.0;
            double rowX = rect.cx() - (w / 2.0 - 0.5) * c - dv * s - 0.5;
            double rowY = rect.cy() - (w / 2.0 - 0.5) * s + dv * c - 0.5;
            for (int u = 0; u < w; u++) {
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
                put(data, v * w + u, cubic(px, WEIGHTS[fx], WEIGHTS[fy]), gray);
            }
        }
        return out;
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
