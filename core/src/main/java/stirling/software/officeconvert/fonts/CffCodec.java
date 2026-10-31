package stirling.software.officeconvert.fonts;

import java.io.ByteArrayOutputStream;
import java.util.List;

public final class CffCodec {

    private CffCodec() {}

    public static byte[] index(List<byte[]> items) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(items.size() >> 8);
        o.write(items.size());
        if (items.isEmpty()) {
            return o.toByteArray();
        }
        long total = 1;
        for (byte[] b : items) {
            total += b.length;
        }
        int offSize = total < 1 << 8 ? 1 : total < 1 << 16 ? 2 : total < 1 << 24 ? 3 : 4;
        o.write(offSize);
        long off = 1;
        for (int i = 0; i <= items.size(); i++) {
            for (int k = offSize - 1; k >= 0; k--) {
                o.write((int) (off >> (8 * k)));
            }
            if (i < items.size()) {
                off += items.get(i).length;
            }
        }
        for (byte[] b : items) {
            o.writeBytes(b);
        }
        return o.toByteArray();
    }

    public static byte[] int5(int v) {
        return new byte[] {29, (byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.writeBytes(p);
        }
        return o.toByteArray();
    }

    public static void writeShortInt(ByteArrayOutputStream out, int v) {
        if (v >= -107 && v <= 107) {
            out.write(v + 139);
        } else if (v >= 108 && v <= 1131) {
            out.write((v - 108 >> 8) + 247);
            out.write(v - 108);
        } else if (v >= -1131 && v <= -108) {
            out.write((-v - 108 >> 8) + 251);
            out.write(-v - 108);
        } else {
            out.write(28);
            out.write(v >> 8);
            out.write(v);
        }
    }

    public static void writeFixed(ByteArrayOutputStream out, int fixed16) {
        out.write(255);
        out.write(fixed16 >> 24);
        out.write(fixed16 >> 16);
        out.write(fixed16 >> 8);
        out.write(fixed16);
    }

    public static int twoByteInt(int b0, int b1) {
        return b0 <= 250 ? (b0 - 247) * 256 + b1 + 108 : -(b0 - 251) * 256 - b1 - 108;
    }

    public static int subrBias(int count) {
        return count < 1240 ? 107 : count < 33900 ? 1131 : 32768;
    }

    public static int dictOperand(byte[] d, int[] p, int end) {
        int b = d[p[0]] & 0xFF;
        if (b == 30) {
            p[0]++;
            while (p[0] < end) {
                int x = d[p[0]++] & 0xFF;
                if ((x & 0x0F) == 0x0F || (x & 0xF0) == 0xF0) {
                    break;
                }
            }
            return 0;
        }
        if (b == 28) {
            int v = (short) ((d[p[0] + 1] & 0xFF) << 8 | d[p[0] + 2] & 0xFF);
            p[0] += 3;
            return v;
        }
        if (b == 29) {
            int v = (d[p[0] + 1] & 0xFF) << 24 | (d[p[0] + 2] & 0xFF) << 16 | (d[p[0] + 3] & 0xFF) << 8
                    | d[p[0] + 4] & 0xFF;
            p[0] += 5;
            return v;
        }
        if (b >= 32 && b <= 246) {
            p[0]++;
            return b - 139;
        }
        if (b >= 247 && b <= 254) {
            int v = twoByteInt(b, d[p[0] + 1] & 0xFF);
            p[0] += 2;
            return v;
        }
        throw new IllegalArgumentException("bad DICT operand " + b);
    }
}
