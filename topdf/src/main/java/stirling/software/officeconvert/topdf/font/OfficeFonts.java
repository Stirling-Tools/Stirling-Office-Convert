package stirling.software.officeconvert.topdf.font;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Widths and line metrics of fonts that come with Windows and Office, for laying text out when they are missing. */
final class OfficeFonts {

    /** One style of a family in ems: prose width, win ascent and descent, advances, underline and strikeout. */
    record Style(float average, float winAscent, float winDescent, short[] advances, float underlinePosition,
            float underlineThickness, float strikeoutPosition, float strikeoutSize) {

        /** The advance in thousandths of an em, or -1 when the table does not have the character. */
        int advance(int codePoint) {
            int i = index(codePoint);
            return i < 0 ? -1 : advances[i];
        }

        /** The stand-in's metrics with this font's underline and strikeout, which Office draws. */
        FontMetrics lines(FontMetrics m) {
            if (Float.isNaN(underlinePosition) || underlineThickness <= 0 || strikeoutSize <= 0) {
                return m;
            }
            int upm = m.unitsPerEm();
            return new FontMetrics(upm, m.hheaAscender(), m.hheaDescender(), m.hheaLineGap(), m.typoAscender(),
                    m.typoDescender(), m.typoLineGap(), m.useTypoMetrics(), m.winAscent(), m.winDescent(),
                    m.capHeight(), m.xHeight(), Math.round(underlinePosition * upm),
                    Math.max(1, Math.round(underlineThickness * upm)), Math.round(strikeoutPosition * upm),
                    Math.max(1, Math.round(strikeoutSize * upm)), m.italicAngle(), m.fixedPitch(), m.xMin(), m.yMin(),
                    m.xMax(), m.yMax());
        }
    }

    private static final int[][] RANGES = {{0x20, 0x7E}, {0xA0, 0x17F}, {0x192, 0x192}, {0x2C6, 0x2C7},
            {0x2D8, 0x2DD}, {0x384, 0x3CE}, {0x400, 0x45F}, {0x490, 0x491}, {0x2013, 0x2014}, {0x2018, 0x201E},
            {0x2020, 0x2022}, {0x2026, 0x2026}, {0x2030, 0x2030}, {0x2039, 0x203A}, {0x20AC, 0x20AC},
            {0x2116, 0x2116}, {0x2122, 0x2122}};

    private static final int CHARS = count();

    private static final int[] DIRECT = direct();

    private static final String RESOURCE = "office-fonts.tsv";

    private static final int MISSING = 36 * 36 - 1;

    // Families whose usual Linux stand-ins share their widths and line metrics, so emulating them would only add noise
    private static final Map<String, Set<String>> COMPATIBLE = Map.of(
            "calibri", Set.of("carlito"),
            "arial", Set.of("liberation sans", "arimo"),
            "times new roman", Set.of("liberation serif", "tinos"),
            "courier new", Set.of("liberation mono", "cousine"),
            "segoe ui", Set.of("selawik"),
            "segoe ui light", Set.of("selawik"),
            "segoe ui semilight", Set.of("selawik"));

    private static final Map<String, Style[]> TABLE = load();

    // Plain running text: the widths in the table are averages over it, so a stand-in is compared on the same text
    static final String PROSE = "The committee reviewed the proposal and agreed that the new process should be "
            + "introduced in stages. Each department will prepare a short report on its current workload, the "
            + "resources it needs and the risks it sees, so that the plan can be adjusted before the second phase "
            + "begins in March 2024. In the meantime, staff are asked to keep using the existing forms, to record any "
            + "problems they find, and to send their comments to the project office by the end of the month. A summary "
            + "of the results will be published on the intranet, together with answers to the most common questions.";

    private OfficeFonts() {}

    static Map<String, Style[]> all() {
        return TABLE;
    }

    static boolean compatible(String requested, String substitute) {
        Set<String> ok = COMPATIBLE.get(FontLibrary.normalize(requested));
        return ok != null && ok.contains(FontLibrary.normalize(substitute));
    }

    static Style style(String family, boolean bold, boolean italic) {
        Style[] styles = family == null ? null : TABLE.get(FontLibrary.normalize(family));
        if (styles == null) {
            return null;
        }
        int wanted = (bold ? 2 : 0) + (italic ? 1 : 0);
        for (int s : new int[] {wanted, wanted & 2, wanted & 1, 0}) {
            if (styles[s] != null) {
                return styles[s];
            }
        }
        return null;
    }

    static float prose(FontProgram program) {
        long units = 0;
        for (int i = 0; i < PROSE.length(); i++) {
            units += program.advanceOfGlyph(Math.max(0, program.glyph(PROSE.charAt(i))));
        }
        return units / (float) program.metrics().unitsPerEm() / PROSE.length();
    }

    static int index(int codePoint) {
        if (codePoint >= 0 && codePoint < DIRECT.length) {
            return DIRECT[codePoint];
        }
        return search(codePoint);
    }

    private static int search(int codePoint) {
        int base = 0;
        for (int[] r : RANGES) {
            if (codePoint < r[0]) {
                return -1;
            }
            if (codePoint <= r[1]) {
                return base + codePoint - r[0];
            }
            base += r[1] - r[0] + 1;
        }
        return -1;
    }

    private static int[] direct() {
        int[] d = new int[0x500];
        for (int cp = 0; cp < d.length; cp++) {
            d[cp] = search(cp);
        }
        return d;
    }

    private static int count() {
        int n = 0;
        for (int[] r : RANGES) {
            n += r[1] - r[0] + 1;
        }
        return n;
    }

    private static Map<String, Style[]> load() {
        Map<String, Style[]> m = new HashMap<>();
        try (InputStream in = OfficeFonts.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return Map.of();
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                String[] f = line.split("\t");
                if (f.length != 6 && f.length != 10 || f[5].length() != 2 * CHARS) {
                    continue;
                }
                short[] advances = new short[CHARS];
                for (int i = 0; i < CHARS; i++) {
                    int v = Integer.parseInt(f[5], 2 * i, 2 * i + 2, 36);
                    advances[i] = (short) (v == MISSING ? -1 : v);
                }
                boolean lines = f.length == 10;
                m.computeIfAbsent(FontLibrary.normalize(f[0]), k -> new Style[4])[Integer.parseInt(f[1]) & 3] =
                        new Style(Float.parseFloat(f[2]), Float.parseFloat(f[3]), Float.parseFloat(f[4]), advances,
                                lines ? Float.parseFloat(f[6]) : Float.NaN, lines ? Float.parseFloat(f[7]) : Float.NaN,
                                lines ? Float.parseFloat(f[8]) : Float.NaN, lines ? Float.parseFloat(f[9]) : Float.NaN);
            }
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
        return Collections.unmodifiableMap(m);
    }
}
