package stirling.software.officeconvert.topdf.psd;

final class Samples {

    private Samples() {}

    static void interleave8(byte[][] in, int n, int width, boolean invert, byte[] out) {
        int mask = invert ? 0xFF : 0;
        if (n == 1) {
            byte[] c = in[0];
            for (int x = 0; x < width; x++) {
                out[x] = (byte) (c[x] ^ mask);
            }
            return;
        }
        if (n == 3) {
            byte[] c0 = in[0];
            byte[] c1 = in[1];
            byte[] c2 = in[2];
            for (int x = 0, k = 0; x < width; x++, k += 3) {
                out[k] = (byte) (c0[x] ^ mask);
                out[k + 1] = (byte) (c1[x] ^ mask);
                out[k + 2] = (byte) (c2[x] ^ mask);
            }
            return;
        }
        if (n == 4) {
            byte[] c0 = in[0];
            byte[] c1 = in[1];
            byte[] c2 = in[2];
            byte[] c3 = in[3];
            for (int x = 0, k = 0; x < width; x++, k += 4) {
                out[k] = (byte) (c0[x] ^ mask);
                out[k + 1] = (byte) (c1[x] ^ mask);
                out[k + 2] = (byte) (c2[x] ^ mask);
                out[k + 3] = (byte) (c3[x] ^ mask);
            }
            return;
        }
        for (int i = 0; i < n; i++) {
            byte[] c = in[i];
            for (int x = 0, k = i; x < width; x++, k += n) {
                out[k] = (byte) (c[x] ^ mask);
            }
        }
    }

    static void interleave16(byte[][] in, int n, int width, boolean invert, byte[] out) {
        int mask = invert ? 0xFF : 0;
        for (int i = 0; i < n; i++) {
            byte[] c = in[i];
            for (int x = 0, k = 2 * i; x < width; x++, k += 2 * n) {
                out[k] = (byte) (c[2 * x] ^ mask);
                out[k + 1] = (byte) (c[2 * x + 1] ^ mask);
            }
        }
    }

    static float float32(byte[] row, int x) {
        int b = 4 * x;
        return Float.intBitsToFloat((row[b] & 0xFF) << 24 | (row[b + 1] & 0xFF) << 16 | (row[b + 2] & 0xFF) << 8
                | row[b + 3] & 0xFF);
    }

    static void interleave32(byte[][] in, int n, int width, boolean encode, byte[] out) {
        for (int i = 0; i < n; i++) {
            byte[] c = in[i];
            for (int x = 0, k = 2 * i; x < width; x++, k += 2 * n) {
                float f = float32(c, x);
                float v = encode ? Colors.encode(Float.isNaN(f) ? 0 : f) : Colors.clamp(Float.isNaN(f) ? 0 : f);
                int s = Math.round(v * 65535);
                out[k] = (byte) (s >> 8);
                out[k + 1] = (byte) s;
            }
        }
    }

    static void unmatte8(byte[][] in, int n, byte[] alpha, int width, int[] white) {
        for (int x = 0; x < width; x++) {
            int a = alpha[x] & 0xFF;
            if (a == 255) {
                continue;
            }
            for (int i = 0; i < n; i++) {
                int w = white[i];
                if (a == 0) {
                    in[i][x] = (byte) w;
                    continue;
                }
                int v = in[i][x] & 0xFF;
                int u = Math.round((v - (255 - a) * w / 255f) * 255f / a);
                in[i][x] = (byte) (u < 0 ? 0 : u > 255 ? 255 : u);
            }
        }
    }

    static void unmatte16(byte[][] in, int n, byte[] alpha, int width, int[] white) {
        for (int x = 0; x < width; x++) {
            int a = (alpha[2 * x] & 0xFF) << 8 | alpha[2 * x + 1] & 0xFF;
            if (a == 65535) {
                continue;
            }
            for (int i = 0; i < n; i++) {
                int w = white[i] == 128 ? 32768 : white[i] * 257;
                int u;
                if (a == 0) {
                    u = w;
                } else {
                    int v = (in[i][2 * x] & 0xFF) << 8 | in[i][2 * x + 1] & 0xFF;
                    u = Math.round((v - (65535 - a) * (w / 65535f)) * 65535f / a);
                    u = u < 0 ? 0 : u > 65535 ? 65535 : u;
                }
                in[i][2 * x] = (byte) (u >> 8);
                in[i][2 * x + 1] = (byte) u;
            }
        }
    }

    static void unmatte32(byte[][] in, int n, byte[] alpha, int width, int[] white) {
        for (int x = 0; x < width; x++) {
            float a = float32(alpha, x);
            if (!(a < 1)) {
                continue;
            }
            for (int i = 0; i < n; i++) {
                float w = white[i] / 255f;
                float u = a <= 0 ? w : (float32(in[i], x) - (1 - a) * w) / a;
                int bits = Float.floatToRawIntBits(u);
                int b = 4 * x;
                in[i][b] = (byte) (bits >> 24);
                in[i][b + 1] = (byte) (bits >> 16);
                in[i][b + 2] = (byte) (bits >> 8);
                in[i][b + 3] = (byte) bits;
            }
        }
    }
}
