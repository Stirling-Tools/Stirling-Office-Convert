package stirling.software.officeconvert.topdf.xlsx;

import java.awt.Color;

record CellFormat(FontSpec font, Color fill, BorderLine left, BorderLine right, BorderLine top, BorderLine bottom,
        BorderLine diagonal, boolean diagonalUp, boolean diagonalDown, HAlign hAlign, VAlign vAlign, boolean wrap,
        boolean shrink, int indent, int rotation, int formatIndex, String formatString) {

    enum HAlign {
        GENERAL,
        LEFT,
        CENTER,
        RIGHT,
        FILL,
        JUSTIFY,
        CENTER_CONTINUOUS,
        DISTRIBUTED
    }

    enum VAlign {
        TOP,
        CENTER,
        BOTTOM,
        JUSTIFY,
        DISTRIBUTED
    }

    CellFormat {
        left = left == null ? BorderLine.NONE : left;
        right = right == null ? BorderLine.NONE : right;
        top = top == null ? BorderLine.NONE : top;
        bottom = bottom == null ? BorderLine.NONE : bottom;
        diagonal = diagonal == null ? BorderLine.NONE : diagonal;
        hAlign = hAlign == null ? HAlign.GENERAL : hAlign;
        vAlign = vAlign == null ? VAlign.BOTTOM : vAlign;
        formatString = formatString == null ? "General" : formatString;
    }

    boolean visible() {
        return fill != null || left.visible() || right.visible() || top.visible() || bottom.visible()
                || (diagonal.visible() && (diagonalUp || diagonalDown));
    }

    boolean wraps() {
        return wrap || hAlign == HAlign.JUSTIFY || hAlign == HAlign.DISTRIBUTED || vAlign == VAlign.JUSTIFY
                || vAlign == VAlign.DISTRIBUTED;
    }
}
