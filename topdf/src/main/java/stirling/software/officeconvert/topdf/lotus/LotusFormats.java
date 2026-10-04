package stirling.software.officeconvert.topdf.lotus;

final class LotusFormats {

    static final int DEFAULT = 0xFF;

    private LotusFormats() {}

    static boolean hidden(int format) {
        return (format & 0x7F) == 0x76;
    }

    static String excel(int format) {
        int type = (format >> 4) & 7;
        int digits = format & 0x0F;
        String decimals = digits == 0 ? "" : "." + "0".repeat(digits);
        return switch (type) {
            case 0 -> "0" + decimals;
            case 1 -> "0" + decimals + "E+00";
            case 2 -> "\\$#,##0" + decimals + ";\\(\\$#,##0" + decimals + "\\)";
            case 3 -> "0" + decimals + "%";
            case 4 -> "#,##0" + decimals + ";\\(#,##0" + decimals + "\\)";
            case 7 -> special(digits);
            default -> null;
        };
    }

    private static String special(int sub) {
        return switch (sub) {
            case 2 -> "dd\\-mmm\\-yy";
            case 3 -> "dd\\-mmm";
            case 4 -> "mmm\\-yy";
            case 6 -> ";;;";
            case 7 -> "hh:mm:ss AM/PM";
            case 8 -> "hh:mm AM/PM";
            case 9 -> "mm/dd/yy";
            case 10 -> "mm/dd";
            case 11 -> "hh:mm:ss";
            case 12 -> "hh:mm";
            default -> null;
        };
    }
}
