package stirling.software.officeconvert.topdf.pdf;

import java.awt.Color;
import java.util.Objects;

import stirling.software.officeconvert.topdf.font.FontFace;

public record TextStyle(FontFace face, float size, Color color, float charSpacing, float horizontalScale,
        float wordSpacing, boolean kerning) {

    public TextStyle {
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(color, "color");
        if (!(size > 0 && size <= 10_000)) {
            throw new IllegalArgumentException("The font size must be above 0 and at most 10000 pt, was " + size);
        }
        if (!(horizontalScale > 0 && horizontalScale <= 10_000)) {
            throw new IllegalArgumentException("The horizontal scale is a percentage above 0, was " + horizontalScale);
        }
        if (!Float.isFinite(charSpacing) || !Float.isFinite(wordSpacing)) {
            throw new IllegalArgumentException("Spacing must be finite");
        }
    }

    public static TextStyle of(FontFace face, float size) {
        return new TextStyle(face, size, Color.BLACK, 0, 100, 0, false);
    }

    public TextStyle face(FontFace f) {
        return new TextStyle(f, size, color, charSpacing, horizontalScale, wordSpacing, kerning);
    }

    public TextStyle size(float s) {
        return new TextStyle(face, s, color, charSpacing, horizontalScale, wordSpacing, kerning);
    }

    public TextStyle color(Color c) {
        return new TextStyle(face, size, c, charSpacing, horizontalScale, wordSpacing, kerning);
    }

    public TextStyle charSpacing(float points) {
        return new TextStyle(face, size, color, points, horizontalScale, wordSpacing, kerning);
    }

    public TextStyle horizontalScale(float percent) {
        return new TextStyle(face, size, color, charSpacing, percent, wordSpacing, kerning);
    }

    public TextStyle wordSpacing(float points) {
        return new TextStyle(face, size, color, charSpacing, horizontalScale, points, kerning);
    }

    public TextStyle kerning(boolean on) {
        return new TextStyle(face, size, color, charSpacing, horizontalScale, wordSpacing, on);
    }

    public float width(String text) {
        return width(text, 0, text.length());
    }

    /** The width of {@code text.substring(from, to)}, without making the substring. */
    public float width(String text, int from, int to) {
        long units = 0;
        int glyphs = 0;
        int spaces = 0;
        int previous = -1;
        for (int i = from; i < to; ) {
            char c = text.charAt(i);
            int cp = c;
            if (Character.isHighSurrogate(c) && i + 1 < to && Character.isLowSurrogate(text.charAt(i + 1))) {
                cp = Character.toCodePoint(c, text.charAt(i + 1));
            }
            i += Character.charCount(cp);
            if (skipped(cp) && !face.symbolCode(cp)) {
                continue;
            }
            units += face.advance(cp);
            if (kerning && previous >= 0) {
                units += face.kerning(previous, cp);
            }
            glyphs++;
            if (cp == ' ') {
                spaces++;
            }
            previous = cp;
        }
        return units * size / face.unitsPerEm() * (horizontalScale / 100f) + glyphs * charSpacing + spaces * wordSpacing;
    }

    public float points(int fontUnits) {
        return fontUnits * size / face.unitsPerEm();
    }

    static boolean skipped(int cp) {
        return Character.isISOControl(cp) || cp == FontFace.SOFT_HYPHEN;
    }
}
