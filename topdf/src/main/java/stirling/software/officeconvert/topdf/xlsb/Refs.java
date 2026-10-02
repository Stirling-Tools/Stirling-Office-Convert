package stirling.software.officeconvert.topdf.xlsb;

final class Refs {

    static final int MAX_ROW = 1_048_575;

    static final int MAX_COL = 16_383;

    private Refs() {}

    static String col(int c) {
        StringBuilder b = new StringBuilder(3);
        int n = c + 1;
        while (n > 0) {
            int m = (n - 1) % 26;
            b.insert(0, (char) ('A' + m));
            n = (n - 1) / 26;
        }
        return b.toString();
    }

    static String cell(int row, int col) {
        return col(col) + (row + 1);
    }

    static boolean valid(int row, int col) {
        return row >= 0 && row <= MAX_ROW && col >= 0 && col <= MAX_COL;
    }

    /** A BinRange (rows then columns) as A1:B2, or null when it lies outside the sheet. */
    static String range(Data d) {
        int r0 = d.i32();
        int r1 = d.i32();
        int c0 = d.i32();
        int c1 = d.i32();
        if (!valid(r0, c0) || !valid(r1, c1) || r1 < r0 || c1 < c0) {
            return null;
        }
        return r0 == r1 && c0 == c1 ? cell(r0, c0) : cell(r0, c0) + ":" + cell(r1, c1);
    }

    static String number(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    static double rk(int v) {
        double d = (v & 0x02) != 0 ? (double) (v >> 2) : Double.longBitsToDouble(((long) (v & 0xFFFFFFFC)) << 32);
        return (v & 0x01) != 0 ? d / 100 : d;
    }

    static String error(int code) {
        return switch (code) {
            case 0x00 -> "#NULL!";
            case 0x07 -> "#DIV/0!";
            case 0x0F -> "#VALUE!";
            case 0x17 -> "#REF!";
            case 0x1D -> "#NAME?";
            case 0x24 -> "#NUM!";
            case 0x2B -> "#GETTING_DATA";
            default -> "#N/A";
        };
    }
}
