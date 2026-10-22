package stirling.software.officeconvert.topdf.crypt;

import java.util.Arrays;

public final class OpenOfficeSha1 {

    private OpenOfficeSha1() {}

    public static byte[] digest(byte[] message) {
        int tail = message.length % 64;
        if (tail < 52 || tail > 55) {
            return null;
        }
        int blocks = message.length / 64 + 2;
        byte[] padded = Arrays.copyOf(message, blocks * 64);
        padded[message.length] = (byte) 0x80;
        long bits = (long) message.length * 8;
        for (int i = 0; i < 8; i++) {
            padded[padded.length - 1 - i] = (byte) (bits >>> (8 * i));
        }
        return compress(padded);
    }

    static byte[] compress(byte[] padded) {
        int blocks = padded.length / 64;
        int[] h = {0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476, 0xC3D2E1F0};
        int[] w = new int[80];
        for (int block = 0; block < blocks; block++) {
            for (int t = 0; t < 16; t++) {
                int o = block * 64 + t * 4;
                w[t] = (padded[o] & 0xff) << 24 | (padded[o + 1] & 0xff) << 16 | (padded[o + 2] & 0xff) << 8
                        | padded[o + 3] & 0xff;
            }
            for (int t = 16; t < 80; t++) {
                w[t] = Integer.rotateLeft(w[t - 3] ^ w[t - 8] ^ w[t - 14] ^ w[t - 16], 1);
            }
            int a = h[0];
            int b = h[1];
            int c = h[2];
            int d = h[3];
            int e = h[4];
            for (int t = 0; t < 80; t++) {
                int f = t < 20 ? (b & c | ~b & d) + 0x5A827999 : t < 40 ? (b ^ c ^ d) + 0x6ED9EBA1
                        : t < 60 ? (b & c | b & d | c & d) + 0x8F1BBCDC : (b ^ c ^ d) + 0xCA62C1D6;
                int next = Integer.rotateLeft(a, 5) + f + e + w[t];
                e = d;
                d = c;
                c = Integer.rotateLeft(b, 30);
                b = a;
                a = next;
            }
            h[0] += a;
            h[1] += b;
            h[2] += c;
            h[3] += d;
            h[4] += e;
        }
        byte[] out = new byte[20];
        for (int i = 0; i < 20; i++) {
            out[i] = (byte) (h[i / 4] >>> (24 - 8 * (i % 4)));
        }
        return out;
    }
}
