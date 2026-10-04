package stirling.software.officeconvert.topdf.grid;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.JulianFields;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.SourceFile;

/** dBASE and FoxPro tables (.dbf): the field names as a heading row, then each record that is not deleted. Memo
 * fields live in a separate file and are left empty. */
public final class Dbf {

    private record Field(String name, char type, int length, int decimals) {}

    private static final int MAX_FIELDS = 2048;

    private static final double DATE_TIME_WIDTH = 16;

    private static final String UNREADABLE = "Some fields of the dBASE table do not fit its records and were left out";

    private Dbf() {}

    /** Whether the file has a dBASE header that agrees with its size. */
    public static boolean is(Path file) throws IOException {
        byte[] h;
        try (InputStream in = SourceFile.open(file)) {
            h = in.readNBytes(32);
        }
        if (h.length < 32) {
            return false;
        }
        int version = h[0] & 0xFF;
        if (version != 0x02 && version != 0x03 && version != 0x30 && version != 0x31 && version != 0x32
                && version != 0x43 && version != 0x63 && version != 0x83 && version != 0x8B && version != 0xCB
                && version != 0xF5 && version != 0xFB) {
            return false;
        }
        int month = h[2] & 0xFF;
        int day = h[3] & 0xFF;
        long records = u32(h, 4);
        int header = u16(h, 8);
        int record = u16(h, 10);
        long size = SourceFile.size(file);
        return month <= 12 && day <= 31 && header >= 33 && record >= 1 && header + records * record <= size + 1
                && header + records * record >= size - 512;
    }

    public static Grid read(Path file) throws IOException {
        byte[] b = SourceFile.read(file, OfficeZip.Limits.DEFAULT.maxEntryBytes());
        long records = u32(b, 4);
        int header = u16(b, 8);
        int record = u16(b, 10);
        Charset cs = charset(b[29] & 0xFF);
        List<Field> fields = fields(b, header, record);
        Grid grid = new Grid();
        for (int c = 0; c < fields.size(); c++) {
            grid.value(0, c, fields.get(c).name());
            if (fields.get(c).type() == 'T') {
                grid.width(c, DATE_TIME_WIDTH);
            }
        }
        int row = 1;
        for (long i = 0; i < records && row < Grid.MAX_ROWS; i++) {
            if ((i & 0xFFF) == 0 && Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Conversion interrupted");
            }
            long start = header + i * record;
            if (start + record > b.length) {
                grid.warn(UNREADABLE);
                break;
            }
            if (b[(int) start] == '*') {
                continue;
            }
            int at = (int) start + 1;
            for (int c = 0; c < fields.size(); c++) {
                Field f = fields.get(c);
                if (at + f.length() > start + record) {
                    grid.warn(UNREADABLE);
                    break;
                }
                Grid.Cell cell = cell(f, b, at, cs);
                if (cell != null) {
                    grid.put(row, c, cell);
                }
                at += f.length();
            }
            row++;
        }
        if (row >= Grid.MAX_ROWS && records > row) {
            grid.truncated = true;
        }
        return grid;
    }

    private static List<Field> fields(byte[] b, int header, int record) {
        List<Field> plain = new ArrayList<>();
        List<Field> clipper = new ArrayList<>();
        long plainLength = 1;
        long clipperLength = 1;
        for (int at = 32; at + 32 <= Math.min(header, b.length) && b[at] != 0x0D && plain.size() < MAX_FIELDS; at += 32) {
            int end = 0;
            while (end < 11 && b[at + end] != 0) {
                end++;
            }
            String name = new String(b, at, end, StandardCharsets.ISO_8859_1).trim();
            char type = (char) (b[at + 11] & 0xFF);
            int length = b[at + 16] & 0xFF;
            int decimals = b[at + 17] & 0xFF;
            plain.add(new Field(name, type, length, type == 'C' ? 0 : decimals));
            int wide = type == 'C' ? length | decimals << 8 : length;
            clipper.add(new Field(name, type, wide, type == 'C' ? 0 : decimals));
            plainLength += length;
            clipperLength += wide;
        }
        return clipperLength == record && plainLength != record ? clipper : plain;
    }

    private static Grid.Cell cell(Field f, byte[] b, int at, Charset cs) {
        String raw = new String(b, at, f.length(), f.type() == 'C' ? cs : StandardCharsets.ISO_8859_1);
        switch (f.type()) {
            case 'C' -> {
                String s = raw.replaceAll("[\\s\u0000]+$", "");
                return s.isEmpty() ? null : new Grid.Cell(s, null, false, false, null);
            }
            case 'N', 'F' -> {
                try {
                    double d = Double.parseDouble(raw.trim());
                    String format = f.decimals() > 0 ? "0." + "0".repeat(Math.min(30, f.decimals())) : null;
                    return Double.isFinite(d) ? new Grid.Cell(d, format, false, false, null) : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            case 'D' -> {
                String s = raw.trim();
                if (s.length() != 8) {
                    return null;
                }
                try {
                    LocalDate date = LocalDate.of(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(4, 6)),
                            Integer.parseInt(s.substring(6, 8)));
                    long serial = serial(date);
                    return serial > 0 ? new Grid.Cell((double) serial, "m/d/yyyy", false, false, null)
                            : new Grid.Cell(date.toString(), null, false, false, null);
                } catch (RuntimeException e) {
                    return null;
                }
            }
            case 'T' -> {
                if (f.length() != 8) {
                    return null;
                }
                long day = i32(b, at);
                long ms = i32(b, at + 4);
                if (day <= 0 || ms < 0 || ms >= 86_400_000L) {
                    return null;
                }
                LocalDate date = LocalDate.EPOCH.with(JulianFields.JULIAN_DAY, day);
                long serial = serial(date);
                return serial > 0 ? new Grid.Cell(serial + ms / 86_400_000.0, "m/d/yyyy h:mm", false, false, null)
                        : null;
            }
            case 'L' -> {
                char c = raw.isEmpty() ? '?' : Character.toUpperCase(raw.charAt(0));
                return c == 'T' || c == 'Y' ? new Grid.Cell(Boolean.TRUE, null, false, false, null)
                        : c == 'F' || c == 'N' ? new Grid.Cell(Boolean.FALSE, null, false, false, null) : null;
            }
            case 'I' -> {
                return f.length() == 4 ? new Grid.Cell((double) i32(b, at), null, false, false, null) : null;
            }
            case 'Y' -> {
                if (f.length() != 8) {
                    return null;
                }
                long v = (u32(b, at) & 0xFFFFFFFFL) | (u32(b, at + 4) << 32);
                return new Grid.Cell(v / 10_000.0, "#,##0.00", false, false, null);
            }
            case 'B', 'O' -> {
                if (f.length() != 8) {
                    return null;
                }
                double d = Double.longBitsToDouble((u32(b, at) & 0xFFFFFFFFL) | (u32(b, at + 4) << 32));
                return Double.isFinite(d) ? new Grid.Cell(d, null, false, false, null) : null;
            }
            default -> {
                return null;
            }
        }
    }

    private static long serial(LocalDate date) {
        long days = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), date);
        return days > 60 ? days : days - 1;
    }

    private static Charset charset(int driver) {
        String name = switch (driver) {
            case 0x01 -> "IBM437";
            case 0x02 -> "IBM850";
            case 0x64 -> "IBM852";
            case 0x65 -> "IBM866";
            case 0x66 -> "IBM865";
            case 0x67 -> "IBM861";
            case 0x13, 0x7B -> "MS932";
            case 0x4D, 0x7A -> "GBK";
            case 0x4E, 0x79 -> "MS949";
            case 0x4F, 0x78 -> "Big5";
            case 0x7D -> "windows-1255";
            case 0x7E -> "windows-1256";
            case 0xC8 -> "windows-1250";
            case 0xC9 -> "windows-1251";
            case 0xCA -> "windows-1254";
            case 0xCB -> "windows-1253";
            default -> "windows-1252";
        };
        try {
            return Charset.forName(name);
        } catch (RuntimeException e) {
            return Charset.forName("windows-1252");
        }
    }

    private static int u16(byte[] b, int at) {
        return at + 1 < b.length ? (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 : 0;
    }

    private static long u32(byte[] b, int at) {
        return at + 3 < b.length ? ((b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16
                | (long) (b[at + 3] & 0xFF) << 24) : 0;
    }

    private static int i32(byte[] b, int at) {
        return (int) u32(b, at);
    }
}
