package stirling.software.officeconvert.topdf.grid;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** DIF (Data Interchange Format) tables: a header of topics, then the data as tuples (rows) of typed values. */
public final class Dif {

    private static final int MAX_LINES = 40_000_000;

    private Dif() {}

    /** Whether the file starts with DIF's TABLE topic. */
    public static boolean is(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            String head = new String(in.readNBytes(32), StandardCharsets.ISO_8859_1).stripLeading();
            return head.startsWith("TABLE\r\n0,") || head.startsWith("TABLE\n0,");
        }
    }

    public static Grid read(Path file) throws IOException {
        Grid grid = new Grid();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(Files.newInputStream(file),
                Charset.forName("windows-1252")), 1 << 16)) {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null) {
                if (++n > MAX_LINES) {
                    return grid;
                }
                if (line.trim().equals("DATA")) {
                    r.readLine();
                    r.readLine();
                    break;
                }
            }
            int row = -1;
            int col = 0;
            while ((line = r.readLine()) != null) {
                if ((++n & 0xFFF) == 0 && Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Conversion interrupted");
                }
                if (n > MAX_LINES) {
                    grid.truncated = true;
                    break;
                }
                String text = r.readLine();
                if (text == null) {
                    break;
                }
                int comma = line.indexOf(',');
                String type = (comma < 0 ? line : line.substring(0, comma)).trim();
                String number = comma < 0 ? "" : line.substring(comma + 1).trim();
                switch (type) {
                    case "-1" -> {
                        if (text.trim().equals("BOT")) {
                            row++;
                            col = 0;
                        } else if (text.trim().equals("EOD")) {
                            return grid;
                        }
                    }
                    case "0" -> {
                        String indicator = text.trim();
                        Object v = switch (indicator) {
                            case "V" -> parse(number);
                            case "TRUE" -> Boolean.TRUE;
                            case "FALSE" -> Boolean.FALSE;
                            case "NA" -> new Grid.Error("#N/A");
                            case "ERROR" -> new Grid.Error("#VALUE!");
                            default -> null;
                        };
                        grid.value(Math.max(0, row), col++, v);
                    }
                    case "1" -> {
                        String s = text;
                        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                            s = s.substring(1, s.length() - 1).replace("\"\"", "\"");
                        }
                        grid.value(Math.max(0, row), col++, s);
                    }
                    default -> col++;
                }
            }
        }
        return grid;
    }

    private static Object parse(String number) {
        try {
            double d = Double.parseDouble(number);
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
