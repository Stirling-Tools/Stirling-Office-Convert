package stirling.software.officeconvert.topdf.crypt;

import java.util.Arrays;

final class Blake2b {

    private static final long[] IV = {0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL,
        0xa54ff53a5f1d36f1L, 0x510e527fade682d1L, 0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L};

    private static final byte[][] SIGMA = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
        {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
        {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4},
        {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
        {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13},
        {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
        {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11},
        {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
        {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5},
        {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0}};

    private final long[] h = new long[8];

    private final byte[] buffer = new byte[128];

    private final long[] m = new long[16];

    private final long[] v = new long[16];

    private final int length;

    private int filled;

    private long counter;

    Blake2b(int length) {
        if (length < 1 || length > 64) {
            throw new IllegalArgumentException("BLAKE2b digests are 1 to 64 bytes");
        }
        this.length = length;
        System.arraycopy(IV, 0, h, 0, 8);
        h[0] ^= 0x01010000L ^ length;
    }

    Blake2b update(byte[] data) {
        return update(data, 0, data.length);
    }

    Blake2b update(byte[] data, int off, int len) {
        while (len > 0) {
            if (filled == 128) {
                counter += 128;
                compress(false);
                filled = 0;
            }
            int n = Math.min(len, 128 - filled);
            System.arraycopy(data, off, buffer, filled, n);
            filled += n;
            off += n;
            len -= n;
        }
        return this;
    }

    Blake2b updateInt(int value) {
        return update(new byte[] {(byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)});
    }

    byte[] digest() {
        counter += filled;
        Arrays.fill(buffer, filled, 128, (byte) 0);
        compress(true);
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (h[i >> 3] >>> (8 * (i & 7)));
        }
        return out;
    }

    private void compress(boolean last) {
        for (int i = 0; i < 16; i++) {
            m[i] = le64(buffer, i * 8);
        }
        System.arraycopy(h, 0, v, 0, 8);
        System.arraycopy(IV, 0, v, 8, 8);
        v[12] ^= counter;
        if (last) {
            v[14] = ~v[14];
        }
        for (int r = 0; r < 12; r++) {
            byte[] s = SIGMA[r % 10];
            mix(0, 4, 8, 12, m[s[0]], m[s[1]]);
            mix(1, 5, 9, 13, m[s[2]], m[s[3]]);
            mix(2, 6, 10, 14, m[s[4]], m[s[5]]);
            mix(3, 7, 11, 15, m[s[6]], m[s[7]]);
            mix(0, 5, 10, 15, m[s[8]], m[s[9]]);
            mix(1, 6, 11, 12, m[s[10]], m[s[11]]);
            mix(2, 7, 8, 13, m[s[12]], m[s[13]]);
            mix(3, 4, 9, 14, m[s[14]], m[s[15]]);
        }
        for (int i = 0; i < 8; i++) {
            h[i] ^= v[i] ^ v[i + 8];
        }
    }

    private void mix(int a, int b, int c, int d, long x, long y) {
        v[a] += v[b] + x;
        v[d] = Long.rotateRight(v[d] ^ v[a], 32);
        v[c] += v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 24);
        v[a] += v[b] + y;
        v[d] = Long.rotateRight(v[d] ^ v[a], 16);
        v[c] += v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 63);
    }

    static long le64(byte[] b, int off) {
        long r = 0;
        for (int i = 7; i >= 0; i--) {
            r = r << 8 | (b[off + i] & 0xffL);
        }
        return r;
    }
}
