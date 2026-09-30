package stirling.software.officeconvert.topdf.xlsx;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// A sheet's cells kept packed per row, so a large sheet costs a few bytes a cell; rows in use are unpacked and cached
final class PackedCells {

    static final int CACHED_CELLS = 32_768;

    static final int CACHED_ROWS = 64;

    static final int MAX_CACHED_ROWS = 4096;

    private static final CellText.Kind[] KINDS = CellText.Kind.values();

    private static final int NOT_EMPTY = 0x40;

    private final Table<CellFormat> formats = new Table<>();

    private final Table<FontSpec> fonts = new Table<>();

    private final Table<Color> colors = new Table<>();

    private final LinkedHashMap<Grid.RowInfo, TreeMap<Integer, CellEntry>> cache = new LinkedHashMap<>(256, 0.75f,
            true);

    private int cachedCells;

    private byte[] buffer = new byte[256];

    private int size;

    private static final class Table<T> {

        final List<T> values = new ArrayList<>();

        final Map<T, Integer> ids = new IdentityHashMap<>();

        int id(T value) {
            Integer id = ids.get(value);
            if (id == null) {
                id = values.size();
                values.add(value);
                ids.put(value, id);
            }
            return id;
        }

        T at(int id) {
            return values.get(id);
        }
    }

    TreeMap<Integer, CellEntry> cells(Grid.RowInfo row) {
        if (row.cols == null) {
            return new TreeMap<>();
        }
        TreeMap<Integer, CellEntry> cells = cache.get(row);
        if (cells == null) {
            cells = unpack(row);
            remember(row, cells);
        }
        return cells;
    }

    void store(Grid.RowInfo row, TreeMap<Integer, CellEntry> cells) {
        pack(row, cells.values());
        TreeMap<Integer, CellEntry> old = cache.remove(row);
        if (old != null) {
            cachedCells -= old.size();
        }
        if (row.cols != null) {
            remember(row, cells);
        }
    }

    private void remember(Grid.RowInfo row, TreeMap<Integer, CellEntry> cells) {
        cache.put(row, cells);
        cachedCells += cells.size();
        Iterator<Map.Entry<Grid.RowInfo, TreeMap<Integer, CellEntry>>> it = cache.entrySet().iterator();
        while ((cachedCells > CACHED_CELLS || cache.size() > MAX_CACHED_ROWS) && cache.size() > CACHED_ROWS
                && it.hasNext()) {
            Map.Entry<Grid.RowInfo, TreeMap<Integer, CellEntry>> e = it.next();
            if (e.getKey() == row) {
                continue;
            }
            cachedCells -= e.getValue().size();
            it.remove();
        }
    }

    // A single cell or its format is found by its column without unpacking the row or touching the cache
    CellEntry cell(Grid.RowInfo row, int col) {
        int i = row.cols == null || col < 0 || col > Character.MAX_VALUE ? -1 : Arrays.binarySearch(row.cols,
                (char) col);
        return i < 0 ? null : new CellEntry(row.index, col, formats.at(row.formats[i]),
                readText(row.packed, new int[] {row.texts[i]}));
    }

    // The cells of some columns, in column order, read from the packed row: a page reads its own columns only
    List<CellEntry> cells(Grid.RowInfo row, int first, int last) {
        if (row.cols == null || first > last || last < 0) {
            return List.of();
        }
        TreeMap<Integer, CellEntry> cached = cache.get(row);
        if (cached != null) {
            return new ArrayList<>(cached.subMap(first, true, last, true).values());
        }
        int i = Arrays.binarySearch(row.cols, (char) Math.max(0, Math.min(first, Character.MAX_VALUE)));
        List<CellEntry> out = new ArrayList<>();
        int[] at = {0};
        for (i = i < 0 ? -i - 1 : i; i < row.cols.length && row.cols[i] <= last; i++) {
            at[0] = row.texts[i];
            out.add(new CellEntry(row.index, row.cols[i], formats.at(row.formats[i]), readText(row.packed, at)));
        }
        return out;
    }

    CellFormat format(Grid.RowInfo row, int col) {
        int i = row.cols == null || col < 0 || col > Character.MAX_VALUE ? -1 : Arrays.binarySearch(row.cols,
                (char) col);
        return i < 0 ? null : formats.at(row.formats[i]);
    }

    boolean hasText(Grid.RowInfo row, int i) {
        return (row.packed[row.texts[i]] & NOT_EMPTY) != 0;
    }

    // Columns, formats and where each cell's text starts are kept as arrays, the texts themselves as bytes
    private void pack(Grid.RowInfo row, Collection<CellEntry> cells) {
        int n = cells.size();
        if (n == 0) {
            row.cols = null;
            row.formats = null;
            row.texts = null;
            row.packed = null;
            return;
        }
        char[] cols = new char[n];
        int[] ids = new int[n];
        int[] at = new int[n];
        size = 0;
        int i = 0;
        for (CellEntry e : cells) {
            cols[i] = (char) e.col();
            ids[i] = formats.id(e.format());
            at[i] = size;
            i++;
            CellText t = e.text();
            if (t == null) {
                write(0);
                continue;
            }
            boolean number = Double.doubleToRawLongBits(t.number()) != 0;
            write(1 | (t.color() != null ? 2 : 0) | (t.general() ? 4 : 0) | (number ? 8 : 0) | t.kind().ordinal() << 4
                    | (t.isEmpty() ? 0 : NOT_EMPTY));
            if (t.color() != null) {
                varint(colors.id(t.color()));
            }
            if (number) {
                long bits = Double.doubleToRawLongBits(t.number());
                for (int k = 0; k < 8; k++) {
                    write((int) (bits >>> (k * 8)));
                }
            }
            varint(t.runs().size());
            for (TextRun r : t.runs()) {
                varint(fonts.id(r.font()));
                string(r.text());
            }
        }
        row.cols = cols;
        row.formats = ids;
        row.texts = at;
        row.packed = Arrays.copyOf(buffer, size);
    }

    private TreeMap<Integer, CellEntry> unpack(Grid.RowInfo row) {
        TreeMap<Integer, CellEntry> cells = new TreeMap<>();
        int[] at = {0};
        for (int i = 0; i < row.cols.length; i++) {
            at[0] = row.texts[i];
            cells.put((int) row.cols[i], new CellEntry(row.index, row.cols[i], formats.at(row.formats[i]),
                    readText(row.packed, at)));
        }
        return cells;
    }

    private CellText readText(byte[] data, int[] at) {
        int flags = data[at[0]++] & 0xFF;
        if ((flags & 1) == 0) {
            return null;
        }
        Color color = (flags & 2) != 0 ? colors.at(readVarint(data, at)) : null;
        long bits = 0;
        if ((flags & 8) != 0) {
            for (int i = 0; i < 8; i++) {
                bits |= (long) (data[at[0]++] & 0xFF) << (i * 8);
            }
        }
        int n = readVarint(data, at);
        TextRun[] runs = new TextRun[n];
        for (int i = 0; i < n; i++) {
            FontSpec font = fonts.at(readVarint(data, at));
            runs[i] = new TextRun(readString(data, at), font);
        }
        return new CellText(KINDS[flags >>> 4 & 3], List.of(runs), color, (flags & 4) != 0,
                Double.longBitsToDouble(bits));
    }

    private void string(String s) {
        if (s == null) {
            varint(0);
            return;
        }
        boolean narrow = true;
        for (int i = 0; i < s.length() && narrow; i++) {
            narrow = s.charAt(i) < 0x100;
        }
        varint((s.length() + 1) << 1 | (narrow ? 1 : 0));
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!narrow) {
                write(c >>> 8);
            }
            write(c);
        }
    }

    private static String readString(byte[] data, int[] at) {
        int head = readVarint(data, at);
        if (head == 0) {
            return null;
        }
        int length = (head >>> 1) - 1;
        if ((head & 1) != 0) {
            String s = new String(data, at[0], length, StandardCharsets.ISO_8859_1);
            at[0] += length;
            return s;
        }
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = (char) ((data[at[0]] & 0xFF) << 8 | data[at[0] + 1] & 0xFF);
            at[0] += 2;
        }
        return new String(chars);
    }

    private void varint(int v) {
        while ((v & ~0x7F) != 0) {
            write(v & 0x7F | 0x80);
            v >>>= 7;
        }
        write(v);
    }

    private static int readVarint(byte[] data, int[] at) {
        int v = 0;
        for (int shift = 0; ; shift += 7) {
            int b = data[at[0]++] & 0xFF;
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return v;
            }
        }
    }

    private void write(int b) {
        if (size == buffer.length) {
            buffer = Arrays.copyOf(buffer, buffer.length * 2);
        }
        buffer[size++] = (byte) b;
    }
}
