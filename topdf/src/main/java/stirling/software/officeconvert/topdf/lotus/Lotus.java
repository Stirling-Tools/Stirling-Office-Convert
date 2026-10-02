package stirling.software.officeconvert.topdf.lotus;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import stirling.software.officeconvert.topdf.grid.Grid;
import stirling.software.officeconvert.topdf.grid.GridPackage;
import stirling.software.officeconvert.topdf.io.SourceFile;

public final class Lotus {

    public static final long MAX_BYTES = 256L << 20;

    private static final int MAX_SHEETS = 256;

    private static final double[] SMALL = {5000, 500, 0.05, 0.005, 0.0005, 0.00005, 0.0625, 0.015625};

    private static final Charset LATIN = StandardCharsets.ISO_8859_1;

    private final byte[] d;

    private final boolean wide;

    private final Map<Integer, Grid> sheets = new TreeMap<>();

    private final Map<Integer, String> names = new TreeMap<>();

    private int defaultFormat = 0x71;

    private int pendingRow = -1;

    private int pendingSheet;

    private int pendingCol = -1;

    private int pendingFormat = LotusFormats.DEFAULT;

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
        if (len < 4 || op < 0x16 || op > 0x27) {
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
                    put(sheet, row, col, number(Double.longBitsToDouble(i64(v))), LotusFormats.DEFAULT);
                }
            }
            default -> {
            }
        }
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

    private Object extended(int at) {
        long mantissa = i64(at);
        int se = u16(at + 8);
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
        return new String(d, from, stop - from, LATIN);
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
