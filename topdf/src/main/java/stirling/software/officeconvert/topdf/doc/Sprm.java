package stirling.software.officeconvert.topdf.doc;

import java.util.ArrayList;
import java.util.List;

record Sprm(int opcode, byte[] data, int at, int length) {

    static final int MAX = 4096;

    static List<Sprm> parse(byte[] grpprl, int offset) {
        List<Sprm> out = new ArrayList<>();
        if (grpprl == null) {
            return out;
        }
        int i = offset;
        while (i + 2 <= grpprl.length && out.size() < MAX) {
            int op = u16(grpprl, i);
            i += 2;
            int len;
            int start = i;
            switch (op >>> 13) {
                case 0, 1 -> len = 1;
                case 2, 4, 5 -> len = 2;
                case 3 -> len = 4;
                case 7 -> len = 3;
                default -> {
                    if (op == 0xD608 || op == 0xD606) {
                        if (i + 2 > grpprl.length) {
                            return out;
                        }
                        len = u16(grpprl, i) + 1;
                    } else if (op == 0xC615) {
                        if (i >= grpprl.length) {
                            return out;
                        }
                        int cb = grpprl[i] & 0xFF;
                        len = cb == 255 ? tabsLength(grpprl, i) : cb + 1;
                    } else {
                        if (i >= grpprl.length) {
                            return out;
                        }
                        len = (grpprl[i] & 0xFF) + 1;
                    }
                }
            }
            if (start + len > grpprl.length) {
                return out;
            }
            out.add(new Sprm(op, grpprl, start, len));
            i = start + len;
        }
        return out;
    }

    private static int tabsLength(byte[] g, int at) {
        int i = at + 1;
        if (i >= g.length) {
            return 1;
        }
        int del = g[i] & 0xFF;
        i += 1 + del * 4;
        if (i >= g.length) {
            return g.length - at;
        }
        int add = g[i] & 0xFF;
        return i + 1 + add * 3 - at;
    }

    static byte[] encode(List<Sprm> sprms) {
        int n = 0;
        for (Sprm s : sprms) {
            n += 2 + s.length;
        }
        byte[] out = new byte[n];
        int at = 0;
        for (Sprm s : sprms) {
            out[at] = (byte) s.opcode;
            out[at + 1] = (byte) (s.opcode >> 8);
            System.arraycopy(s.data, s.at, out, at + 2, s.length);
            at += 2 + s.length;
        }
        return out;
    }

    static Sprm find(List<Sprm> sprms, int opcode) {
        Sprm found = null;
        for (Sprm s : sprms) {
            if (s.opcode == opcode) {
                found = s;
            }
        }
        return found;
    }

    boolean variable() {
        return opcode >>> 13 == 6;
    }

    int payload() {
        return variable() ? at + 1 : at;
    }

    int payloadLength() {
        return variable() ? length - 1 : length;
    }

    int u8() {
        return data[at] & 0xFF;
    }

    int s16() {
        return (short) u16(data, at);
    }

    int u16() {
        return u16(data, at);
    }

    static int u16(byte[] d, int i) {
        return (d[i] & 0xFF) | (d[i + 1] & 0xFF) << 8;
    }

    static int s32(byte[] d, int i) {
        return (d[i] & 0xFF) | (d[i + 1] & 0xFF) << 8 | (d[i + 2] & 0xFF) << 16 | (d[i + 3] & 0xFF) << 24;
    }
}
