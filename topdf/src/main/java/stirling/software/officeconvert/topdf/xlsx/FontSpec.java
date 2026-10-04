package stirling.software.officeconvert.topdf.xlsx;

import java.awt.Color;

record FontSpec(String family, double size, boolean bold, boolean italic, Underline underline, boolean strike,
        Color color, Offset offset) {

    enum Underline {
        NONE,
        SINGLE,
        DOUBLE,
        SINGLE_ACCOUNTING,
        DOUBLE_ACCOUNTING
    }

    enum Offset {
        NONE,
        SUPER,
        SUB
    }

    FontSpec {
        family = family == null || family.isBlank() ? "Calibri" : family;
        size = size > 0 && size < 1000 ? size : 11;
        underline = underline == null ? Underline.NONE : underline;
        color = color == null ? Color.BLACK : color;
        offset = offset == null ? Offset.NONE : offset;
    }

    FontSpec color(Color c) {
        return new FontSpec(family, size, bold, italic, underline, strike, c, offset);
    }

    FontSpec size(double s) {
        return new FontSpec(family, s, bold, italic, underline, strike, color, offset);
    }

    FontSpec bold(boolean b) {
        return new FontSpec(family, size, b, italic, underline, strike, color, offset);
    }

    double drawSize() {
        return offset == Offset.NONE ? size : size * 2 / 3;
    }
}
