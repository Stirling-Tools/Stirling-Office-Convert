package stirling.software.officeconvert.topdf.xlsx;

import stirling.software.officeconvert.topdf.font.FontLibrary;

/** Excel's column width unit: the widest digit of the workbook's default font, in screen pixels. */
public final class ColumnUnits {

    private ColumnUnits() {}

    public static int screenDigit(FontLibrary fonts, String family, double size) {
        return FontMeasure.of(fonts, family, false, false).screenDigit(size > 0 ? size : 10);
    }
}
