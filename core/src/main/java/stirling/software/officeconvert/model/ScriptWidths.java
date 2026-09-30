package stirling.software.officeconvert.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class ScriptWidths {

    private static final Map<String, Map<Integer, Short>> WIDTHS = load();

    private static final Set<Character.UnicodeScript> SIMPLE = EnumSet.of(Character.UnicodeScript.ARMENIAN,
            Character.UnicodeScript.GEORGIAN, Character.UnicodeScript.ETHIOPIC);

    private static final int ZWNJ = 0x200C;

    private static final int ZWJ = 0x200D;

    private static final int JOINED = 0x110000;

    private ScriptWidths() {}

    public static Character.UnicodeScript script(String text) {
        Character.UnicodeScript found = null;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == ZWJ || cp == ZWNJ) {
                continue;
            }
            Character.UnicodeScript s = Character.UnicodeScript.of(cp);
            if (s == Character.UnicodeScript.INHERITED) {
                continue;
            }
            if (found != null && s != found || !WIDTHS.containsKey(s + "|0")) {
                return null;
            }
            found = s;
        }
        return found;
    }

    public static float width(String text, Character.UnicodeScript script, boolean bold) {
        Map<Integer, Short> w = bold && WIDTHS.containsKey(script + "|1") ? WIDTHS.get(script + "|1") : WIDTHS.get(script + "|0");
        if (w == null) {
            return Float.NaN;
        }
        float sum = 0;
        for (String word : text.split("\\s+")) {
            int[] cps = word.codePoints().toArray();
            for (int i = 0; i < cps.length; i++) {
                int cp = cps[i];
                boolean end = i + 1 == cps.length || cps[i + 1] == ZWNJ;
                Short v = linker(cp) && end || i > 0 && linker(cps[i - 1]) && Character.isLetter(cp) ? w.get(JOINED + cp) : null;
                v = v == null ? w.get(cp) : v;
                if (v == null) {
                    if (cp == ZWJ || cp == ZWNJ) {
                        continue;
                    }
                    return Float.NaN;
                }
                sum += v;
            }
        }
        return sum / 1000f;
    }

    public static float space(Character.UnicodeScript script, boolean bold) {
        Map<Integer, Short> w = bold && WIDTHS.containsKey(script + "|1") ? WIDTHS.get(script + "|1") : WIDTHS.get(script + "|0");
        Short v = w == null ? null : w.get((int) ' ');
        return v == null ? Float.NaN : v / 1000f;
    }

    public static float perUnit(String text, boolean bold) {
        Character.UnicodeScript s = script(text.replaceAll("\\s+", ""));
        if (!clustered(s)) {
            return Float.NaN;
        }
        long spaces = text.chars().filter(c -> c == ' ').count();
        return (width(text.strip(), s, bold) + spaces * space(s, bold)) / units(text);
    }

    public static boolean clustered(Character.UnicodeScript script) {
        return script != null && !SIMPLE.contains(script);
    }

    static int units(String text) {
        int n = 0;
        int prev = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            boolean joins = type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK || cp == ZWJ || cp == ZWNJ || linker(prev) && Character.isLetter(cp);
            n += joins ? 0 : 1;
            prev = cp;
        }
        return Math.max(1, n);
    }

    private static boolean linker(int cp) {
        return switch (cp) {
            case 0x094D, 0x09CD, 0x0A4D, 0x0ACD, 0x0B4D, 0x0BCD, 0x0C4D, 0x0CCD, 0x0D4D, 0x0DCA, 0x1039, 0x17D2 -> true;
            default -> false;
        };
    }

    private static Map<String, Map<Integer, Short>> load() {
        Map<String, Map<Integer, Short>> out = new HashMap<>();
        try (InputStream in = ScriptWidths.class.getResourceAsStream("script-widths.txt")) {
            if (in == null) {
                return out;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split("\\|");
                Map<Integer, Short> widths = out.computeIfAbsent(Character.UnicodeScript.valueOf(p[0]) + "|" + p[1],
                        k -> new HashMap<>());
                int cp = Integer.parseInt(p[2], 16);
                for (String w : p[3].split(" ")) {
                    widths.put(cp++, Short.parseShort(w));
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            return new HashMap<>();
        }
        return out;
    }
}
