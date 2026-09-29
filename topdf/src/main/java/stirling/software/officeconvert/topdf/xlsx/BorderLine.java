package stirling.software.officeconvert.topdf.xlsx;

import java.awt.Color;

import org.apache.poi.ss.usermodel.BorderStyle;

record BorderLine(BorderStyle style, Color color) {

    static final BorderLine NONE = new BorderLine(BorderStyle.NONE, Color.BLACK);

    static final double THIN_WIDTH = 0.96;

    BorderLine {
        style = style == null ? BorderStyle.NONE : style;
        color = color == null ? Color.BLACK : color;
    }

    boolean visible() {
        return style != BorderStyle.NONE;
    }

    int rank() {
        return switch (style) {
            case NONE -> 0;
            case HAIR -> 1;
            case DOTTED -> 2;
            case DASH_DOT_DOT -> 3;
            case DASH_DOT -> 4;
            case DASHED -> 5;
            case THIN -> 6;
            case MEDIUM_DASH_DOT_DOT -> 7;
            case SLANTED_DASH_DOT -> 8;
            case MEDIUM_DASH_DOT -> 9;
            case MEDIUM_DASHED -> 10;
            case MEDIUM -> 11;
            case THICK -> 12;
            case DOUBLE -> 13;
        };
    }

    double width() {
        return switch (style) {
            case NONE -> 0;
            case HAIR -> 0.14;
            case THIN, DASHED, DOTTED, DASH_DOT, DASH_DOT_DOT -> 0.96;
            case MEDIUM, MEDIUM_DASHED, MEDIUM_DASH_DOT, MEDIUM_DASH_DOT_DOT, SLANTED_DASH_DOT -> 1.92;
            case THICK -> 2.88;
            case DOUBLE -> 2.88;
        };
    }

    float[] dash() {
        return switch (style) {
            case DASHED -> new float[] {2.88f, 0.96f};
            case DOTTED -> new float[] {0.96f, 0.96f};
            case DASH_DOT -> new float[] {6.72f, 1.92f, 2.88f, 1.92f};
            case DASH_DOT_DOT -> new float[] {6.72f, 1.92f, 2.88f, 1.92f, 2.88f, 1.92f};
            case MEDIUM_DASHED -> new float[] {6.72f, 1.92f};
            case MEDIUM_DASH_DOT, SLANTED_DASH_DOT -> new float[] {6.72f, 1.92f, 2.88f, 1.92f};
            case MEDIUM_DASH_DOT_DOT -> new float[] {6.72f, 1.92f, 2.88f, 1.92f, 2.88f, 1.92f};
            default -> null;
        };
    }

    static BorderLine stronger(BorderLine a, BorderLine b) {
        if (a == null || !a.visible()) {
            return b == null ? NONE : b;
        }
        if (b == null || !b.visible()) {
            return a;
        }
        return b.rank() > a.rank() ? b : a;
    }
}
