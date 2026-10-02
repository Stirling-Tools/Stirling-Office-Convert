package stirling.software.officeconvert.jpx;

final class Dequantize {

    private Dequantize() {}

    static void reversible(int[] data, int w, int h, int roi, int[] out, int at, int stride) {
        for (int y = 0; y < h; y++) {
            int o = at + y * stride;
            int s = y * w;
            for (int x = 0; x < w; x++) {
                out[o + x] = unshift(data[s + x], roi) / 2;
            }
        }
    }

    static void irreversible(int[] data, int w, int h, int roi, float step, float[] out, int at, int stride) {
        for (int y = 0; y < h; y++) {
            int o = at + y * stride;
            int s = y * w;
            for (int x = 0; x < w; x++) {
                out[o + x] = unshift(data[s + x], roi) * step;
            }
        }
    }

    private static int unshift(int v, int roi) {
        if (roi == 0) {
            return v;
        }
        int mag = Math.abs(v);
        if (roi >= 30) {
            return 0;
        }
        if (mag >= 1 << (roi + 1)) {
            mag >>= roi;
            return v < 0 ? -mag : mag;
        }
        return v;
    }
}
