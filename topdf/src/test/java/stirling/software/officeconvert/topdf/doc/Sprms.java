package stirling.software.officeconvert.topdf.doc;

final class Sprms {

    private Sprms() {}

    static byte[] op(int opcode, int... operand) {
        byte[] b = new byte[2 + operand.length];
        b[0] = (byte) opcode;
        b[1] = (byte) (opcode >> 8);
        for (int i = 0; i < operand.length; i++) {
            b[2 + i] = (byte) operand[i];
        }
        return b;
    }

    static byte[] u8(int opcode, int v) {
        return op(opcode, v);
    }

    static byte[] u16(int opcode, int v) {
        return op(opcode, v & 0xFF, (v >> 8) & 0xFF);
    }

    static byte[] u32(int opcode, int v) {
        return op(opcode, v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >>> 24) & 0xFF);
    }

    static byte[] var(int opcode, int... payload) {
        int[] all = new int[payload.length + 1];
        all[0] = payload.length;
        System.arraycopy(payload, 0, all, 1, payload.length);
        return op(opcode, all);
    }

    static byte[] special() {
        return u8(0x0855, 1);
    }

    static byte[] bold() {
        return u8(0x0835, 1);
    }

    static byte[] italic() {
        return u8(0x0836, 1);
    }

    static byte[] size(int halfPoints) {
        return u16(0x4A43, halfPoints);
    }

    static byte[] font(int ftc) {
        return WordFixture.concat(u16(0x4A4F, ftc), u16(0x4A51, ftc));
    }

    static byte[] color(int rgb) {
        return u32(0x6870, (rgb >> 16) & 0xFF | (rgb & 0xFF00) | (rgb & 0xFF) << 16);
    }

    static byte[] jc(int jc) {
        return u8(0x2461, jc);
    }

    static byte[] inTable() {
        return u8(0x2416, 1);
    }

    static byte[] rowEnd() {
        return WordFixture.concat(u8(0x2416, 1), u8(0x2417, 1));
    }

    static byte[] defTable(int[] centers, int[][] tc) {
        int n = centers.length - 1;
        int len = 1 + centers.length * 2 + n * 20;
        int[] operand = new int[2 + len];
        operand[0] = (len + 1) & 0xFF;
        operand[1] = (len + 1) >> 8;
        operand[2] = n;
        int at = 3;
        for (int c : centers) {
            operand[at++] = c & 0xFF;
            operand[at++] = (c >> 8) & 0xFF;
        }
        for (int i = 0; i < n; i++) {
            int[] cell = tc == null ? new int[0] : tc[i];
            for (int k = 0; k < 20; k++) {
                operand[at++] = k < cell.length ? cell[k] : 0;
            }
        }
        return op(0xD608, operand);
    }
}
