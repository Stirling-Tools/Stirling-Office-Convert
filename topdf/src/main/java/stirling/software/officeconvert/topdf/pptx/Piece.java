package stirling.software.officeconvert.topdf.pptx;

import java.awt.Color;

import stirling.software.officeconvert.topdf.pdf.Stroke;
import stirling.software.officeconvert.topdf.pdf.TextStyle;

record Piece(String text, TextStyle style, float size, boolean underline, boolean strike, float rise,
        Color highlight, String link, float[] win, Advances metrics, Shadows.Shadow shadow, Stroke outline,
        boolean emoji) {

    Piece(String text, TextStyle style, float size, boolean underline, boolean strike, float rise, Color highlight,
            String link, float[] win, Advances metrics, Shadows.Shadow shadow) {
        this(text, style, size, underline, strike, rise, highlight, link, win, metrics, shadow, null, false);
    }

    Piece withText(String t) {
        return new Piece(t, style, size, underline, strike, rise, highlight, link, win, metrics, shadow, outline,
                emoji);
    }

    Piece withOutline(Stroke s) {
        return new Piece(text, style, size, underline, strike, rise, highlight, link, win, metrics, shadow, s, emoji);
    }

    Piece asEmoji(float[] line) {
        return new Piece(text, style, size, underline, strike, rise, highlight, link, line, null, shadow, outline,
                true);
    }
}
