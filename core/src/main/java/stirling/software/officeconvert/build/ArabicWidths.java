package stirling.software.officeconvert.build;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ArabicWidths {

    static final String FAMILY = "Tahoma";

    private static final int ISOL = 0;
    private static final int FINA = 1;
    private static final int INIT = 2;
    private static final int MEDI = 3;
    private static final int NONE = -1;

    private static final float[] SPACE = {318f, 348f};

    private static final List<Map<Integer, short[]>> TABLES = load();

    private ArabicWidths() {}

    static float space(boolean bold, float size) {
        return SPACE[bold ? 1 : 0] / 1000f * size;
    }

    static float width(String logical, boolean bold, float size) {
        Map<Integer, short[]> table = TABLES.get(bold ? 1 : 0);
        if (table.isEmpty()) {
            return Float.NaN;
        }
        int[] cps = logical.codePoints().toArray();
        float total = 0;
        for (int i = 0; i < cps.length; i++) {
            int cp = cps[i];
            char type = type(table, cp);
            if (type == 'T') {
                continue;
            }
            short[] w = table.get(cp);
            if (w == null) {
                if (cp == 0x200C || cp == 0x200D) {
                    continue;
                }
                return Float.NaN;
            }
            int prev = neighbour(table, cps, i, -1);
            int next = neighbour(table, cps, i, 1);
            boolean joinPrev = (type == 'D' || type == 'R' || type == 'C') && prev >= 0 && joinsAfter(type(table, cps[prev]));
            int alef = cp == 0x0644 && next >= 0 ? ligature(cps[next]) : 0;
            if (alef != 0) {
                short[] lig = table.get(alef + (joinPrev ? 1 : 0));
                if (lig != null) {
                    total += lig[ISOL];
                    i = next;
                    continue;
                }
            }
            boolean joinNext = (type == 'D' || type == 'C') && next >= 0 && joinsBefore(type(table, cps[next]));
            int form = joinPrev && joinNext ? MEDI : joinPrev ? FINA : joinNext ? INIT : ISOL;
            total += w[form] != NONE ? w[form] : w[ISOL];
        }
        return total / 1000f * size;
    }

    private static int ligature(int cp) {
        return switch (cp) {
            case 0x0622 -> 0xFEF5;
            case 0x0623 -> 0xFEF7;
            case 0x0625 -> 0xFEF9;
            case 0x0627 -> 0xFEFB;
            default -> 0;
        };
    }

    private static boolean joinsAfter(char type) {
        return type == 'D' || type == 'C';
    }

    private static boolean joinsBefore(char type) {
        return type == 'D' || type == 'R' || type == 'C';
    }

    private static int neighbour(Map<Integer, short[]> table, int[] cps, int from, int step) {
        for (int j = from + step; j >= 0 && j < cps.length; j += step) {
            if (type(table, cps[j]) != 'T') {
                return j;
            }
        }
        return -1;
    }

    private static char type(Map<Integer, short[]> table, int cp) {
        if (cp == 0x0640 || cp == 0x200D) {
            return 'C';
        }
        int t = Character.getType(cp);
        if (t == Character.NON_SPACING_MARK || t == Character.ENCLOSING_MARK || t == Character.FORMAT && cp != 0x200C) {
            return 'T';
        }
        short[] w = table.get(cp);
        if (w == null) {
            return 'U';
        }
        return w[INIT] != NONE ? 'D' : w[FINA] != NONE ? 'R' : 'U';
    }

    private static List<Map<Integer, short[]>> load() {
        List<Map<Integer, short[]>> out = List.of(new HashMap<>(), new HashMap<>());
        try (InputStream in = ArabicWidths.class.getResourceAsStream("arabic-widths.txt")) {
            if (in == null) {
                return out;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                String[] f = line.split("\\|");
                if (f.length != 3 || line.startsWith("#")) {
                    continue;
                }
                String[] w = f[2].split(" ");
                short[] forms = new short[4];
                for (int i = 0; i < 4; i++) {
                    forms[i] = "-".equals(w[i]) ? NONE : Short.parseShort(w[i]);
                }
                out.get(Integer.parseInt(f[0])).put(Integer.parseInt(f[1], 16), forms);
            }
        } catch (IOException | RuntimeException e) {
            return List.of(Map.of(), Map.of());
        }
        return out;
    }
}
