package stirling.software.officeconvert.topdf.doc6;

import java.io.ByteArrayOutputStream;

/** The Word 6 structures whose layout Word 97 widened: borders, autonumber descriptors, outline lists and table
 * cell definitions. */
final class Structures {

    private static final int ANLV = 16;

    private Structures() {}

    /** A Word 6 BRC (two bytes) as Word 97's BRC80 (four bytes). */
    static byte[] brc(byte[] g, int at) {
        if (at + 1 >= g.length) {
            return new byte[4];
        }
        int b0 = g[at] & 0xFF;
        int b1 = g[at + 1] & 0xFF;
        int width = b0 & 0x07;
        int type = (b0 & 0x18) >> 3;
        boolean shadow = (b0 & 0x20) != 0;
        int ico = (b0 & 0xC0) >> 6 | (b1 & 0x07) << 2;
        int space = b1 >> 3;
        if (width > 5) {
            type = width;
            width = 1;
        }
        return new byte[] {(byte) (width * 6), (byte) type, (byte) ico, (byte) (space & 0x1F | (shadow ? 0x20 : 0))};
    }

    /** A Word 1/2 style BRC10 as BRC80: its outer line, doubled when it has a second one. */
    static byte[] brc10(byte[] g, int at) {
        if (at + 1 >= g.length) {
            return new byte[4];
        }
        int v = (g[at] & 0xFF) | (g[at + 1] & 0xFF) << 8;
        int line2 = v & 0x07;
        int line1 = v >> 6 & 0x07;
        int space = v >> 9 & 0x1F;
        if (line1 == 0 && line2 == 0) {
            return new byte[4];
        }
        int type = line2 > 0 ? 3 : 1;
        return new byte[] {(byte) (Math.max(1, line1) * 6), (byte) type, 0, (byte) (space & 0x1F)};
    }

    /** A Word 6 ANLD (52 bytes, 8-bit text) as Word 97's (84 bytes, 16-bit text). */
    static byte[] anld(byte[] g, int at, int len) {
        byte[] out = new byte[84];
        int n = Math.min(len, ANLV + 4);
        System.arraycopy(g, at, out, 0, Math.max(0, Math.min(n, g.length - at)));
        for (int i = 0; i < 32 && ANLV + 4 + i < len && at + ANLV + 4 + i < g.length; i++) {
            out[20 + 2 * i] = g[at + ANLV + 4 + i];
        }
        return out;
    }

    /** A Word 6 OLST (64 bytes of 8-bit text) as Word 97's (32 16-bit characters). */
    static byte[] olst(byte[] g, int at, int len) {
        byte[] out = new byte[212];
        int head = 9 * ANLV + 4;
        System.arraycopy(g, at, out, 0, Math.max(0, Math.min(Math.min(len, head), g.length - at)));
        for (int i = 0; i < 32 && head + i < len && at + head + i < g.length; i++) {
            out[head + 2 * i] = g[at + head + i];
        }
        return out;
    }

    /** A Word 6 sprmTDefTable operand (cell edges, then 10-byte TCs) as Word 97's (20-byte TCs). */
    static byte[] defTable(byte[] g, int at, int len, boolean brc10) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (len < 1) {
            return out.toByteArray();
        }
        int cells = g[at] & 0xFF;
        int edges = 2 * (cells + 1);
        if (1 + edges > len) {
            return out.toByteArray();
        }
        out.write(cells);
        out.write(g, at + 1, edges);
        int tc = at + 1 + edges;
        int end = at + len;
        for (int c = 0; c < cells && tc + 10 <= end; c++, tc += 10) {
            out.write(g[tc]);
            out.write(g[tc + 1]);
            out.write(0);
            out.write(0);
            for (int k = 0; k < 4; k++) {
                out.writeBytes(brc10 ? brc10(g, tc + 2 + 2 * k) : brc(g, tc + 2 + 2 * k));
            }
        }
        return out.toByteArray();
    }
}
