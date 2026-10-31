package stirling.software.officeconvert.topdf.font;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class CffSubsetter {

    private static final int UNIQUE_ID = 13;

    private static final int XUID = 14;

    private static final int CHARSET = 15;

    private static final int ENCODING = 16;

    private static final int CHAR_STRINGS = 17;

    private static final int PRIVATE = 18;

    private static final int SUBRS = 19;

    private static final int ROS = 1230;

    private static final int CID_COUNT = 1234;

    private static final int UID_BASE = 1235;

    private static final int FD_ARRAY = 1236;

    private static final int FD_SELECT = 1237;

    private static final int SYNTHETIC_BASE = 1220;

    private static final int STANDARD_STRINGS = 391;

    private static final int MAX_DEPTH = 10;

    private static final int MAX_STACK = 513;

    private static final long MAX_WORK = 1L << 25;

    private static final int MAX_PRIVATE_BYTES = 1 << 16;

    private static final long MAX_EXTRA_BYTES = 16L << 20;

    private static final byte[] RETURN = {11};

    private record Entry(int op, byte[] operands, int[] numbers) {}

    private record Index(int count, int base, int[] offsets, int end) {

        int start(int i) {
            return base + offsets[i];
        }

        int stop(int i) {
            return base + offsets[i + 1];
        }
    }

    private static final class Fd {
        final int number;
        List<Entry> font = List.of();
        List<Entry> priv = List.of();
        Index subrs;
        final BitSet used = new BitSet();
        boolean all;
        int[] renumbered;
        int renumberedCount;

        Fd(int number) {
            this.number = number;
        }
    }

    private record Call(int length, int fd, int subr) {}

    private final byte[] in;

    private final BitSet usedGlobal = new BitSet();

    private final BitSet operands = new BitSet();

    private boolean allGlobal;

    private boolean renumber = true;

    private final TreeMap<Integer, Call> calls = new TreeMap<>();

    private int[] globalRenumbered;

    private int globalCount;

    private Fd[] fds = new Fd[1];

    private long work;

    private long written;

    private Index global;

    private CffSubsetter(byte[] in) {
        this.in = in;
    }

    static byte[] subset(byte[] cff, String name, int[] cids, int[] glyphs) throws IOException {
        try {
            return new CffSubsetter(cff).write(name, cids, glyphs);
        } catch (RuntimeException e) {
            throw new IOException("the CFF outlines could not be read: " + e, e);
        }
    }

    private byte[] write(String name, int[] cids, int[] glyphs) throws IOException {
        if (in.length < 4 || (in[0] & 0xFF) != 1) {
            throw new IOException("the CFF version is not 1");
        }
        if (cids.length == 0 || cids[0] != 0 || glyphs[0] != 0) {
            throw new IllegalArgumentException("CID 0 must draw glyph 0");
        }
        Index names = index(in[2] & 0xFF);
        Index tops = index(names.end());
        Index strings = index(tops.end());
        global = index(strings.end());
        if (tops.count() < 1) {
            throw new IOException("the CFF has no font");
        }
        List<Entry> top = dict(tops.start(0), tops.stop(0));
        Index charStrings = index(number(top, CHAR_STRINGS));
        int count = charStrings.count();
        boolean cidKeyed = entry(top, ROS) != null;
        int[] fdOf = new int[glyphs.length];
        for (int g : glyphs) {
            if (g < 0 || g >= count) {
                throw new IllegalArgumentException("glyph " + g + " is not in the font");
            }
        }
        if (cidKeyed) {
            Index array = index(number(top, FD_ARRAY));
            fds = new Fd[array.count()];
            int[] select = fdSelect(number(top, FD_SELECT), count);
            for (int k = 0; k < glyphs.length; k++) {
                int f = select[glyphs[k]];
                if (f < 0 || f >= fds.length) {
                    throw new IOException("glyph " + glyphs[k] + " has no font dictionary");
                }
                if (fds[f] == null) {
                    Fd fd = new Fd(f);
                    fd.font = dict(array.start(f), array.stop(f));
                    privateOf(fd, fd.font);
                    fds[f] = fd;
                }
                fdOf[k] = f;
            }
        } else {
            Fd fd = new Fd(0);
            privateOf(fd, top);
            fds[0] = fd;
        }
        for (int k = 0; k < glyphs.length; k++) {
            new Scan(fds[fdOf[k]]).glyph(charStrings.start(glyphs[k]), charStrings.stop(glyphs[k]));
        }

        for (int pos : calls.keySet()) {
            if (operands.get(pos)) {
                renumber = false;
                break;
            }
        }
        List<Fd> kept = new ArrayList<>();
        int[] keptAt = new int[fds.length];
        Arrays.fill(keptAt, -1);
        for (int k = 0; k < glyphs.length; k++) {
            if (keptAt[fdOf[k]] < 0) {
                keptAt[fdOf[k]] = kept.size();
                kept.add(fds[fdOf[k]]);
            }
        }
        if (renumber) {
            globalRenumbered = ranks(usedGlobal, global.count());
            globalCount = usedGlobal.cardinality();
            for (Fd fd : kept) {
                if (fd.subrs != null) {
                    fd.renumbered = ranks(fd.used, fd.subrs.count());
                    fd.renumberedCount = fd.used.cardinality();
                }
            }
        }

        List<byte[]> stringItems = items(strings);
        int adobe = STANDARD_STRINGS + stringItems.size();
        stringItems.add("Adobe".getBytes(StandardCharsets.US_ASCII));
        stringItems.add("Identity".getBytes(StandardCharsets.US_ASCII));
        byte[] stringIndex = index(stringItems);
        byte[] nameIndex = index(List.of(name.getBytes(StandardCharsets.ISO_8859_1)));
        byte[] globalIndex = subrs(global, usedGlobal, allGlobal);

        ByteArrayOutputStream charset = new ByteArrayOutputStream();
        charset.write(0);
        for (int k = 1; k < cids.length; k++) {
            u16(charset, cids[k]);
        }
        ByteArrayOutputStream select = new ByteArrayOutputStream();
        List<int[]> ranges = new ArrayList<>();
        for (int k = 0; k < glyphs.length; k++) {
            int fd = keptAt[fdOf[k]];
            if (ranges.isEmpty() || ranges.get(ranges.size() - 1)[1] != fd) {
                ranges.add(new int[] {k, fd});
            }
        }
        select.write(3);
        u16(select, ranges.size());
        for (int[] r : ranges) {
            u16(select, r[0]);
            select.write(r[1]);
        }
        u16(select, glyphs.length);
        List<byte[]> outlines = new ArrayList<>(glyphs.length);
        for (int g : glyphs) {
            outlines.add(rewritten(charStrings.start(g), charStrings.stop(g)));
        }
        byte[] charStringIndex = index(outlines);

        List<byte[]> privates = new ArrayList<>();
        List<byte[]> locals = new ArrayList<>();
        for (Fd fd : kept) {
            byte[] local = fd.subrs == null || !fd.all && fd.used.isEmpty() ? null : subrs(fd.subrs, fd.used, fd.all);
            List<Entry> body = new ArrayList<>();
            for (Entry e : fd.priv) {
                if (e.op() != SUBRS) {
                    body.add(e);
                }
            }
            byte[] bytes = entries(body);
            if (local != null) {
                bytes = concat(bytes, int5(bytes.length + 6), new byte[] {SUBRS});
            }
            privates.add(bytes);
            locals.add(local);
        }

        int headerSize = 4;
        byte[] topProbe = index(List.of(topDict(top, adobe, cids, 0, 0, 0, 0)));
        int at = headerSize + nameIndex.length + topProbe.length + stringIndex.length + globalIndex.length;
        int charsetAt = at;
        at += charset.size();
        int selectAt = at;
        at += select.size();
        int charStringsAt = at;
        at += charStringIndex.length;
        int fdArrayAt = at;
        List<byte[]> fontProbe = new ArrayList<>();
        for (Fd fd : kept) {
            fontProbe.add(fontDict(fd.font, 0, 0));
        }
        at += index(fontProbe).length;
        List<byte[]> fontDicts = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            fontDicts.add(fontDict(kept.get(i).font, privates.get(i).length, at));
            at += privates.get(i).length + (locals.get(i) == null ? 0 : locals.get(i).length);
        }
        byte[] topIndex = index(List.of(topDict(top, adobe, cids, charsetAt, selectAt, charStringsAt, fdArrayAt)));
        ByteArrayOutputStream out = new ByteArrayOutputStream(at);
        out.writeBytes(new byte[] {1, 0, (byte) headerSize, 4});
        out.writeBytes(nameIndex);
        out.writeBytes(topIndex);
        out.writeBytes(stringIndex);
        out.writeBytes(globalIndex);
        charset.writeTo(out);
        select.writeTo(out);
        out.writeBytes(charStringIndex);
        out.writeBytes(index(fontDicts));
        for (int i = 0; i < kept.size(); i++) {
            out.writeBytes(privates.get(i));
            if (locals.get(i) != null) {
                out.writeBytes(locals.get(i));
            }
        }
        if (out.size() != at) {
            throw new IllegalStateException("the CFF layout is inconsistent");
        }
        return out.toByteArray();
    }

    private byte[] topDict(List<Entry> top, int adobe, int[] cids, int charset, int select, int charStrings,
            int fdArray) {
        List<Entry> out = new ArrayList<>();
        out.add(new Entry(ROS, concat(integer(adobe), integer(adobe + 1), integer(0)), null));
        for (Entry e : top) {
            switch (e.op()) {
                case ROS, CID_COUNT, CHARSET, ENCODING, CHAR_STRINGS, PRIVATE, FD_ARRAY, FD_SELECT, UNIQUE_ID, XUID,
                        UID_BASE, SYNTHETIC_BASE -> {
                }
                default -> out.add(e);
            }
        }
        out.add(new Entry(CID_COUNT, integer(cids[cids.length - 1] + 1), null));
        out.add(new Entry(CHARSET, int5(charset), null));
        out.add(new Entry(FD_SELECT, int5(select), null));
        out.add(new Entry(CHAR_STRINGS, int5(charStrings), null));
        out.add(new Entry(FD_ARRAY, int5(fdArray), null));
        return entries(out);
    }

    private static byte[] fontDict(List<Entry> font, int privateSize, int privateAt) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : font) {
            if (e.op() != PRIVATE) {
                out.add(e);
            }
        }
        out.add(new Entry(PRIVATE, concat(int5(privateSize), int5(privateAt)), null));
        return entries(out);
    }

    private void privateOf(Fd fd, List<Entry> owner) {
        Entry p = entry(owner, PRIVATE);
        if (p == null || p.numbers().length < 2) {
            return;
        }
        int size = p.numbers()[0];
        int at = p.numbers()[1];
        if (size < 0 || at < 0 || at + size > in.length) {
            throw new IllegalArgumentException("bad Private DICT");
        }
        if (size > MAX_PRIVATE_BYTES) {
            throw new IllegalArgumentException("the Private DICT is too large");
        }
        fd.priv = dict(at, at + size);
        Entry subrs = entry(fd.priv, SUBRS);
        if (subrs != null && subrs.numbers().length > 0 && subrs.numbers()[0] > 0) {
            fd.subrs = index(at + subrs.numbers()[0]);
        }
    }

    private int[] fdSelect(int select, int count) {
        int[] fd = new int[count];
        int format = in[select] & 0xFF;
        if (format == 0) {
            for (int g = 0; g < count; g++) {
                fd[g] = in[select + 1 + g] & 0xFF;
            }
            return fd;
        }
        if (format != 3) {
            throw new IllegalArgumentException("FDSelect format " + format);
        }
        Arrays.fill(fd, -1);
        int ranges = u16(select + 1);
        int previous = 0;
        for (int r = 0; r < ranges; r++) {
            int first = u16(select + 3 + 3 * r);
            int next = u16(select + 6 + 3 * r);
            if (first < previous) {
                throw new IllegalArgumentException("the FDSelect ranges are out of order");
            }
            Arrays.fill(fd, Math.min(first, count), Math.min(Math.max(first, next), count),
                    in[select + 5 + 3 * r] & 0xFF);
            previous = first;
        }
        return fd;
    }

    private byte[] subrs(Index subrs, BitSet used, boolean all) {
        List<byte[]> out = new ArrayList<>(renumber ? used.cardinality() : subrs.count());
        for (int i = 0; i < subrs.count(); i++) {
            if (renumber) {
                if (used.get(i)) {
                    out.add(rewritten(subrs.start(i), subrs.stop(i)));
                }
            } else {
                out.add(all || used.get(i) ? slice(subrs.start(i), subrs.stop(i)) : RETURN);
            }
        }
        return index(out);
    }

    private static int[] ranks(BitSet used, int count) {
        int[] rank = new int[count];
        int next = 0;
        for (int i = 0; i < count; i++) {
            rank[i] = used.get(i) ? next++ : -1;
        }
        return rank;
    }

    private byte[] rewritten(int start, int end) {
        if (!renumber) {
            return slice(start, end);
        }
        grow(end - start);
        ByteArrayOutputStream out = new ByteArrayOutputStream(end - start + 8);
        int at = start;
        for (Map.Entry<Integer, Call> e : calls.subMap(start, end).entrySet()) {
            int pos = e.getKey();
            Call call = e.getValue();
            out.write(in, at, pos - at);
            Fd fd = call.fd() < 0 ? null : fds[call.fd()];
            int rank = fd == null ? globalRenumbered[call.subr()] : fd.renumbered[call.subr()];
            operand(out, rank - bias(fd == null ? globalCount : fd.renumberedCount));
            at = pos + call.length();
        }
        out.write(in, at, end - at);
        return out.toByteArray();
    }

    private static void operand(ByteArrayOutputStream out, int v) {
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

    private final class Scan {

        private final Fd fd;

        private final int[] stack = new int[MAX_STACK];

        private final int[] at = new int[MAX_STACK];

        private final int[] length = new int[MAX_STACK];

        private int top;

        private int stems;

        private boolean done;

        Scan(Fd fd) {
            this.fd = fd;
        }

        void glyph(int start, int end) {
            run(start, end, 0);
        }

        private void run(int start, int end, int depth) {
            if (depth > MAX_DEPTH) {
                fail();
                return;
            }
            int i = start;
            while (i < end && !done) {
                if (++work > MAX_WORK) {
                    fail();
                    return;
                }
                int b = in[i] & 0xFF;
                if (b == 28) {
                    push((short) ((in[i + 1] & 0xFF) << 8 | in[i + 2] & 0xFF), i, 3);
                    i += 3;
                } else if (b >= 32 && b <= 246) {
                    push(b - 139, i, 1);
                    i++;
                } else if (b >= 247 && b <= 250) {
                    push((b - 247) * 256 + (in[i + 1] & 0xFF) + 108, i, 2);
                    i += 2;
                } else if (b >= 251 && b <= 254) {
                    push(-(b - 251) * 256 - (in[i + 1] & 0xFF) - 108, i, 2);
                    i += 2;
                } else if (b == 255) {
                    push((short) ((in[i + 1] & 0xFF) << 8 | in[i + 2] & 0xFF), -1, 5);
                    i += 5;
                } else {
                    i = operator(i, b, end, depth);
                }
            }
        }

        private void push(int v, int pos, int len) {
            if (top == MAX_STACK) {
                fail();
                return;
            }
            at[top] = pos;
            length[top] = len;
            stack[top++] = v;
        }

        private int operator(int i, int b, int end, int depth) {
            switch (b) {
                case 1, 3, 18, 23 -> {
                    stems += top / 2;
                    clear();
                    return i + 1;
                }
                case 19, 20 -> {
                    stems += top / 2;
                    clear();
                    return i + 1 + (stems + 7) / 8;
                }
                case 10, 29 -> {
                    boolean local = b == 10;
                    Index subrs = local ? fd.subrs : global;
                    if (top == 0 || subrs == null) {
                        fail();
                        return end;
                    }
                    int n = stack[--top] + bias(subrs.count());
                    if (n < 0 || n >= subrs.count()) {
                        fail();
                        return end;
                    }
                    Call call = new Call(length[top], local ? fd.number : -1, n);
                    Call known = at[top] < 0 ? null : calls.putIfAbsent(at[top], call);
                    if (at[top] < 0 || known != null && !known.equals(call)) {
                        renumber = false;
                    }
                    (local ? fd.used : usedGlobal).set(n);
                    run(subrs.start(n), subrs.stop(n), depth + 1);
                    return i + 1;
                }
                case 11 -> {
                    return end;
                }
                case 14 -> {
                    if (top >= 4) {
                        throw new IllegalArgumentException("a glyph is built from accented parts (seac)");
                    }
                    clear();
                    done = true;
                    return end;
                }
                case 12 -> {
                    clear();
                    return i + 2;
                }
                default -> {
                    clear();
                    return i + 1;
                }
            }
        }

        private void clear() {
            for (int k = 0; k < top; k++) {
                if (at[k] >= 0) {
                    operands.set(at[k]);
                }
            }
            top = 0;
        }

        private void fail() {
            done = true;
            fd.all = true;
            allGlobal = true;
            renumber = false;
        }
    }

    private static int bias(int count) {
        return count < 1240 ? 107 : count < 33900 ? 1131 : 32768;
    }

    private Index index(int at) {
        int count = u16(at);
        if (count == 0) {
            return new Index(0, at + 2, new int[] {0}, at + 2);
        }
        int offSize = in[at + 2] & 0xFF;
        if (offSize < 1 || offSize > 4) {
            throw new IllegalArgumentException("bad INDEX offset size " + offSize);
        }
        int[] offsets = new int[count + 1];
        int p = at + 3;
        for (int i = 0; i <= count; i++) {
            int v = 0;
            for (int k = 0; k < offSize; k++) {
                v = v << 8 | in[p++] & 0xFF;
            }
            offsets[i] = v - 1;
            if (offsets[i] < 0 || i > 0 && offsets[i] < offsets[i - 1]) {
                throw new IllegalArgumentException("bad INDEX offsets");
            }
        }
        int base = p;
        if (base + offsets[count] > in.length) {
            throw new IllegalArgumentException("the INDEX runs past the CFF");
        }
        return new Index(count, base, offsets, base + offsets[count]);
    }

    private List<byte[]> items(Index index) {
        List<byte[]> out = new ArrayList<>(index.count() + 2);
        for (int i = 0; i < index.count(); i++) {
            out.add(slice(index.start(i), index.stop(i)));
        }
        return out;
    }

    private List<Entry> dict(int start, int end) {
        List<Entry> out = new ArrayList<>();
        int p = start;
        int from = start;
        List<Integer> nums = new ArrayList<>();
        while (p < end) {
            int b = in[p] & 0xFF;
            if (b <= 21) {
                int op = b == 12 ? 1200 + (in[p + 1] & 0xFF) : b;
                byte[] operands = slice(from, p);
                p += b == 12 ? 2 : 1;
                int[] n = new int[nums.size()];
                for (int i = 0; i < n.length; i++) {
                    n[i] = nums.get(i);
                }
                out.add(new Entry(op, operands, n));
                nums.clear();
                from = p;
            } else if (b == 30) {
                p++;
                while (p < end) {
                    int x = in[p++] & 0xFF;
                    if ((x & 0x0F) == 0x0F || (x & 0xF0) == 0xF0) {
                        break;
                    }
                }
                nums.add(0);
            } else if (b == 28) {
                nums.add((int) (short) ((in[p + 1] & 0xFF) << 8 | in[p + 2] & 0xFF));
                p += 3;
            } else if (b == 29) {
                nums.add((in[p + 1] & 0xFF) << 24 | (in[p + 2] & 0xFF) << 16 | (in[p + 3] & 0xFF) << 8
                        | in[p + 4] & 0xFF);
                p += 5;
            } else if (b >= 32 && b <= 246) {
                nums.add(b - 139);
                p++;
            } else if (b >= 247 && b <= 250) {
                nums.add((b - 247) * 256 + (in[p + 1] & 0xFF) + 108);
                p += 2;
            } else if (b >= 251 && b <= 254) {
                nums.add(-(b - 251) * 256 - (in[p + 1] & 0xFF) - 108);
                p += 2;
            } else {
                throw new IllegalArgumentException("bad DICT operand " + b);
            }
        }
        return out;
    }

    private static Entry entry(List<Entry> d, int op) {
        for (Entry e : d) {
            if (e.op() == op) {
                return e;
            }
        }
        return null;
    }

    private static int number(List<Entry> d, int op) {
        Entry e = entry(d, op);
        if (e == null || e.numbers().length == 0) {
            throw new IllegalArgumentException("the Top DICT lacks operator " + op);
        }
        return e.numbers()[e.numbers().length - 1];
    }

    private int u16(int at) {
        return (in[at] & 0xFF) << 8 | in[at + 1] & 0xFF;
    }

    private void grow(long bytes) {
        written += bytes;
        if (written > 2L * in.length + MAX_EXTRA_BYTES) {
            throw new IllegalArgumentException("the subset CFF would be too large");
        }
    }

    private byte[] slice(int start, int end) {
        grow(end - start);
        byte[] b = new byte[end - start];
        System.arraycopy(in, start, b, 0, b.length);
        return b;
    }

    private static void u16(ByteArrayOutputStream out, int v) {
        out.write(v >> 8);
        out.write(v);
    }

    private static byte[] entries(List<Entry> entries) {
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

    private static byte[] index(List<byte[]> items) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        u16(o, items.size());
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

    private static byte[] integer(int v) {
        if (v >= -107 && v <= 107) {
            return new byte[] {(byte) (v + 139)};
        }
        return int5(v);
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
