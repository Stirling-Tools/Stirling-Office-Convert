package stirling.software.officeconvert.topdf.font;

import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.fontbox.cff.CIDKeyedType2CharString;
import org.apache.fontbox.cff.Type2CharStringParser;

final class LazyCidCff {

    private static final int PAGE = 1 << 14;

    private static final int ROS = 1230;

    private static final int CHARSTRING_TYPE = 1206;

    private static final int FONT_MATRIX = 1207;

    private static final int SYNTHETIC_BASE = 1220;

    private static final int FD_ARRAY = 1236;

    private static final int FD_SELECT = 1237;

    private static final List<Number> DEFAULT_MATRIX = List.of(0.001, 0.0, 0.0, 0.001, 0.0, 0.0);

    private final Path file;

    private final byte[] data;

    private final long base;

    private final int length;

    private final Map<Integer, byte[]> pages = new HashMap<>();

    private final String name;

    private final List<Number> matrix;

    private final int charStrings;

    private final int glyphCount;

    private final int charStringOffSize;

    private final long charStringData;

    private final int[] rangeGid;

    private final int[] rangeCid;

    private final int[] rangeCount;

    private final int[] cidStart;

    private final int[] cidRange;

    private final int[] select;

    private final int[] selectFirst;

    private final int[] selectFd;

    private final int selectSentinel;

    private final long[] privateAt;

    private final int[] privateSize;

    private final Map<Integer, Map<Integer, List<Number>>> privates = new HashMap<>();

    private final Map<Integer, byte[][]> localSubrs = new HashMap<>();

    private final byte[][] globalSubrs;

    private final Type2CharStringParser parser;

    private LazyCidCff(FontEntry entry, long base, int length) throws IOException {
        this.file = entry.file();
        this.data = entry.file() == null ? entry.data() : null;
        this.base = base;
        this.length = length;
        if (u8(0) != 1) {
            throw new IOException("not a CFF table");
        }
        long at = u8(2);
        long[] names = index(at);
        name = new String(bytes(names[0], (int) (names[1] - names[0])), StandardCharsets.ISO_8859_1);
        long[] tops = index(next(at));
        Map<Integer, List<Number>> top = dict(tops[0], (int) (tops[1] - tops[0]));
        long globalAt = next(next(next(at)));
        if (!top.containsKey(ROS) || top.containsKey(SYNTHETIC_BASE)
                || top.containsKey(CHARSTRING_TYPE) && number(top, CHARSTRING_TYPE) != 2) {
            throw new IOException("not a plain CID-keyed CFF");
        }
        long charStringsAt = number(top, 17);
        charStrings = u16(charStringsAt);
        if (charStrings == 0) {
            throw new IOException("no charstrings");
        }
        glyphCount = charStrings;
        charStringOffSize = u8(charStringsAt + 2);
        charStringData = charStringsAt + 3 + (long) (charStrings + 1) * charStringOffSize - 1;
        long charset = top.containsKey(15) ? number(top, 15) : 0;
        if (charset <= 2) {
            throw new IOException("predefined or missing charset");
        }
        List<int[]> ranges = charset(charset);
        rangeGid = new int[ranges.size()];
        rangeCid = new int[ranges.size()];
        rangeCount = new int[ranges.size()];
        for (int i = 0; i < ranges.size(); i++) {
            rangeGid[i] = ranges.get(i)[0];
            rangeCid[i] = ranges.get(i)[1];
            rangeCount[i] = ranges.get(i)[2];
        }
        Integer[] byCid = new Integer[ranges.size()];
        for (int i = 0; i < byCid.length; i++) {
            byCid[i] = i;
        }
        Arrays.sort(byCid, Comparator.comparingInt(i -> rangeCid[i]));
        cidStart = new int[byCid.length];
        cidRange = new int[byCid.length];
        for (int i = 0; i < byCid.length; i++) {
            cidRange[i] = byCid[i];
            cidStart[i] = rangeCid[byCid[i]];
        }
        long fdArrayAt = number(top, FD_ARRAY);
        int fds = u16(fdArrayAt);
        privateAt = new long[fds];
        privateSize = new int[fds];
        for (int i = 0; i < fds; i++) {
            long[] fd = indexItem(fdArrayAt, i);
            Map<Integer, List<Number>> dict = dict(fd[0], (int) (fd[1] - fd[0]));
            if (i == 0 && dict.containsKey(FONT_MATRIX)) {
                throw new IOException("the font dictionary has its own matrix");
            }
            List<Number> priv = dict.get(18);
            if (priv == null || priv.size() < 2) {
                throw new IOException("a font dictionary has no private dictionary");
            }
            privateSize[i] = priv.get(0).intValue();
            privateAt[i] = priv.get(1).intValue();
        }
        long selectAt = number(top, FD_SELECT);
        int format = u8(selectAt);
        if (format == 0) {
            byte[] raw = bytes(selectAt + 1, charStrings);
            select = new int[charStrings];
            for (int g = 0; g < charStrings; g++) {
                select[g] = raw[g] & 0xFF;
            }
            selectFirst = null;
            selectFd = null;
            selectSentinel = 0;
        } else if (format == 3) {
            int n = u16(selectAt + 1);
            byte[] raw = bytes(selectAt + 3, 3 * n + 2);
            selectFirst = new int[n];
            selectFd = new int[n];
            for (int i = 0; i < n; i++) {
                selectFirst[i] = (raw[3 * i] & 0xFF) << 8 | raw[3 * i + 1] & 0xFF;
                selectFd[i] = raw[3 * i + 2] & 0xFF;
            }
            selectSentinel = (raw[3 * n] & 0xFF) << 8 | raw[3 * n + 1] & 0xFF;
            select = null;
        } else {
            throw new IOException("unsupported FDSelect format " + format);
        }
        List<Number> m = top.get(FONT_MATRIX);
        matrix = m == null ? DEFAULT_MATRIX : m;
        globalSubrs = indexData(globalAt);
        parser = new Type2CharStringParser(name);
    }

    static LazyCidCff open(FontEntry entry) throws IOException {
        long[] t = FontScanner.tableRange(entry, "CFF ");
        if (t[1] > FontProgram.MAX_WHOLE_BYTES) {
            throw new IOException("the CFF table is too large");
        }
        try {
            return new LazyCidCff(entry, t[0], (int) t[1]);
        } catch (RuntimeException e) {
            throw new IOException("the CFF outlines could not be read lazily: " + e, e);
        }
    }

    int glyphCount() {
        return glyphCount;
    }

    List<Number> fontMatrix() {
        return matrix;
    }

    synchronized GeneralPath path(int glyph) throws IOException {
        int cid = cidForGid(glyph);
        int gid = gidForCid(cid);
        byte[] charString = charString(gid);
        int fd = fdIndex(gid);
        boolean known = fd != -1 && fd < privateAt.length;
        List<Object> sequence = parser.parse(charString, globalSubrs, known ? localSubrs(fd) : null,
                String.format(Locale.US, "%04x", cid));
        int defaultWidth = known ? width(fd, 20) : 1000;
        int nominalWidth = known ? width(fd, 21) : 1000;
        CIDKeyedType2CharString outline = new CIDKeyedType2CharString(n -> notdef(), name, cid, gid, sequence,
                defaultWidth, nominalWidth);
        return outline.getPath();
    }

    private CIDKeyedType2CharString notdef() throws IOException {
        int gid = gidForCid(0);
        int fd = fdIndex(gid);
        boolean known = fd != -1 && fd < privateAt.length;
        List<Object> sequence = parser.parse(charString(gid), globalSubrs, known ? localSubrs(fd) : null, "0000");
        return new CIDKeyedType2CharString(n -> notdef(), name, 0, gid, sequence, known ? width(fd, 20) : 1000,
                known ? width(fd, 21) : 1000);
    }

    private int cidForGid(int gid) {
        int i = Arrays.binarySearch(rangeGid, gid);
        i = i >= 0 ? i : -i - 2;
        return i >= 0 && gid < rangeGid[i] + rangeCount[i] ? rangeCid[i] + gid - rangeGid[i] : 0;
    }

    private int gidForCid(int cid) {
        int i = Arrays.binarySearch(cidStart, cid);
        i = i >= 0 ? i : -i - 2;
        if (i < 0) {
            return 0;
        }
        int r = cidRange[i];
        return cid < rangeCid[r] + rangeCount[r] ? rangeGid[r] + cid - rangeCid[r] : 0;
    }

    private int fdIndex(int gid) {
        if (select != null) {
            return gid < select.length ? select[gid] : 0;
        }
        for (int i = 0; i < selectFirst.length; i++) {
            if (selectFirst[i] <= gid) {
                if (i + 1 < selectFirst.length) {
                    if (selectFirst[i + 1] > gid) {
                        return selectFd[i];
                    }
                } else {
                    return selectSentinel > gid ? selectFd[i] : -1;
                }
            }
        }
        return 0;
    }

    private byte[] charString(int gid) throws IOException {
        if (gid < 0 || gid >= charStrings) {
            throw new IOException("glyph " + gid + " is out of range");
        }
        byte[] raw = bytes(charStringsOffsetAt(gid), 2 * charStringOffSize);
        long from = offset(raw, 0, charStringOffSize);
        long to = offset(raw, charStringOffSize, charStringOffSize);
        if (from < 1 || to < from) {
            throw new IOException("charstring offsets out of order");
        }
        return bytes(charStringData + from, (int) (to - from));
    }

    private long charStringsOffsetAt(int gid) {
        return charStringData + 1 - (long) (charStrings + 1) * charStringOffSize + (long) gid * charStringOffSize;
    }

    private Map<Integer, List<Number>> privateDict(int fd) throws IOException {
        Map<Integer, List<Number>> d = privates.get(fd);
        if (d == null) {
            d = dict(privateAt[fd], privateSize[fd]);
            privates.put(fd, d);
        }
        return d;
    }

    private int width(int fd, int op) throws IOException {
        List<Number> v = privateDict(fd).get(op);
        return v == null || v.isEmpty() ? 0 : v.get(0).intValue();
    }

    private byte[][] localSubrs(int fd) throws IOException {
        if (localSubrs.containsKey(fd)) {
            return localSubrs.get(fd);
        }
        List<Number> subrs = privateDict(fd).get(19);
        int at = subrs == null || subrs.isEmpty() ? 0 : subrs.get(0).intValue();
        byte[][] made = at > 0 ? indexData(privateAt[fd] + at) : null;
        localSubrs.put(fd, made);
        return made;
    }

    private List<int[]> charset(long at) throws IOException {
        int format = u8(at);
        List<int[]> ranges = new ArrayList<>();
        long pos = at + 1;
        int gid = 1;
        if (format == 0) {
            byte[] raw = bytes(pos, 2 * (charStrings - 1));
            for (int g = 1; g < charStrings; g++) {
                int cid = (raw[2 * (g - 1)] & 0xFF) << 8 | raw[2 * (g - 1) + 1] & 0xFF;
                ranges.add(new int[] {g, cid, 1});
            }
            return unique(ranges);
        }
        if (format != 1 && format != 2) {
            throw new IOException("unsupported charset format " + format);
        }
        int width = format == 1 ? 3 : 4;
        while (gid < charStrings) {
            byte[] raw = bytes(pos, width);
            int first = (raw[0] & 0xFF) << 8 | raw[1] & 0xFF;
            int left = format == 1 ? raw[2] & 0xFF : (raw[2] & 0xFF) << 8 | raw[3] & 0xFF;
            ranges.add(new int[] {gid, first, left + 1});
            gid += left + 1;
            pos += width;
        }
        return unique(ranges);
    }

    private static List<int[]> unique(List<int[]> ranges) throws IOException {
        long total = 0;
        int maxCid = 0;
        for (int[] r : ranges) {
            total += r[2];
            maxCid = Math.max(maxCid, r[1] + r[2]);
        }
        if (total > 1 << 20 || maxCid > 1 << 20) {
            throw new IOException("charset out of range");
        }
        BitSet seen = new BitSet(maxCid + 1);
        seen.set(0);
        for (int[] r : ranges) {
            if (seen.nextSetBit(r[1]) >= 0 && seen.nextSetBit(r[1]) < r[1] + r[2]) {
                throw new IOException("the charset maps a CID twice");
            }
            seen.set(r[1], r[1] + r[2]);
        }
        List<int[]> merged = new ArrayList<>();
        for (int[] r : ranges) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && last[0] + last[2] == r[0] && last[1] + last[2] == r[1]) {
                last[2] += r[2];
            } else {
                merged.add(r.clone());
            }
        }
        return merged;
    }

    private long[] index(long at) throws IOException {
        return indexItem(at, 0);
    }

    private long[] indexItem(long at, int item) throws IOException {
        int count = u16(at);
        if (item >= count) {
            throw new IOException("INDEX item " + item + " is missing");
        }
        int offSize = u8(at + 2);
        byte[] raw = bytes(at + 3 + (long) item * offSize, 2 * offSize);
        long dataAt = at + 3 + (long) (count + 1) * offSize - 1;
        return new long[] {dataAt + offset(raw, 0, offSize), dataAt + offset(raw, offSize, offSize)};
    }

    private long next(long at) throws IOException {
        int count = u16(at);
        if (count == 0) {
            return at + 2;
        }
        int offSize = u8(at + 2);
        byte[] raw = bytes(at + 3 + (long) count * offSize, offSize);
        return at + 3 + (long) (count + 1) * offSize - 1 + offset(raw, 0, offSize);
    }

    private byte[][] indexData(long at) throws IOException {
        int count = u16(at);
        if (count == 0) {
            return null;
        }
        int offSize = u8(at + 2);
        byte[] raw = bytes(at + 3, (count + 1) * offSize);
        long dataAt = at + 3 + (long) (count + 1) * offSize - 1;
        long first = offset(raw, 0, offSize);
        long last = offset(raw, count * offSize, offSize);
        if (first < 1 || last < first) {
            throw new IOException("INDEX offsets out of order");
        }
        byte[] all = bytes(dataAt + first, (int) (last - first));
        byte[][] items = new byte[count][];
        long prev = first;
        for (int i = 0; i < count; i++) {
            long end = offset(raw, (i + 1) * offSize, offSize);
            if (end < prev || end > last) {
                throw new IOException("INDEX offsets out of order");
            }
            items[i] = Arrays.copyOfRange(all, (int) (prev - first), (int) (end - first));
            prev = end;
        }
        return items;
    }

    private static long offset(byte[] raw, int at, int size) throws IOException {
        if (size < 1 || size > 4) {
            throw new IOException("bad INDEX offset size " + size);
        }
        long v = 0;
        for (int i = 0; i < size; i++) {
            v = v << 8 | raw[at + i] & 0xFF;
        }
        return v;
    }

    private Map<Integer, List<Number>> dict(long at, int size) throws IOException {
        byte[] raw = bytes(at, size);
        Map<Integer, List<Number>> out = new HashMap<>();
        List<Number> operands = new ArrayList<>();
        int i = 0;
        while (i < raw.length) {
            int b0 = raw[i] & 0xFF;
            if (b0 <= 21) {
                int op = b0;
                i++;
                if (b0 == 12) {
                    if (i >= raw.length) {
                        throw new IOException("truncated DICT operator");
                    }
                    op = 1200 + (raw[i++] & 0xFF);
                }
                out.put(op, operands);
                operands = new ArrayList<>();
            } else if (b0 == 28) {
                need(raw, i, 3);
                operands.add((int) (short) ((raw[i + 1] & 0xFF) << 8 | raw[i + 2] & 0xFF));
                i += 3;
            } else if (b0 == 29) {
                need(raw, i, 5);
                operands.add((raw[i + 1] & 0xFF) << 24 | (raw[i + 2] & 0xFF) << 16 | (raw[i + 3] & 0xFF) << 8
                        | raw[i + 4] & 0xFF);
                i += 5;
            } else if (b0 == 30) {
                i = real(raw, i + 1, operands);
            } else if (b0 >= 32 && b0 <= 246) {
                operands.add(b0 - 139);
                i++;
            } else if (b0 >= 247 && b0 <= 250) {
                need(raw, i, 2);
                operands.add((b0 - 247) * 256 + (raw[i + 1] & 0xFF) + 108);
                i += 2;
            } else if (b0 >= 251 && b0 <= 254) {
                need(raw, i, 2);
                operands.add(-(b0 - 251) * 256 - (raw[i + 1] & 0xFF) - 108);
                i += 2;
            } else {
                throw new IOException("reserved DICT byte " + b0);
            }
        }
        return out;
    }

    private static int real(byte[] raw, int i, List<Number> operands) throws IOException {
        StringBuilder s = new StringBuilder();
        while (true) {
            if (i >= raw.length) {
                throw new IOException("truncated real number");
            }
            int b = raw[i++] & 0xFF;
            for (int nibble : new int[] {b >> 4, b & 0xF}) {
                switch (nibble) {
                    case 0xA -> s.append('.');
                    case 0xB -> s.append('E');
                    case 0xC -> s.append("E-");
                    case 0xE -> s.append('-');
                    case 0xF -> {
                        try {
                            operands.add(s.isEmpty() ? 0.0 : Double.parseDouble(s.toString()));
                        } catch (NumberFormatException e) {
                            throw new IOException("bad real number " + s, e);
                        }
                        return i;
                    }
                    case 0xD -> throw new IOException("reserved real nibble");
                    default -> s.append((char) ('0' + nibble));
                }
            }
        }
    }

    private static void need(byte[] raw, int i, int n) throws IOException {
        if (i + n > raw.length) {
            throw new IOException("truncated DICT operand");
        }
    }

    private static long number(Map<Integer, List<Number>> dict, int op) throws IOException {
        List<Number> v = dict.get(op);
        if (v == null || v.isEmpty()) {
            throw new IOException("DICT entry " + op + " is missing");
        }
        return v.get(0).longValue();
    }

    private int u8(long pos) throws IOException {
        return bytes(pos, 1)[0] & 0xFF;
    }

    private int u16(long pos) throws IOException {
        byte[] b = bytes(pos, 2);
        return (b[0] & 0xFF) << 8 | b[1] & 0xFF;
    }

    private byte[] bytes(long pos, int len) throws IOException {
        if (pos < 0 || len < 0 || pos + len > length) {
            throw new IOException("CFF data out of range");
        }
        byte[] out = new byte[len];
        int done = 0;
        while (done < len) {
            long at = pos + done;
            int page = (int) (at / PAGE);
            byte[] p = page(page);
            int in = (int) (at - (long) page * PAGE);
            int n = Math.min(len - done, p.length - in);
            System.arraycopy(p, in, out, done, n);
            done += n;
        }
        return out;
    }

    private byte[] page(int page) throws IOException {
        byte[] p = pages.get(page);
        if (p != null) {
            return p;
        }
        long from = (long) page * PAGE;
        int n = (int) Math.min(PAGE, length - from);
        p = new byte[n];
        if (data != null) {
            if (base + from + n > data.length) {
                throw new IOException("CFF data out of range");
            }
            System.arraycopy(data, (int) (base + from), p, 0, n);
        } else {
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                raf.seek(base + from);
                raf.readFully(p);
            }
        }
        pages.put(page, p);
        return p;
    }
}
