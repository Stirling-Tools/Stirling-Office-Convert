package stirling.software.officeconvert.topdf.grid;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import stirling.software.officeconvert.topdf.io.SourceFile;

/** SYLK (Symbolic Link) spreadsheets: C records for cells (cached values, the E formulas ignored), F records for
 * their formats, bold and italic, and column widths, P records for number formats. */
public final class Sylk {

    private static final int MAX_LINE = 1 << 16;

    private static final int MAX_LINES = 20_000_000;

    private Sylk() {}

    /** Whether the file starts with a SYLK ID record. */
    public static boolean is(Path file) throws IOException {
        try (InputStream in = SourceFile.open(file)) {
            byte[] head = in.readNBytes(4);
            return head.length >= 3 && head[0] == 'I' && head[1] == 'D' && head[2] == ';';
        }
    }

    public static Grid read(Path file) throws IOException {
        Grid grid = new Grid();
        List<String> pictures = new ArrayList<>();
        int x = 1;
        int y = 1;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(SourceFile.open(file),
                Charset.forName("windows-1252")), 1 << 16)) {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null) {
                if (++n > MAX_LINES) {
                    grid.truncated = true;
                    break;
                }
                if ((n & 0xFFF) == 0 && Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Conversion interrupted");
                }
                if (line.length() > MAX_LINE) {
                    line = line.substring(0, MAX_LINE);
                }
                List<String> fields = fields(line);
                if (fields.isEmpty()) {
                    continue;
                }
                String type = fields.get(0);
                if (type.equals("E")) {
                    break;
                }
                switch (type) {
                    case "P" -> {
                        for (String f : fields.subList(1, fields.size())) {
                            if (f.startsWith("P")) {
                                pictures.add(f.substring(1));
                            }
                        }
                    }
                    case "C" -> {
                        int[] at = position(fields, x, y);
                        x = at[0];
                        y = at[1];
                        for (String f : fields.subList(1, fields.size())) {
                            if (f.startsWith("K")) {
                                grid.value(y - 1, x - 1, value(f.substring(1)));
                            }
                        }
                    }
                    case "F" -> format(grid, fields, pictures, x, y);
                    default -> {
                    }
                }
                if (type.equals("F")) {
                    int[] at = position(fields, x, y);
                    x = at[0];
                    y = at[1];
                }
            }
        }
        return grid;
    }

    private static int[] position(List<String> fields, int x, int y) {
        for (String f : fields) {
            if (f.length() > 1 && f.charAt(0) == 'X') {
                x = number(f.substring(1), x);
            } else if (f.length() > 1 && f.charAt(0) == 'Y') {
                y = number(f.substring(1), y);
            }
        }
        return new int[] {x, y};
    }

    private static void format(Grid grid, List<String> fields, List<String> pictures, int lastX, int lastY) {
        String picture = null;
        boolean bold = false;
        boolean italic = false;
        String align = null;
        boolean cell = false;
        int[] at = position(fields, lastX, lastY);
        for (String f : fields.subList(1, fields.size())) {
            if (f.isEmpty()) {
                continue;
            }
            switch (f.charAt(0)) {
                case 'P' -> {
                    int i = number(f.substring(1), -1);
                    picture = i >= 0 && i < pictures.size() ? pictures.get(i) : null;
                }
                case 'F' -> {
                    align = align(f);
                    if (picture == null) {
                        picture = fixed(f);
                    }
                }
                case 'S' -> {
                    bold |= f.indexOf('D') > 0;
                    italic |= f.indexOf('I') > 0;
                }
                case 'W' -> widths(grid, f.substring(1));
                case 'X', 'Y' -> cell = true;
                default -> {
                }
            }
        }
        if (cell && (picture != null || bold || italic || align != null)) {
            grid.put(at[1] - 1, at[0] - 1, new Grid.Cell(null, picture, bold, italic, align));
        }
    }

    private static String align(String f) {
        if (f.length() < 4) {
            return null;
        }
        return switch (f.charAt(f.length() - 1)) {
            case 'L' -> "left";
            case 'C' -> "center";
            case 'R' -> "right";
            case 'X' -> "fill";
            default -> null;
        };
    }

    private static String fixed(String f) {
        if (f.length() < 3) {
            return null;
        }
        int digits = Character.isDigit(f.charAt(2)) ? f.charAt(2) - '0' : 0;
        String decimals = digits > 0 ? "." + "0".repeat(digits) : "";
        return switch (f.charAt(1)) {
            case 'F' -> "0" + decimals;
            case 'E' -> "0" + decimals + "E+00";
            case '%' -> "0" + decimals + "%";
            case '$', 'C' -> "\"$\"#,##0" + decimals;
            default -> null;
        };
    }

    private static void widths(Grid grid, String spec) {
        String[] p = spec.trim().split("\\s+");
        if (p.length < 3) {
            return;
        }
        int from = number(p[0], -1);
        int to = number(p[1], -1);
        int w = number(p[2], -1);
        for (int c = from; c >= 1 && c <= to && c <= Grid.MAX_COLS && c - from < Grid.MAX_COLS; c++) {
            grid.width(c - 1, w);
        }
    }

    private static Object value(String k) {
        if (k.startsWith("\"")) {
            String s = k.endsWith("\"") && k.length() >= 2 ? k.substring(1, k.length() - 1) : k.substring(1);
            return s.replace("\"\"", "\"").replace("\u001B :", "\n").replaceAll("\u001B[ -~]?[ -~]?", "");
        }
        String t = k.trim();
        if (t.equalsIgnoreCase("TRUE") || t.equalsIgnoreCase("FALSE")) {
            return Boolean.valueOf(t.toUpperCase(Locale.ROOT).equals("TRUE"));
        }
        if (t.startsWith("#")) {
            return new Grid.Error(t);
        }
        try {
            double d = Double.parseDouble(t);
            return Double.isFinite(d) ? d : t;
        } catch (NumberFormatException e) {
            return t;
        }
    }

    private static List<String> fields(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ';') {
                if (i + 1 < line.length() && line.charAt(i + 1) == ';') {
                    cur.append(';');
                    i++;
                    continue;
                }
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static int number(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
