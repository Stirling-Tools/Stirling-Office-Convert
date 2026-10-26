package stirling.software.officeconvert.lineart;

import java.io.ByteArrayOutputStream;

final class Group4 {

    private static final String[] WHITE = {"00110101", "000111", "0111", "1000", "1011", "1100", "1110", "1111",
        "10011", "10100", "00111", "01000", "001000", "000011", "110100", "110101", "101010", "101011", "0100111",
        "0001100", "0001000", "0010111", "0000011", "0000100", "0101000", "0101011", "0010011", "0100100", "0011000",
        "00000010", "00000011", "00011010", "00011011", "00010010", "00010011", "00010100", "00010101", "00010110",
        "00010111", "00101000", "00101001", "00101010", "00101011", "00101100", "00101101", "00000100", "00000101",
        "00001010", "00001011", "01010010", "01010011", "01010100", "01010101", "00100100", "00100101", "01011000",
        "01011001", "01011010", "01011011", "01001010", "01001011", "00110010", "00110011", "00110100"};

    private static final String[] WHITE_MAKEUP = {"11011", "10010", "010111", "0110111", "00110110", "00110111",
        "01100100", "01100101", "01101000", "01100111", "011001100", "011001101", "011010010", "011010011",
        "011010100", "011010101", "011010110", "011010111", "011011000", "011011001", "011011010", "011011011",
        "010011000", "010011001", "010011010", "011000", "010011011"};

    private static final String[] BLACK = {"0000110111", "010", "11", "10", "011", "0011", "0010", "00011", "000101",
        "000100", "0000100", "0000101", "0000111", "00000100", "00000111", "000011000", "0000010111", "0000011000",
        "0000001000", "00001100111", "00001101000", "00001101100", "00000110111", "00000101000", "00000010111",
        "00000011000", "000011001010", "000011001011", "000011001100", "000011001101", "000001101000",
        "000001101001", "000001101010", "000001101011", "000011010010", "000011010011", "000011010100",
        "000011010101", "000011010110", "000011010111", "000001101100", "000001101101", "000011011010",
        "000011011011", "000001010100", "000001010101", "000001010110", "000001010111", "000001100100",
        "000001100101", "000001010010", "000001010011", "000000100100", "000000110111", "000000111000",
        "000000100111", "000000101000", "000001011000", "000001011001", "000000101011", "000000101100",
        "000001011010", "000001100110", "000001100111"};

    private static final String[] BLACK_MAKEUP = {"0000001111", "000011001000", "000011001001", "000001011011",
        "000000110011", "000000110100", "000000110101", "0000001101100", "0000001101101", "0000001001010",
        "0000001001011", "0000001001100", "0000001001101", "0000001110010", "0000001110011", "0000001110100",
        "0000001110101", "0000001110110", "0000001110111", "0000001010010", "0000001010011", "0000001010100",
        "0000001010101", "0000001011010", "0000001011011", "0000001100100", "0000001100101"};

    private static final String[] EXTENDED_MAKEUP = {"00000001000", "00000001100", "00000001101", "000000010010",
        "000000010011", "000000010100", "000000010101", "000000010110", "000000010111", "000000011100",
        "000000011101", "000000011110", "000000011111"};

    private static final String[] VERTICAL = {"0000011", "000011", "011", "1", "010", "000010", "0000010"};

    private static final int[][] WHITE_CODES = codes(WHITE);
    private static final int[][] WHITE_MAKEUP_CODES = codes(WHITE_MAKEUP);
    private static final int[][] BLACK_CODES = codes(BLACK);
    private static final int[][] BLACK_MAKEUP_CODES = codes(BLACK_MAKEUP);
    private static final int[][] EXTENDED_CODES = codes(EXTENDED_MAKEUP);
    private static final int[][] VERTICAL_CODES = codes(VERTICAL);

    private final ByteArrayOutputStream out;
    private int buffer;
    private int filled;

    private Group4(int expected) {
        out = new ByteArrayOutputStream(Math.max(64, expected));
    }

    private static int[][] codes(String[] bits) {
        int[][] codes = new int[bits.length][];
        for (int i = 0; i < bits.length; i++) {
            codes[i] = new int[] {Integer.parseInt(bits[i], 2), bits[i].length()};
        }
        return codes;
    }

    static byte[] encode(Bitmap bitmap) {
        int w = bitmap.width();
        int h = bitmap.height();
        int stride = bitmap.stride();
        byte[] bits = bitmap.packed();
        Group4 g = new Group4(bits.length / 10);
        byte[] reference = new byte[stride];
        byte[] coding = new byte[stride];
        for (int y = 0; y < h; y++) {
            System.arraycopy(bits, y * stride, coding, 0, stride);
            g.row(coding, reference, w);
            byte[] t = reference;
            reference = coding;
            coding = t;
        }
        g.put(1, 12);
        g.put(1, 12);
        return g.finish();
    }

    private void row(byte[] coding, byte[] reference, int w) {
        int a0 = 0;
        int a1 = pixel(coding, 0) != 0 ? 0 : find(coding, 0, w, 0);
        int b1 = pixel(reference, 0) != 0 ? 0 : find(reference, 0, w, 0);
        while (true) {
            int b2 = b1 >= w ? w : find(reference, b1, w, pixel(reference, b1));
            if (b2 >= a1) {
                int d = b1 - a1;
                if (d < -3 || d > 3) {
                    int a2 = a1 >= w ? w : find(coding, a1, w, pixel(coding, a1));
                    put(1, 3);
                    if (a0 + a1 == 0 || pixel(coding, a0) == 0) {
                        span(a1 - a0, false);
                        span(a2 - a1, true);
                    } else {
                        span(a1 - a0, true);
                        span(a2 - a1, false);
                    }
                    a0 = a2;
                } else {
                    put(VERTICAL_CODES[d + 3][0], VERTICAL_CODES[d + 3][1]);
                    a0 = a1;
                }
            } else {
                put(1, 4);
                a0 = b2;
            }
            if (a0 >= w) {
                return;
            }
            int colour = pixel(coding, a0);
            a1 = find(coding, a0, w, colour);
            b1 = find(reference, a0, w, colour ^ 1);
            b1 = find(reference, b1, w, colour);
        }
    }

    private static int pixel(byte[] row, int x) {
        return (row[x >>> 3] >>> (7 - (x & 7))) & 1;
    }

    private static int find(byte[] row, int start, int end, int colour) {
        int x = start;
        while (x < end) {
            if ((x & 7) == 0 && x + 8 <= end) {
                int b = row[x >>> 3] & 0xFF;
                if (b == (colour == 0 ? 0 : 0xFF)) {
                    x += 8;
                    continue;
                }
            }
            if (pixel(row, x) != colour) {
                return x;
            }
            x++;
        }
        return end;
    }

    private void span(int run, boolean black) {
        int[][] terminating = black ? BLACK_CODES : WHITE_CODES;
        int[][] makeup = black ? BLACK_MAKEUP_CODES : WHITE_MAKEUP_CODES;
        while (run >= 2560) {
            put(EXTENDED_CODES[12][0], EXTENDED_CODES[12][1]);
            run -= 2560;
        }
        if (run >= 64) {
            int m = run / 64;
            if (m <= 27) {
                put(makeup[m - 1][0], makeup[m - 1][1]);
            } else {
                put(EXTENDED_CODES[m - 28][0], EXTENDED_CODES[m - 28][1]);
            }
            run -= m * 64;
        }
        put(terminating[run][0], terminating[run][1]);
    }

    private void put(int code, int length) {
        for (int i = length - 1; i >= 0; i--) {
            buffer = buffer << 1 | ((code >>> i) & 1);
            if (++filled == 8) {
                out.write(buffer);
                buffer = 0;
                filled = 0;
            }
        }
    }

    private byte[] finish() {
        if (filled > 0) {
            out.write(buffer << (8 - filled));
        }
        return out.toByteArray();
    }
}
