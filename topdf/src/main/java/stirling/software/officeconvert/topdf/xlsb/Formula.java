package stirling.software.officeconvert.topdf.xlsb;

import java.util.ArrayList;
import java.util.List;

/** The few BIFF12 formulas ([MS-XLSB] 2.2.2) a conversion reads without evaluating anything: a single constant, and
 * the areas a print range or print titles name. */
final class Formula {

    private static final int MAX_AREAS = 1024;

    record Area(int row0, int row1, int col0, int col1) {}

    private Formula() {}

    /** A formula (size, tokens, extra) whose tokens are one constant, as SpreadsheetML formula text; null otherwise.
     * The whole formula is consumed either way. */
    static String constant(Data d) {
        int size = d.i32();
        if (size < 0 || size > d.remaining()) {
            d.skip(d.remaining());
            return null;
        }
        byte[] tokens = new byte[size];
        for (int i = 0; i < size; i++) {
            tokens[i] = (byte) d.u8();
        }
        int extra = d.i32();
        if (extra > 0) {
            d.skip(Math.min(extra, d.remaining()));
        }
        Data t = new Data(tokens, size);
        boolean negative = false;
        String value = null;
        while (t.remaining() > 0) {
            int ptg = t.u8();
            switch (ptg) {
                case 0x1E -> value = Integer.toString(t.u16());
                case 0x1F -> {
                    double v = t.f64();
                    value = Double.isFinite(v) ? Refs.number(v) : null;
                }
                case 0x1D -> value = t.u8() != 0 ? "TRUE" : "FALSE";
                case 0x17 -> {
                    int n = t.u16();
                    StringBuilder b = new StringBuilder("\"");
                    for (int i = 0; i < n && t.remaining() >= 2; i++) {
                        char c = (char) t.u16();
                        b.append(c == '"' ? "\"\"" : String.valueOf(c));
                    }
                    value = b.append('"').toString();
                }
                case 0x13 -> negative = !negative;
                case 0x12, 0x15 -> {
                }
                default -> {
                    return null;
                }
            }
        }
        if (value == null || t.overrun()) {
            return null;
        }
        return negative ? "-" + value : value;
    }

    /** The areas a defined name's formula refers to (3-D or plain references and areas, joined by unions); empty
     * when it holds anything else. */
    static List<Area> areas(Data d) {
        List<Area> out = new ArrayList<>();
        int size = d.i32();
        if (size <= 0 || size > d.remaining()) {
            return out;
        }
        int end = d.remaining() - size;
        while (d.remaining() > end && !d.overrun()) {
            int ptg = d.u8();
            int base = ptg & 0x1F | 0x20;
            if ((ptg & 0x60) != 0 && (base == 0x3B || base == 0x3A || base == 0x25 || base == 0x24)) {
                if (base == 0x3B || base == 0x3A) {
                    d.u16();
                }
                int r0 = d.i32();
                int r1 = base == 0x3B || base == 0x25 ? d.i32() : r0;
                int c0 = d.u16() & 0x3FFF;
                int c1 = base == 0x3B || base == 0x25 ? d.u16() & 0x3FFF : c0;
                if (r0 >= 0 && r1 >= r0 && r1 <= Refs.MAX_ROW && c1 >= c0 && c1 <= Refs.MAX_COL && out.size() < MAX_AREAS) {
                    out.add(new Area(r0, r1, c0, c1));
                }
            } else if (ptg == 0x10) {
                continue;
            } else if (ptg == 0x29 || ptg == 0x49 || ptg == 0x69) {
                d.u16();
            } else {
                out.clear();
                return out;
            }
        }
        return out;
    }
}
