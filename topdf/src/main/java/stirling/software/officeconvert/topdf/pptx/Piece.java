package stirling.software.officeconvert.topdf.pptx;

import java.awt.Color;

import stirling.software.officeconvert.topdf.pdf.Stroke;
import stirling.software.officeconvert.topdf.pdf.TextStyle;

record Piece(String text, TextStyle style, float size, boolean underline, boolean strike, float rise,
        Color highlight, String link, float[] win, Advances metrics, Shadows.Shadow shadow, Stroke outline) {

    Piece(String text, TextStyle style, float size, boolean underline, boolean strike, float rise, Color highlight,
            String link, float[] win, Advances metrics, Shadows.Shadow shadow) {
        this(text, style, size, underline, strike, rise, highlight, link, win, metrics, shadow, null);
    }

    Piece withText(String t) {
        return new Piece(t, style, size, underline, strike, rise, highlight, link, win, metrics, shadow, outline);
    }

    Piece withOutline(Stroke s) {
        return new Piece(text, style, size, underline, strike, rise, highlight, link, win, metrics, shadow, s);
    }
}
