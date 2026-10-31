package stirling.software.officeconvert.topdf.xlsx;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.font.FontMetrics;

record FontMeasure(int unitsPerEm, int digit, int winAscent, int winDescent, int hheaAscent, int hheaDescent,
        int hheaLineGap, boolean typoMetrics, boolean screenLeading, FontFace hinting, boolean estimated,
        int[] screenDigits, int[] screenLines) {

    private static final Map<String, FontMeasure> KNOWN = new HashMap<>();

    FontMeasure(int unitsPerEm, int digit, int winAscent, int winDescent, int hheaAscent, int hheaDescent,
            int hheaLineGap, boolean typoMetrics) {
        this(unitsPerEm, digit, winAscent, winDescent, hheaAscent, hheaDescent, hheaLineGap, typoMetrics, true, null,
                false, null, null);
    }

    static {
        known("Aptos", 1094, 2068, 563, 1923, -577, 0);
        known("Aptos Narrow", 1038, 2068, 563, 1923, -577, 0);
        known("Aptos Display", 1094, 2068, 563, 1923, -577, 0);
        known("Aptos Light", 1094, 2068, 563, 1923, -577, 0);
        known("Arial", 1139, 1854, 434, 1854, -434, 67);
        known("Arial Narrow", 934, 1854, 434, 1854, -434, 67);
        known("Helvetica", 1139, 1854, 434, 1854, -434, 67);
        known("Calibri", 1038, 1950, 550, 1536, -512, 452);
        known("Calibri Light", 1038, 1950, 550, 1536, -512, 452);
        known("Cambria", 1134, 1946, 455, 1946, -455, 0);
        known("Candara", 1130, 1950, 550, 1484, -564, 452);
        known("Century Gothic", 1135, 1989, 451, 2060, -451, 0);
        known("Consolas", 1126, 1884, 514, 1521, -527, 350);
        known("Constantia", 1119, 1950, 550, 1538, -510, 452);
        known("Corbel", 1074, 1950, 550, 1523, -525, 425);
        known("Courier New", 1229, 1705, 615, 1705, -615, 0);
        known("Franklin Gothic Book", 1201, 1877, 445, 1877, -445, 0);
        known("Garamond", 960, 1765, 539, 1765, -539, 0);
        known("Georgia", 1257, 1878, 449, 1878, -449, 0);
        known("Gill Sans MT", 1024, 1903, 472, 1903, -472, 0);
        known("Book Antiqua", 1024, 1967, 578, 1891, -578, 0);
        known("Palatino Linotype", 1024, 2150, 613, 1499, -582, 682);
        known("Segoe UI", 1104, 2210, 514, 2210, -514, 0);
        known("Tahoma", 1118, 2049, 423, 2049, -423, 0);
        known("Times New Roman", 1024, 1825, 443, 1825, -443, 87, false);
        known("Trebuchet MS", 1074, 1923, 455, 1923, -455, 0);
        known("Verdana", 1302, 2059, 430, 2059, -430, 0);
        known("Microsoft Sans Serif", 1139, 1888, 430, 1888, -430, 0);
        known("MS Sans Serif", 1139, 1888, 430, 1888, -430, 0);
        known("Lucida Sans", 1295, 1900, 432, 1980, -432, 0);
        known("Century", 1139, 2019, 442, 2019, -443, 0);
        known("Bookman Old Style", 1270, 1831, 473, 1929, -475, 0);
        known("Lucida Console", 1234, 1616, 432, 1616, -432, 0);
        known("Comic Sans MS", 1250, 2257, 597, 2257, -597, 0);
        known("Liberation Sans", 1139, 1854, 434, 1854, -434, 67);
        known("Carlito", 1038, 1950, 550, 1536, -512, 452);
        known("Caladea", 1134, 1946, 455, 1946, -455, 0);
    }

    private static void known(String family, int digit, int winAscent, int winDescent, int hheaAscent,
            int hheaDescent, int gap) {
        known(family, digit, winAscent, winDescent, hheaAscent, hheaDescent, gap, true);
    }

    private static void known(String family, int digit, int winAscent, int winDescent, int hheaAscent,
            int hheaDescent, int gap, boolean screenLeading) {
        KNOWN.put(family.toLowerCase(Locale.ROOT), new FontMeasure(2048, digit, winAscent, winDescent, hheaAscent,
                hheaDescent, gap, family.startsWith("Aptos"), screenLeading, null, false, null, null));
    }

    static FontMeasure of(FontLibrary fonts, String family, boolean bold, boolean italic) {
        FontMeasure measure = measure(fonts, family, bold, italic);
        int[] screenDigits = bold || italic ? null : ScreenDigits.of(family);
        int[] screenLines = ScreenRows.of(family, bold, italic);
        return screenDigits == null && screenLines == null ? measure : new FontMeasure(measure.unitsPerEm,
                measure.digit, measure.winAscent, measure.winDescent, measure.hheaAscent, measure.hheaDescent,
                measure.hheaLineGap, measure.typoMetrics, measure.screenLeading, measure.hinting, measure.estimated,
                screenDigits, screenLines);
    }

    private static FontMeasure measure(FontLibrary fonts, String family, boolean bold, boolean italic) {
        String key = family == null ? "" : family.toLowerCase(Locale.ROOT).trim();
        FontFace exact = family == null ? null : fonts.exact(family, bold, italic);
        if (exact == null) {
            exact = family == null ? null : fonts.exact(family, false, false);
        }
        boolean calibri = key.equals(ScreenAdvances.CALIBRI.toLowerCase(Locale.ROOT));
        if (exact != null) {
            return of(exact, calibri ? null : exact, false);
        }
        FontMeasure known = KNOWN.get(key);
        if (known != null) {
            FontFace standIn = calibri ? null : fonts.find(family, false, false);
            return standIn != null && standIn.keepsWidths() ? known.withHinting(standIn) : known;
        }
        return of(fonts.find(family == null ? "Calibri" : family, bold, italic), null, true);
    }

    static FontMeasure of(FontFace face) {
        return of(face, null, false);
    }

    FontMeasure withHinting(FontFace face) {
        return new FontMeasure(unitsPerEm, digit, winAscent, winDescent, hheaAscent, hheaDescent, hheaLineGap,
                typoMetrics, screenLeading, face, false, screenDigits, screenLines);
    }

    private static FontMeasure of(FontFace face, FontFace hinting, boolean estimated) {
        FontMetrics m = face.metrics();
        int digit = 0;
        for (char c = '0'; c <= '9'; c++) {
            digit = Math.max(digit, face.advance(c));
        }
        if (digit <= 0) {
            digit = m.unitsPerEm() / 2;
        }
        int winA = m.winAscent() > 0 ? m.winAscent() : m.hheaAscender();
        int winD = m.winDescent() > 0 ? m.winDescent() : -m.hheaDescender();
        return new FontMeasure(Math.max(16, m.unitsPerEm()), digit, winA, Math.abs(winD), m.hheaAscender(),
                m.hheaDescender(), m.hheaLineGap(), m.useTypoMetrics(), true, hinting, estimated, null, null);
    }

    static int ppem(double size, double dpi) {
        return (int) Math.max(1, Math.round(size * dpi / 72.0));
    }

    int printerDigit(double size) {
        return (int) Math.max(1, Math.round((double) digit * ppem(size, 600) / unitsPerEm));
    }

    int screenDigit(double size) {
        int ppem = ppem(size, 96);
        if (screenDigits != null && ppem < screenDigits.length) {
            return Math.max(1, screenDigits[ppem]);
        }
        int hinted = -1;
        if (hinting != null) {
            for (char c = '0'; c <= '9'; c++) {
                hinted = Math.max(hinted, hinting.hintedAdvance(c, ppem));
            }
        }
        return hinted > 0 ? hinted : (int) Math.max(1, Math.floor((double) digit * ppem / unitsPerEm));
    }

    int printerLinePx(double size) {
        return (int) Math.max(1, textHeightPx(ppem(size, 600), false) + 8 + (typoMetrics ? 1 : 0));
    }

    int screenLinePx(double size) {
        int ppem = ppem(size, 96);
        if (screenLines != null && ppem < screenLines.length) {
            return screenLines[ppem];
        }
        long text = typoMetrics ? pixels((double) hheaAscent * ppem / unitsPerEm)
                + pixels((double) Math.abs(hheaDescent) * ppem / unitsPerEm) + pixels((double) hheaLineGap * ppem / unitsPerEm)
                : textHeightPx(ppem, true);
        return (int) Math.max(1, text + (ppem <= 9 ? 1 : 2));
    }

    int calcLinePx(double size) {
        int ppem = ppem(size, 96);
        int ascent = typoMetrics ? hheaAscent : winAscent;
        int descent = typoMetrics ? Math.abs(hheaDescent) : winDescent;
        return (int) Math.max(1, pixels((double) ascent * ppem / unitsPerEm)
                + pixels((double) descent * ppem / unitsPerEm));
    }

    // GDI rounds the external leading on screen; the printer's row height drops its fraction
    private long textHeightPx(int ppem, boolean screen) {
        int ext = Math.max(0, hheaLineGap - (winAscent + winDescent - (hheaAscent - hheaDescent)));
        double leading = (double) ext * ppem / unitsPerEm;
        return pixels((double) winAscent * ppem / unitsPerEm) + pixels((double) winDescent * ppem / unitsPerEm)
                + (screen ? screenLeading ? pixels(leading) : 0 : (long) Math.floor(leading));
    }

    private static long pixels(double v) {
        return Math.floorDiv(Math.round(v * 64) + 32, 64);
    }

    double ascent(double size) {
        return winAscent * size / unitsPerEm;
    }

    double descent(double size) {
        return winDescent * size / unitsPerEm;
    }

    double externalLeading(double size) {
        int ext = Math.max(0, hheaLineGap - (winAscent + winDescent - (hheaAscent - hheaDescent)));
        return ext * size / unitsPerEm;
    }
}
