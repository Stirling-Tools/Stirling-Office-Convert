package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

final class CffSubset {

    private static final int CHARSET = 15;

    private static final int ENCODING = 16;

    private static final int CHAR_STRINGS = 17;

    private static final int PRIVATE = 18;

    private static final int SUBRS = 19;

    private static final int FD_ARRAY = 1236;

    private static final int FD_SELECT = 1237;

    private static final Set<Integer> OFFSETS = Set.of(CHARSET, ENCODING, CHAR_STRINGS, PRIVATE, FD_ARRAY, FD_SELECT);

    private record Entry(int op, byte[] operands, int[] numbers) {}

    private record Index(int start, int end, List<byte[]> items) {}

    private final byte[] in;

    private CffSubset(byte[] in) {
        this.in = in;
    }

    static byte[] hollow(byte[] cff, Set<Integer> keep) {
        try {
            return new CffSubset(cff).run(keep);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static List<Integer> seacComponents(byte[] cff, Set<Integer> gids) {
        try {
            return new CffSubset(cff).components(gids);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private byte[] run(Set<Integer> keep) {
        int hdrSize = in[2] & 0xFF;
        Index names = index(hdrSize);
        Index tops = index(names.end());
        Index strings = index(tops.end());
        Index gsubrs = index(strings.end());
        if (tops.items().size() != 1) {
            return null;
        }
        List<Entry> top = dict(tops.items().get(0));
        int csAt = value(top, CHAR_STRINGS, 0);
        if (csAt <= 0) {
            return null;
        }
        Index charStrings = index(csAt);
        int glyphs = charStrings.items().size();
        byte[] charset = value(top, CHARSET, 0) > 2 ? block(value(top, CHARSET, 0), charsetSize(value(top, CHARSET, 0),
                glyphs)) : null;
        byte[] encoding = value(top, ENCODING, 0) > 1 ? block(value(top, ENCODING, 0),
                encodingSize(value(top, ENCODING, 0))) : null;
        byte[] fdSelect = has(top, FD_SELECT) ? block(value(top, FD_SELECT, 0), fdSelectSize(value(top, FD_SELECT, 0),
                glyphs)) : null;
        List<byte[]> privates = new ArrayList<>();
        List<List<Entry>> fontDicts = new ArrayList<>();
        if (has(top, FD_ARRAY)) {
            Index fds = index(value(top, FD_ARRAY, 0));
            for (byte[] fd : fds.items()) {
                List<Entry> d = dict(fd);
                fontDicts.add(d);
                privates.add(privateBlock(d));
            }
        } else if (has(top, PRIVATE)) {
            privates.add(privateBlock(top));
        }
        List<byte[]> items = new ArrayList<>(glyphs);
        boolean changed = false;
        for (int g = 0; g < glyphs; g++) {
            if (keep.contains(g) || g == 0) {
                items.add(charStrings.items().get(g));
            } else {
                items.add(new byte[] {14});
                changed |= charStrings.items().get(g).length > 1;
            }
        }
        if (!changed) {
            return null;
        }
        byte[] newCharStrings = index(items);
        byte[] head = Arrays.copyOfRange(in, 0, hdrSize);
        byte[] nameIndex = Arrays.copyOfRange(in, names.start(), names.end());
        byte[] stringIndex = Arrays.copyOfRange(in, strings.start(), strings.end());
        byte[] gsubrIndex = Arrays.copyOfRange(in, gsubrs.start(), gsubrs.end());
        byte[] topProbe = index(List.of(writeDict(top, new int[7])));
        int at = head.length + nameIndex.length + topProbe.length + stringIndex.length + gsubrIndex.length;
        int[] offsets = new int[7];
        offsets[0] = charset == null ? value(top, CHARSET, 0) : at;
        at += charset == null ? 0 : charset.length;
        offsets[1] = encoding == null ? value(top, ENCODING, 0) : at;
        at += encoding == null ? 0 : encoding.length;
        offsets[2] = fdSelect == null ? 0 : at;
        at += fdSelect == null ? 0 : fdSelect.length;
        offsets[3] = at;
        at += newCharStrings.length;
        byte[] fdArray = null;
        if (!fontDicts.isEmpty()) {
            List<byte[]> probe = new ArrayList<>();
            for (List<Entry> d : fontDicts) {
                probe.add(writeDict(d, new int[7]));
            }
            int fdAt = at;
            at += index(probe).length;
            List<byte[]> dicts = new ArrayList<>();
            for (int i = 0; i < fontDicts.size(); i++) {
                int[] o = new int[7];
                o[5] = privateSize(privates.get(i));
                o[6] = at;
                dicts.add(writeDict(fontDicts.get(i), o));
                at += privates.get(i).length;
            }
            fdArray = index(dicts);
            offsets[4] = fdAt;
        } else if (!privates.isEmpty()) {
            offsets[5] = privateSize(privates.get(0));
            offsets[6] = at;
        }
        byte[] topIndex = index(List.of(writeDict(top, offsets)));
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        for (byte[] b : new byte[][] {head, nameIndex, topIndex, stringIndex, gsubrIndex, charset, encoding, fdSelect,
                newCharStrings, fdArray}) {
            if (b != null) {
                out.writeBytes(b);
            }
        }
        for (byte[] p : privates) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private byte[] privateBlock(List<Entry> owner) {
        Entry e = entry(owner, PRIVATE);
        if (e == null || e.numbers().length < 2) {
            return new byte[0];
        }
        int size = e.numbers()[0];
        int off = e.numbers()[1];
        List<Entry> priv = dict(Arrays.copyOfRange(in, off, off + size));
        int subrs = value(priv, SUBRS, -1);
        byte[] local = subrs > 0 ? Arrays.copyOfRange(in, off + subrs, index(off + subrs).end()) : null;
        List<Entry> rewritten = new ArrayList<>();
        for (Entry x : priv) {
            if (x.op() != SUBRS) {
                rewritten.add(x);
            }
        }
        byte[] body = writeEntries(rewritten);
        if (local == null) {
            return body;
        }
        byte[] withSubrs = concat(body, int5(body.length + 6), new byte[] {SUBRS});
        return concat(withSubrs, local);
    }

    private static int privateSize(byte[] block) {
        int[] p = {0};
        int size = 0;
        while (p[0] < block.length) {
            int b = block[p[0]] & 0xFF;
            if (b <= 21) {
                int op = b == 12 ? 1200 + (block[p[0] + 1] & 0xFF) : b;
                p[0] += b == 12 ? 2 : 1;
                size = p[0];
                if (op == SUBRS) {
                    return size;
                }
            } else {
                skipOperand(block, p);
            }
        }
        return size;
    }

    private byte[] writeDict(List<Entry> d, int[] offsets) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : d) {
            int k = switch (e.op()) {
                case CHARSET -> 0;
                case ENCODING -> 1;
                case FD_SELECT -> 2;
                case CHAR_STRINGS -> 3;
                case FD_ARRAY -> 4;
                case PRIVATE -> 5;
                default -> -1;
            };
            if (k < 0 || (k == 0 && e.numbers()[0] <= 2 && offsets[0] <= 2) || (k == 1 && e.numbers()[0] <= 1
                    && offsets[1] <= 1)) {
                out.add(e);
            } else if (k == 5) {
                out.add(new Entry(e.op(), concat(int5(offsets[5]), int5(offsets[6])), null));
            } else {
                out.add(new Entry(e.op(), int5(offsets[k]), null));
            }
        }
        return writeEntries(out);
    }

    private static byte[] writeEntries(List<Entry> entries) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (Entry e : entries) {
            o.writeBytes(e.operands());
            if (e.op() >= 1200) {
                o.write(12);
                o.write(e.op() - 1200);
            } else {
                o.write(e.op());
            }
        }
        return o.toByteArray();
    }

    private List<Entry> dict(byte[] d) {
        List<Entry> out = new ArrayList<>();
        int[] p = {0};
        int start = 0;
        List<Integer> nums = new ArrayList<>();
        while (p[0] < d.length) {
            int b = d[p[0]] & 0xFF;
            if (b <= 21) {
                int op = b == 12 ? 1200 + (d[p[0] + 1] & 0xFF) : b;
                byte[] operands = Arrays.copyOfRange(d, start, p[0]);
                p[0] += b == 12 ? 2 : 1;
                int[] n = new int[nums.size()];
                for (int i = 0; i < n.length; i++) {
                    n[i] = nums.get(i);
                }
                out.add(new Entry(op, operands, n));
                nums.clear();
                start = p[0];
            } else {
                nums.add(skipOperand(d, p));
            }
        }
        return out;
    }

    private static int skipOperand(byte[] d, int[] p) {
        int b = d[p[0]] & 0xFF;
        if (b == 30) {
            p[0]++;
            while (p[0] < d.length) {
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
        if (b >= 247 && b <= 250) {
            int v = (b - 247) * 256 + (d[p[0] + 1] & 0xFF) + 108;
            p[0] += 2;
            return v;
        }
        if (b >= 251 && b <= 254) {
            int v = -(b - 251) * 256 - (d[p[0] + 1] & 0xFF) - 108;
            p[0] += 2;
            return v;
        }
        throw new IllegalArgumentException("bad DICT operand " + b);
    }

    private static Entry entry(List<Entry> d, int op) {
        for (Entry e : d) {
            if (e.op() == op) {
                return e;
            }
        }
        return null;
    }

    private static boolean has(List<Entry> d, int op) {
        return entry(d, op) != null;
    }

    private static int value(List<Entry> d, int op, int fallback) {
        Entry e = entry(d, op);
        return e == null || e.numbers().length == 0 ? fallback : e.numbers()[e.numbers().length - 1];
    }

    private Index index(int at) {
        int count = (in[at] & 0xFF) << 8 | in[at + 1] & 0xFF;
        if (count == 0) {
            return new Index(at, at + 2, List.of());
        }
        int offSize = in[at + 2] & 0xFF;
        if (offSize < 1 || offSize > 4) {
            throw new IllegalArgumentException("bad offSize");
        }
        int base = at + 3 + (count + 1) * offSize - 1;
        List<byte[]> items = new ArrayList<>(count);
        int prev = offset(at + 3, offSize);
        for (int i = 1; i <= count; i++) {
            int next = offset(at + 3 + i * offSize, offSize);
            items.add(Arrays.copyOfRange(in, base + prev, base + next));
            prev = next;
        }
        return new Index(at, base + prev, items);
    }

    private int offset(int at, int size) {
        int v = 0;
        for (int i = 0; i < size; i++) {
            v = v << 8 | in[at + i] & 0xFF;
        }
        return v;
    }

    private byte[] block(int at, int size) {
        return Arrays.copyOfRange(in, at, at + size);
    }

    private int charsetSize(int at, int glyphs) {
        int format = in[at] & 0xFF;
        if (format == 0) {
            return 1 + 2 * (glyphs - 1);
        }
        int p = at + 1;
        int covered = 1;
        while (covered < glyphs) {
            int left = format == 1 ? in[p + 2] & 0xFF : (in[p + 2] & 0xFF) << 8 | in[p + 3] & 0xFF;
            p += format == 1 ? 3 : 4;
            covered += left + 1;
        }
        return p - at;
    }

    private int encodingSize(int at) {
        int format = in[at] & 0xFF;
        int size;
        if ((format & 0x7F) == 0) {
            size = 2 + (in[at + 1] & 0xFF);
        } else {
            size = 2 + 2 * (in[at + 1] & 0xFF);
        }
        if ((format & 0x80) != 0) {
            size += 1 + 3 * (in[at + size] & 0xFF);
        }
        return size;
    }

    private int fdSelectSize(int at, int glyphs) {
        int format = in[at] & 0xFF;
        if (format == 0) {
            return 1 + glyphs;
        }
        int ranges = (in[at + 1] & 0xFF) << 8 | in[at + 2] & 0xFF;
        return 1 + 2 + 3 * ranges + 2;
    }

    private List<Integer> components(Set<Integer> gids) {
        int hdrSize = in[2] & 0xFF;
        Index names = index(hdrSize);
        Index tops = index(names.end());
        Index strings = index(tops.end());
        Index gsubrs = index(strings.end());
        List<Entry> top = dict(tops.items().get(0));
        Index charStrings = index(value(top, CHAR_STRINGS, 0));
        Entry priv = entry(top, PRIVATE);
        List<byte[]> local = List.of();
        if (priv != null && priv.numbers().length >= 2) {
            List<Entry> p = dict(Arrays.copyOfRange(in, priv.numbers()[1], priv.numbers()[1] + priv.numbers()[0]));
            int subrs = value(p, SUBRS, -1);
            if (subrs > 0) {
                local = index(priv.numbers()[1] + subrs).items();
            }
        }
        List<Integer> out = new ArrayList<>();
        for (int g : gids) {
            if (g >= 0 && g < charStrings.items().size()) {
                int[] seac = new Type2Scan(local, gsubrs.items()).seac(charStrings.items().get(g));
                if (seac != null) {
                    out.add(seac[0]);
                    out.add(seac[1]);
                }
            }
        }
        return out;
    }

    static byte[] index(List<byte[]> items) {
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

    private static byte[] int5(int v) {
        return new byte[] {29, (byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.writeBytes(p);
        }
        return o.toByteArray();
    }
}
