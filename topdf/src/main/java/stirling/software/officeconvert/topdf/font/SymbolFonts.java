package stirling.software.officeconvert.topdf.font;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import stirling.software.officeconvert.fonts.SymbolCodes;

/** What Symbol, Wingdings, Webdings and Dingbats characters look like in Unicode, for drawing them in another font. */
final class SymbolFonts {

    /**
     * How a Unicode face draws one symbol font: per code (0x20 to 0xFF) the look-alike it draws (0 where none), and the
     * symbol font's own advance and ink box in thousandths of an em ({@code NONE} where the table has no box).
     */
    record Remap(int[] lookAlike, short[] metrics) {

        int of(int code) {
            return lookAlike[code];
        }

        /** The symbol font's advance for the code in thousandths of an em, or -1 when unknown. */
        int advance(int code) {
            return metrics == null ? -1 : metrics[code * 5];
        }

        /** The symbol font's ink box {x0, y0, x1, y1} for the code in ems (y up), or null. */
        float[] box(int code) {
            if (metrics == null || metrics[code * 5 + 1] == NONE) {
                return null;
            }
            int i = code * 5 + 1;
            return new float[] {metrics[i] / 1000f, metrics[i + 1] / 1000f, metrics[i + 2] / 1000f,
                    metrics[i + 3] / 1000f};
        }
    }

    static final short NONE = Short.MIN_VALUE;

    private static final Map<String, short[]> METRICS = metrics();

    private SymbolFonts() {}

    static int[][] table(String family) {
        return SymbolCodes.table(FontLibrary.normalize(family));
    }

    static boolean known(String family) {
        return family != null && SymbolCodes.table(FontLibrary.normalize(family)) != null;
    }

    // The look-alikes this program draws for a symbol font's codes; null for other fonts or a real symbol font
    static Remap remap(String family, FontProgram program) {
        String key = family == null ? null : FontLibrary.normalize(family);
        int[][] table = key == null ? null : SymbolCodes.table(key);
        if (table == null || program.symbol()) {
            return null;
        }
        int[] out = new int[256];
        for (int code = 0x20; code < 256; code++) {
            int[] candidates = table[code];
            if (candidates == null) {
                continue;
            }
            for (int cp : candidates) {
                if (program.glyph(cp) > 0 || cp == ' ') {
                    out[code] = cp;
                    break;
                }
            }
        }
        return new Remap(out, METRICS.get(key));
    }

    private static Map<String, short[]> metrics() {
        Map<String, short[]> m = new HashMap<>();
        try (InputStream in = SymbolFonts.class.getResourceAsStream("symbol-metrics.tsv")) {
            if (in == null) {
                return Map.of();
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                int tab = line.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                short[] values = new short[256 * 5];
                Arrays.fill(values, NONE);
                for (int c = 0; c < 256; c++) {
                    values[c * 5] = -1;
                }
                for (String entry : line.substring(tab + 1).trim().split(" ")) {
                    int colon = entry.indexOf(':');
                    int code = Integer.parseInt(entry, 0, colon, 16);
                    String[] v = entry.substring(colon + 1).split(",");
                    if (code < 0 || code > 255 || v.length != 1 && v.length != 5) {
                        continue;
                    }
                    for (int i = 0; i < v.length; i++) {
                        values[code * 5 + i] = Short.parseShort(v[i]);
                    }
                }
                m.put(FontLibrary.normalize(line.substring(0, tab)), values);
            }
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
        return Map.copyOf(m);
    }

    static int code(int codePoint) {
        if (codePoint >= 0x20 && codePoint <= 0xFF) {
            return codePoint;
        }
        return codePoint >= 0xF020 && codePoint <= 0xF0FF ? codePoint - 0xF000 : -1;
    }
}
