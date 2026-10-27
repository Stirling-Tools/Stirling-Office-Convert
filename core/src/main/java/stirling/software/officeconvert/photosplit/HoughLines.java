package stirling.software.officeconvert.photosplit;

import java.util.Arrays;

final class HoughLines {

    private static final int ANGLES = 180;

    private HoughLines() {}

    static float[] thetas(byte[] edges, int w, int h, int threshold) {
        double step = Math.PI / ANGLES;
        float[] sin = new float[ANGLES];
        float[] cos = new float[ANGLES];
        float ang = 0f;
        for (int n = 0; n < ANGLES; n++) {
            sin[n] = (float) Math.sin(ang);
            cos[n] = (float) Math.cos(ang);
            ang += (float) step;
        }
        int numrho = 2 * (w + h) + 1;
        int stride = numrho + 2;
        int offset = (numrho - 1) / 2;
        int[] accum = new int[(ANGLES + 2) * stride];
        for (int i = 0; i < h; i++) {
            for (int j = 0; j < w; j++) {
                if (edges[i * w + j] == 0) {
                    continue;
                }
                for (int n = 0; n < ANGLES; n++) {
                    int r = (int) Math.rint(j * cos[n] + i * sin[n]) + offset;
                    accum[(n + 1) * stride + r + 1]++;
                }
            }
        }
        float[] found = new float[16];
        int count = 0;
        for (int n = 0; n < ANGLES; n++) {
            for (int r = 0; r < numrho; r++) {
                PhotoSplitter.checkpoint();
                int base = (n + 1) * stride + r + 1;
                int v = accum[base];
                if (v > threshold && v > accum[base - 1] && v >= accum[base + 1] && v > accum[base - stride]
                        && v >= accum[base + stride]) {
                    if (count == found.length) {
                        found = Arrays.copyOf(found, count * 2);
                    }
                    found[count++] = (float) (n * step);
                }
            }
        }
        return Arrays.copyOf(found, count);
    }
}
