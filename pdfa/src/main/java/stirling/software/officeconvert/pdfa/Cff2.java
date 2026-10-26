package stirling.software.officeconvert.pdfa;

import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class Cff2 {

    private final ByteBuffer data;

    private final byte[][] charStrings;

    private final byte[][] globalSubrs;

    private final byte[][][] localSubrs;

    private final int[] fdSelect;

    private final int[] regionCounts;

    private final int[] defaultVsindex;

    private final List<Number> fontMatrix;

    Cff2(byte[] table) throws IOException {
        try {
            data = ByteBuffer.wrap(table);
            if ((table[0] & 0xFF) != 2) {
                throw new IOException("The CFF2 table has version " + (table[0] & 0xFF));
            }
            int headerSize = table[2] & 0xFF;
            int topLength = data.getShort(3) & 0xFFFF;
            Map<Integer, List<Number>> top = dict(headerSize, headerSize + topLength);
            int at = headerSize + topLength;
            globalSubrs = index(at);
            charStrings = index(offset(top, 17));
            if (charStrings.length < 1) {
                throw new IOException("The CFF2 table has no glyphs");
            }
            fontMatrix = top.get(0x0C07);
            regionCounts = top.containsKey(24) ? regions(offset(top, 24)) : new int[0];
            byte[][] fonts = index(offset(top, 0x0C24));
            localSubrs = new byte[fonts.length][][];
            defaultVsindex = new int[fonts.length];
            for (int i = 0; i < fonts.length; i++) {
                ByteBuffer f = ByteBuffer.wrap(fonts[i]);
                Map<Integer, List<Number>> fd = dict(f, 0, fonts[i].length);
                List<Number> priv = fd.get(18);
                if (priv == null || priv.size() < 2) {
                    localSubrs[i] = new byte[0][];
                    continue;
                }
                int size = priv.get(0).intValue();
                int start = priv.get(1).intValue();
                Map<Integer, List<Number>> p = dict(start, start + size);
                localSubrs[i] = p.containsKey(19) ? index(start + p.get(19).get(0).intValue()) : new byte[0][];
                defaultVsindex[i] = p.containsKey(22) ? p.get(22).get(0).intValue() : 0;
            }
            fdSelect = top.containsKey(0x0C25) ? fdSelect(offset(top, 0x0C25), charStrings.length)
                    : new int[charStrings.length];
        } catch (RuntimeException e) {
            throw new IOException("The CFF2 table cannot be read: " + e.getMessage(), e);
        }
    }

    int count() {
        return charStrings.length;
    }

    List<Number> fontMatrix() {
        return fontMatrix;
    }

    byte[] charString(int gid) {
        return charStrings[gid];
    }

    byte[][] globalSubrs() {
        return globalSubrs;
    }

    byte[][] localSubrs(int gid) {
        int fd = fdSelect[gid];
        return fd >= 0 && fd < localSubrs.length ? localSubrs[fd] : new byte[0][];
    }

    GeneralPath path(int gid) throws IOException {
        int fd = fdSelect[gid];
        return Type2Outline.path(charStrings[gid], globalSubrs, localSubrs(gid), regionCounts,
                fd >= 0 && fd < defaultVsindex.length ? defaultVsindex[fd] : 0, null);
    }

    private int offset(Map<Integer, List<Number>> dict, int op) throws IOException {
        List<Number> v = dict.get(op);
        if (v == null || v.isEmpty()) {
            throw new IOException("The CFF2 top dictionary lacks operator " + op);
        }
        return v.get(v.size() - 1).intValue();
    }

    private byte[][] index(int at) {
        long count = data.getInt(at) & 0xFFFFFFFFL;
        if (count == 0) {
            return new byte[0][];
        }
        if (count > data.capacity()) {
            throw new IllegalArgumentException("index count " + count);
        }
        int offSize = data.get(at + 4) & 0xFF;
        int offsets = at + 5;
        int base = offsets + (int) ((count + 1) * offSize) - 1;
        byte[][] out = new byte[(int) count][];
        for (int i = 0; i < count; i++) {
            int start = base + readOffset(offsets + i * offSize, offSize);
            int end = base + readOffset(offsets + (i + 1) * offSize, offSize);
            if (start > end || end > data.capacity()) {
                throw new IllegalArgumentException("index entry " + i);
            }
            out[i] = new byte[end - start];
            data.get(start, out[i]);
        }
        return out;
    }

    private int readOffset(int at, int size) {
        int v = 0;
        for (int i = 0; i < size; i++) {
            v = v << 8 | data.get(at + i) & 0xFF;
        }
        return v;
    }

    private Map<Integer, List<Number>> dict(int start, int end) {
        return dict(data, start, end);
    }

    private static Map<Integer, List<Number>> dict(ByteBuffer b, int start, int end) {
        Map<Integer, List<Number>> out = new HashMap<>();
        List<Number> operands = new ArrayList<>();
        int at = start;
        while (at < end) {
            int b0 = b.get(at) & 0xFF;
            if (b0 == 12) {
                out.put(0x0C00 | b.get(at + 1) & 0xFF, operands);
                operands = new ArrayList<>();
                at += 2;
            } else if (b0 == 23) {
                operands = new ArrayList<>();
                at++;
            } else if (b0 <= 24) {
                out.put(b0, operands);
                operands = new ArrayList<>();
                at++;
            } else if (b0 == 28) {
                operands.add((int) b.getShort(at + 1));
                at += 3;
            } else if (b0 == 29) {
                operands.add(b.getInt(at + 1));
                at += 5;
            } else if (b0 == 30) {
                StringBuilder s = new StringBuilder();
                at++;
                boolean done = false;
                while (!done && at < end) {
                    int v = b.get(at++) & 0xFF;
                    for (int nibble : new int[] {v >> 4, v & 0xF}) {
                        switch (nibble) {
                            case 0xA -> s.append('.');
                            case 0xB -> s.append('E');
                            case 0xC -> s.append("E-");
                            case 0xE -> s.append('-');
                            case 0xF -> done = true;
                            case 0xD -> {
                            }
                            default -> s.append((char) ('0' + nibble));
                        }
                        if (done) {
                            break;
                        }
                    }
                }
                double d;
                try {
                    d = Double.parseDouble(s.toString());
                } catch (NumberFormatException e) {
                    d = 0;
                }
                operands.add(d);
            } else if (b0 >= 32 && b0 <= 246) {
                operands.add(b0 - 139);
                at++;
            } else if (b0 >= 247 && b0 <= 250) {
                operands.add((b0 - 247) * 256 + (b.get(at + 1) & 0xFF) + 108);
                at += 2;
            } else if (b0 >= 251 && b0 <= 254) {
                operands.add(-(b0 - 251) * 256 - (b.get(at + 1) & 0xFF) - 108);
                at += 2;
            } else {
                at++;
            }
        }
        return out;
    }

    private int[] regions(int at) {
        int store = at + 2;
        int dataCount = data.getShort(store + 6) & 0xFFFF;
        int[] counts = new int[dataCount];
        for (int i = 0; i < dataCount; i++) {
            int item = store + data.getInt(store + 8 + 4 * i);
            counts[i] = data.getShort(item + 4) & 0xFFFF;
        }
        return counts;
    }

    private int[] fdSelect(int at, int glyphs) {
        int[] out = new int[glyphs];
        int format = data.get(at) & 0xFF;
        if (format == 0) {
            for (int g = 0; g < glyphs; g++) {
                out[g] = data.get(at + 1 + g) & 0xFF;
            }
        } else if (format == 3 || format == 4) {
            boolean wide = format == 4;
            int ranges = wide ? data.getInt(at + 1) : data.getShort(at + 1) & 0xFFFF;
            int rec = wide ? 6 : 3;
            int first = at + (wide ? 5 : 3);
            for (int r = 0; r < ranges; r++) {
                int p = first + r * rec;
                int start = wide ? data.getInt(p) : data.getShort(p) & 0xFFFF;
                int fd = wide ? data.getShort(p + 4) & 0xFFFF : data.get(p + 2) & 0xFF;
                int end = wide ? data.getInt(p + rec) : data.getShort(p + rec) & 0xFFFF;
                for (int g = Math.max(0, start); g < Math.min(glyphs, end); g++) {
                    out[g] = fd;
                }
            }
        }
        return out;
    }
}
