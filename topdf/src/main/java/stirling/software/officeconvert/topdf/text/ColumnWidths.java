package stirling.software.officeconvert.topdf.text;

import java.util.Arrays;

import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontLibrary;

final class ColumnWidths {

    static final String FONT = "Liberation Sans";

    static final int MEASURE_PPEM = 13;

    static final double LO_PIXEL = 0.7722;

    static final double LO_EXTRA = 8.70;

    static final double DEFAULT_WIDTH = 64.0;

    static final double FONT_SIZE = 10;

    static final double CELL_PADDING = 6;

    static final double PRINTABLE_WIDTH = 595.28 - 2 * 56.69;

    static final double OUR_PIXEL = 0.12 * 46 / 7;

    private static final int CACHED = 0x3000;

    private final FontLibrary fonts;

    private final FontFace face;

    private final int[] cache = new int[CACHED];

    private int[] longest = new int[16];

    private int[] longestPixels = new int[16];

    private int[] widest = new int[16];

    private boolean[] multiline = new boolean[16];

    private int columns;

    ColumnWidths(FontLibrary fonts) {
        this.fonts = fonts;
        this.face = fonts.find(FONT, false, false);
        Arrays.fill(cache, -1);
    }

    void add(int col, String shown) {
        columns = Math.max(columns, col + 1);
        if (shown.isEmpty()) {
            return;
        }
        if (col >= longest.length) {
            int n = Math.max(col + 1, longest.length * 2);
            longest = Arrays.copyOf(longest, n);
            longestPixels = Arrays.copyOf(longestPixels, n);
            widest = Arrays.copyOf(widest, n);
            multiline = Arrays.copyOf(multiline, n);
        }
        boolean lines = shown.indexOf('\n') >= 0;
        if (lines && !multiline[col]) {
            multiline[col] = true;
            widest[col] = Math.max(widest[col], longestPixels[col]);
        }
        if (multiline[col]) {
            widest[col] = Math.max(widest[col], Math.max(1, pixels(shown)));
        } else if (shown.length() > longest[col]) {
            longest[col] = shown.length();
            longestPixels[col] = Math.max(1, pixels(shown));
        }
    }

    int columns() {
        return columns;
    }

    boolean measured(int col) {
        return col < longest.length && (longestPixels[col] > 0 || widest[col] > 0);
    }

    double points(int col) {
        if (!measured(col)) {
            return DEFAULT_WIDTH;
        }
        int px = multiline[col] ? widest[col] : longestPixels[col];
        return Math.min(PRINTABLE_WIDTH, loPoints(px));
    }

    boolean overflows(String text) {
        return text.indexOf('\n') >= 0 || loPoints(pixels(text)) > PRINTABLE_WIDTH
                && printedPoints(text) + CELL_PADDING > PRINTABLE_WIDTH;
    }

    double printedPoints(String text) {
        double units = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (TextScanner.visible(cp)) {
                FontFace f = face(cp);
                units += f == null ? 0.5 : (double) f.advance(cp) / f.unitsPerEm();
            }
        }
        return units * FONT_SIZE;
    }

    static double loPoints(int pixels) {
        return pixels * LO_PIXEL + LO_EXTRA;
    }

    static double chars(double points) {
        long px = Math.round(points / OUR_PIXEL);
        return Math.floor(px * 256.0 / 7) / 256;
    }

    int pixels(String text) {
        int best = 0;
        int line = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n') {
                best = Math.max(best, line);
                line = 0;
                continue;
            }
            line += advance(cp);
        }
        return Math.max(best, line);
    }

    private FontFace face(int cp) {
        FontFace f = face != null && face.covers(cp) ? face : fonts.fallback(cp, face);
        return f == null ? face : f;
    }

    private int advance(int cp) {
        if (cp < CACHED && cache[cp] >= 0) {
            return cache[cp];
        }
        int px = 0;
        if (TextScanner.visible(cp)) {
            FontFace f = face(cp);
            px = f == null ? MEASURE_PPEM / 2
                    : (int) Math.round((double) f.advance(cp) * MEASURE_PPEM / f.unitsPerEm());
        }
        if (cp < CACHED) {
            cache[cp] = px;
        }
        return px;
    }
}
