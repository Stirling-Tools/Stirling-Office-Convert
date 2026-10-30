package stirling.software.officeconvert.topdf.lotus;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

import stirling.software.officeconvert.topdf.grid.Grid;
import stirling.software.officeconvert.topdf.grid.GridPackage;
import stirling.software.officeconvert.topdf.io.SourceFile;

public final class Lotus {

    public static final long MAX_BYTES = 256L << 20;

    private static final int MAX_SHEETS = 256;

    private static final int MAX_LEVELS = 64;

    private static final int MAX_RULES = 1 << 18;

    private static final long MAX_RESTYLED = 20_000_000;

    private static final double[] SMALL = {5000, 500, 0.05, 0.005, 0.0005, 0.00005, 0.0625, 0.015625};

    private final byte[] d;

    private final boolean wide;

    private final Map<Integer, Grid> sheets = new TreeMap<>();

    private final Map<Integer, String> names = new TreeMap<>();

    private int defaultFormat = 0x71;

    private int pendingRow = -1;

    private int pendingSheet;

    private int pendingCol = -1;

    private int pendingFormat = LotusFormats.DEFAULT;

    private boolean unreadableText;

    private final Map<Long, List<int[]>> rowFormats = new HashMap<>();

    private int spans;

    private int rowDefault = 0x71;

    private final Map<Integer, Integer> angles = new HashMap<>();

    private final Map<Integer, Integer> parents = new HashMap<>();

    private final Map<Integer, Integer> namedStyles = new HashMap<>();

    private final List<int[]> levels = new ArrayList<>();

    private int deeper;

    private final List<Rule> rules = new ArrayList<>();

    private final List<int[]> widths = new ArrayList<>();

    private final List<int[]> aligned = new ArrayList<>();

    private record Rule(int sheet, int lastSheet, int col, int lastCol, int row, int lastRow, String format,
            int rotation) {}

    private Lotus(byte[] d) {
        this.d = d;
        this.wide = u16(4) >= 0x1000;
    }

    public static boolean is(Path file) {
        try (InputStream in = SourceFile.open(file)) {
            return is(in.readNBytes(6));
        } catch (IOException e) {
            return false;
        }
    }

    static boolean is(byte[] h) {
        if (h.length < 6 || h[0] != 0 || h[1] != 0) {
            return false;
        }
        int len = (h[2] & 0xFF) | (h[3] & 0xFF) << 8;
        int version = (h[4] & 0xFF) | (h[5] & 0xFF) << 8;
        return len == 2 && version >= 0x0404 && version <= 0x0406
                || len == 26 && version >= 0x1000 && version <= 0x1005;
    }

    public static List<GridPackage.Sheet> read(Path file) throws IOException {
        if (SourceFile.size(file) > MAX_BYTES) {
            throw new IOException("The Lotus 1-2-3 file is too large");
        }
        Lotus l = new Lotus(SourceFile.read(file, MAX_BYTES));
        if (!is(l.d)) {
            throw new IOException("The file is not a Lotus 1-2-3 worksheet");
        }
        l.records();
        l.restyle();
        List<GridPackage.Sheet> out = new ArrayList<>();
        for (Map.Entry<Integer, Grid> e : l.sheets.entrySet()) {
            String name = l.names.get(e.getKey());
            if (name == null && l.wide) {
                name = column(e.getKey());
            }
            out.add(new GridPackage.Sheet(name, e.getValue()));
        }
        if (out.isEmpty()) {
            out.add(new GridPackage.Sheet(l.wide ? "A" : null, new Grid()));
        }
        if (l.unreadableText) {
            out.get(0).grid().warn("Some double-byte text of the Lotus 1-2-3 file could not be read and was left out");
        }
        return out;
    }

    private void records() throws IOException {
        int at = 0;
        int count = 0;
        while (at + 4 <= d.length) {
            if ((++count & 4095) == 0 && Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Conversion interrupted");
            }
            int op = u16(at);
            int len = u16(at + 2);
            int body = at + 4;
            if (body + len > d.length) {
                break;
            }
            if (op == 0x01 && count > 1) {
                break;
            }
            if (wide) {
                wide(op, body, len);
            } else {
                narrow(op, body, len);
            }
            at = body + len;
        }
    }

    private void narrow(int op, int at, int len) {
        switch (op) {
            case 0x07 -> {
                if (len >= 8) {
                    defaultFormat = d[at + 4] & 0xFF;
                    grid(0).defaultWidth(Math.max(1, u16(at + 6)));
                }
            }
            case 0x08 -> {
                if (len >= 3) {
                    grid(0).width(u16(at), d[at + 2] & 0xFF);
                }
            }
            case 0x64 -> hiddenColumns(at, len);
            case 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x33 -> {
                if (len < 5) {
                    return;
                }
                int format = d[at] & 0xFF;
                int col = u16(at + 1);
                int row = u16(at + 3);
                Object value = switch (op) {
                    case 0x0D -> len >= 7 ? (double) (short) u16(at + 5) : null;
                    case 0x0E, 0x10 -> len >= 13 ? number(Double.longBitsToDouble(i64(at + 5))) : null;
                    case 0x0F -> null;
                    case 0x33 -> text(at + 5, at + len);
                    default -> null;
                };
                if (op == 0x0F) {
                    label(0, row, col, at + 5, at + len, format);
                } else if (op == 0x33 && row == pendingRow && col == pendingCol) {
                    put(0, row, col, value, pendingFormat);
                } else if (op != 0x33) {
                    put(0, row, col, value, format);
                    pendingRow = row;
                    pendingCol = col;
                    pendingFormat = format;
                }
            }
            default -> {
            }
        }
    }

    private void wide(int op, int at, int len) {
        if (op == 0x23 && len >= 5) {
            int sheet = u16(at + 2);
            String name = text(at + 4, at + len);
            if (sheet < MAX_SHEETS && !name.isBlank()) {
                names.put(sheet, name);
            }
            return;
        }
        if (op >= 0x100) {
            layout(op, at, len);
            return;
        }
        if (op == 0x07) {
            columnWidths(at, len);
            return;
        }
        if (op == 0x13) {
            rowFormat(at, len);
            return;
        }
        if (op == 0x1B) {
            cellStyle(at, len);
            return;
        }
        if (len < 4 || op < 0x16 || op > 0x28) {
            return;
        }
        int row = u16(at);
        int sheet = d[at + 2] & 0xFF;
        int col = d[at + 3] & 0xFF;
        int v = at + 4;
        int end = at + len;
        switch (op) {
            case 0x16 -> label(sheet, row, col, v, end, LotusFormats.DEFAULT);
            case 0x17 -> {
                if (len >= 14) {
                    put(sheet, row, col, extended(v), LotusFormats.DEFAULT);
                }
            }
            case 0x18 -> {
                if (len >= 6) {
                    put(sheet, row, col, small((short) u16(v)), LotusFormats.DEFAULT);
                }
            }
            case 0x19 -> {
                if (len >= 14) {
                    put(sheet, row, col, extended(v), LotusFormats.DEFAULT);
                    pendingRow = row;
                    pendingSheet = sheet;
                    pendingCol = col;
                }
            }
            case 0x1A -> {
                if (row == pendingRow && sheet == pendingSheet && col == pendingCol) {
                    put(sheet, row, col, text(v, end), LotusFormats.DEFAULT);
                }
            }
            case 0x25 -> {
                if (len >= 8) {
                    put(sheet, row, col, compressed(i32(v)), LotusFormats.DEFAULT);
                }
            }
            case 0x27 -> {
                if (len >= 12) {
                    put(sheet, row, col, real(v), LotusFormats.DEFAULT);
                }
            }
            case 0x28 -> {
                if (len >= 12) {
                    put(sheet, row, col, real(v), LotusFormats.DEFAULT);
                    pendingRow = row;
                    pendingSheet = sheet;
                    pendingCol = col;
                }
            }
            default -> {
            }
        }
    }

    private void layout(int op, int at, int len) {
        switch (op) {
            case 0x106 -> {
                if (levels.size() < MAX_LEVELS) {
                    levels.add(new int[2]);
                } else {
                    deeper++;
                }
            }
            case 0x107 -> {
                if (deeper > 0) {
                    deeper--;
                } else if (!levels.isEmpty()) {
                    levels.remove(levels.size() - 1);
                }
            }
            case 0x800 -> {
                if (!levels.isEmpty() && deeper == 0 && (len == 2 || len == 4)) {
                    long count = len == 2 ? u16(at) : i32(at) & 0xFFFFFFFFL;
                    int[] level = levels.get(levels.size() - 1);
                    level[0] = level[1];
                    level[1] = (int) Math.min(Integer.MAX_VALUE, level[1] + count);
                }
            }
            case 0x801 -> region(at, len);
            default -> {
            }
        }
    }

    private void region(int at, int len) {
        if (levels.size() < 4 || deeper > 0 || rules.size() >= MAX_RULES) {
            return;
        }
        String format = null;
        int rotation = 0;
        if (len == 4) {
            format = LotusFormats.millennium(d[at] & 0xFF, d[at + 2] & 0xFF, d[at + 3] & 0xFF);
        } else if (len == 2 && (d[at + 1] & 0xFF) == 0x50) {
            rotation = rotation(d[at] & 0xFF);
        }
        if (format == null && rotation == 0) {
            return;
        }
        int[] s = levels.get(1);
        int[] c = levels.get(2);
        int[] r = levels.get(3);
        rules.add(new Rule(s[0], s[1] - 1, c[0], c[1] - 1, r[0], r[1] - 1, format, rotation));
    }

    private int rotation(int style) {
        Integer own = angles.get(style);
        if (own != null) {
            return own;
        }
        Integer parent = parents.containsKey(style) ? namedStyles.get(parents.get(style)) : null;
        return parent == null ? 0 : angles.getOrDefault(parent, 0);
    }

    private void cellStyle(int at, int len) {
        if (len == 10 && u16(at) == 0xFE6 && (d[at + 3] & 0xFF) == 0x60 && (d[at + 9] & 0xFF) == 0x50) {
            namedStyles.put(d[at + 2] & 0xFF, d[at + 8] & 0xFF);
            return;
        }
        if (len != 35 || u16(at) != 0xFD2 || (d[at + 3] & 0xFF) != 0x50) {
            return;
        }
        if ((d[at + 30] & 0xFF) == 0x60) {
            parents.put(d[at + 2] & 0xFF, d[at + 29] & 0xFF);
        }
        int align = d[at + 20] & 0xFF;
        int rotation = switch (align == 0xFF ? 0 : align >> 4 & 7) {
            case 3 -> 90;
            case 4, 5 -> 180;
            case 6 -> slant(u16(at + 22));
            default -> 0;
        };
        if (rotation != 0) {
            angles.put(d[at + 2] & 0xFF, rotation);
        }
    }

    private static int slant(int degrees) {
        int a = degrees == 3600 ? 45 : degrees % 360;
        return a > 0 && a <= 90 ? a : a >= 270 ? 450 - a : 0;
    }

    private void columnWidths(int at, int len) {
        for (int p = at + 4; p + 1 < at + len && widths.size() < MAX_RULES; p += 2) {
            widths.add(new int[] {d[at] & 0xFF, d[p] & 0xFF, d[p + 1] & 0xFF});
        }
    }

    private void rowFormat(int at, int len) {
        if (len < 4) {
            return;
        }
        int sheet = d[at] & 0xFF;
        long key = (long) sheet << 32 | u16(at + 2);
        switch (d[at + 1] & 0xFF) {
            case 0 -> {
                List<int[]> row = new ArrayList<>();
                int col = 0;
                int p = at + 4;
                int end = at + len;
                while (p + 4 <= end && col < Grid.MAX_COLS && spans < MAX_RULES) {
                    int code = d[p] & 0xFF;
                    int count = 1;
                    boolean repeated = (d[p + 3] & 0x80) != 0;
                    p += 4;
                    if (repeated) {
                        if (p >= end) {
                            break;
                        }
                        count += d[p++] & 0xFF;
                    }
                    row.add(new int[] {col, col + count - 1, code});
                    spans++;
                    col += count;
                }
                rowFormats.put(key, row);
            }
            case 1 -> {
                if (len >= 8) {
                    rowDefault = d[at + 4] & 0xFF;
                }
            }
            case 2 -> {
                List<int[]> row = len >= 8 ? rowFormats.get((long) (d[at + 4] & 0xFF) << 32 | u16(at + 6)) : null;
                if (row != null && spans + row.size() <= MAX_RULES) {
                    rowFormats.put(key, row);
                    spans += row.size();
                }
            }
            default -> {
            }
        }
    }

    private void restyle() {
        for (int[] w : widths) {
            Grid g = sheets.get(w[0]);
            if (g != null) {
                g.width(w[1], w[2]);
            }
        }
        for (int[] a : aligned) {
            Grid g = sheets.get(a[0]);
            if (a[3] > g.width(a[2])) {
                g.put(a[1], a[2], new Grid.Cell(null, null, false, false, "left"));
            }
        }
        long budget = MAX_RESTYLED;
        for (Map.Entry<Long, List<int[]>> e : rowFormats.entrySet()) {
            Grid g = sheets.get((int) (e.getKey() >>> 32));
            int row = (int) (e.getKey() & 0xFFFF);
            for (int[] span : e.getValue()) {
                if (g == null || budget <= 0) {
                    break;
                }
                int code = (span[2] & 0x7F) == 0x7F ? rowDefault : span[2];
                String excel = LotusFormats.excel(code);
                budget -= g.restyle(row, row, span[0], span[1], numbers(excel == null ? "General" : excel));
            }
        }
        for (Rule r : rules) {
            for (int s = Math.max(0, r.sheet()); s <= Math.min(r.lastSheet(), MAX_SHEETS - 1) && budget > 0; s++) {
                Grid g = sheets.get(s);
                if (g != null) {
                    budget -= g.restyle(r.row(), r.lastRow(), r.col(), r.lastCol(), r.format() != null
                            ? numbers(r.format()) : turned(r.rotation()));
                }
            }
        }
    }

    private static UnaryOperator<Grid.Cell> numbers(String format) {
        return c -> c.value() instanceof Double
                ? new Grid.Cell(c.value(), format, c.bold(), c.italic(), c.align(), c.rotation()) : c;
    }

    private static UnaryOperator<Grid.Cell> turned(int rotation) {
        return c -> new Grid.Cell(c.value(), c.format(), c.bold(), c.italic(), c.align(), rotation);
    }

    private void hiddenColumns(int at, int len) {
        for (int i = 0; i < Math.min(len, 32); i++) {
            int bits = d[at + i] & 0xFF;
            for (int b = 0; b < 8; b++) {
                if ((bits & 1 << b) != 0) {
                    grid(0).width(i * 8 + b, 0);
                }
            }
        }
    }

    private Grid grid(int sheet) {
        return sheets.computeIfAbsent(sheet, k -> new Grid());
    }

    private void label(int sheet, int row, int col, int from, int end, int format) {
        String s = text(from, end);
        String align = null;
        if (!s.isEmpty()) {
            switch (s.charAt(0)) {
                case '\'' -> s = s.substring(1);
                case '"' -> {
                    s = s.substring(1);
                    align = "right";
                }
                case '^' -> {
                    s = s.substring(1);
                    align = "center";
                }
                case '\\' -> {
                    s = s.substring(1);
                    align = "fill";
                }
                case '|' -> s = "";
                default -> {
                }
            }
        }
        if (sheet >= MAX_SHEETS || s.isEmpty() || LotusFormats.hidden(effective(format))) {
            return;
        }
        grid(sheet).put(row, col, new Grid.Cell(s, null, false, false, align));
        if (wide && align != null && !align.equals("fill") && aligned.size() < MAX_RULES) {
            aligned.add(new int[] {sheet, row, col, s.length()});
        }
    }

    private int effective(int format) {
        return (format & 0x7F) == 0x7F ? defaultFormat : format;
    }

    private void put(int sheet, int row, int col, Object value, int format) {
        if (sheet >= MAX_SHEETS || value == null) {
            return;
        }
        int f = effective(format);
        if (LotusFormats.hidden(f)) {
            return;
        }
        String excel = value instanceof Double ? LotusFormats.excel(f) : null;
        grid(sheet).put(row, col, new Grid.Cell(value, excel, false, false, null));
    }

    private static Object number(double v) {
        return Double.isFinite(v) ? Double.valueOf(v) : new Grid.Error("#N/A");
    }

    private Object real(int at) {
        if (u16(at + 6) == 0xFFFF && special(d[at + 5] & 0xFF)) {
            return specialValue(d[at + 5] & 0xFF);
        }
        return number(Double.longBitsToDouble(i64(at)));
    }

    private static boolean special(int tag) {
        return tag == 0x00 || tag == 0xC0 || tag == 0xD0 || tag == 0xE0;
    }

    private static Object specialValue(int tag) {
        return tag == 0xC0 ? new Grid.Error("#VALUE!") : tag == 0xD0 ? new Grid.Error("#N/A") : null;
    }

    private Object extended(int at) {
        long mantissa = i64(at);
        int se = u16(at + 8);
        if (se == 0xFFFF && special(d[at + 7] & 0xFF)) {
            return specialValue(d[at + 7] & 0xFF);
        }
        int exp = se & 0x7FFF;
        if (exp == 0x7FFF) {
            return new Grid.Error("#N/A");
        }
        if (exp == 0 && mantissa == 0) {
            return 0.0;
        }
        double m = (mantissa >>> 11) / (double) (1L << 52);
        double v = m * Math.pow(2, exp - 16383);
        return number((se & 0x8000) != 0 ? -v : v);
    }

    static double small(short v) {
        if ((v & 1) == 0) {
            return v >> 1;
        }
        return (v >> 4) * SMALL[(v >> 1) & 7];
    }

    static double compressed(int v) {
        int low = v & 0x3F;
        double n = (v >>> 6);
        int e = low & 0x0F;
        double scaled = (low & 0x10) != 0 ? n / Math.pow(10, e) : n * Math.pow(10, e);
        return (low & 0x20) != 0 ? -scaled : scaled;
    }

    private String text(int from, int end) {
        int stop = from;
        while (stop < end && d[stop] != 0) {
            stop++;
        }
        if (!wide) {
            return LotusText.lics(d, from, stop);
        }
        return LotusText.lmbcs(d, from, stop, () -> unreadableText = true);
    }

    private static String column(int n) {
        StringBuilder b = new StringBuilder();
        int k = n + 1;
        while (k > 0) {
            b.insert(0, (char) ('A' + (k - 1) % 26));
            k = (k - 1) / 26;
        }
        return b.toString();
    }

    private int u16(int at) {
        return at + 1 < d.length ? (d[at] & 0xFF) | (d[at + 1] & 0xFF) << 8 : 0;
    }

    private int i32(int at) {
        return u16(at) | u16(at + 2) << 16;
    }

    private long i64(int at) {
        return (i32(at) & 0xFFFFFFFFL) | (long) i32(at + 4) << 32;
    }
}
