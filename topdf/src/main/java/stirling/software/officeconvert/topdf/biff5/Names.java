package stirling.software.officeconvert.topdf.biff5;

import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.topdf.xls.Xml;

/** BIFF5 built-in names for the print area and print titles: their sheet and the areas their formula names. */
final class Names {

    private static final int BUILT_IN = 0x0020;

    private Names() {}

    static Object[] printName(Stream s) {
        int flags = s.u16(0);
        int nameLength = s.u8(3);
        int size = s.u16(4);
        int sheet = s.u16(8);
        if ((flags & BUILT_IN) == 0 || nameLength < 1 || sheet < 1) {
            return null;
        }
        int at = 14;
        int code = s.u8(at);
        String base = code == 0x06 ? "Print_Area" : code == 0x07 ? "Print_Titles" : null;
        if (base == null) {
            return null;
        }
        List<int[]> areas = areas(s, at + nameLength, size);
        return areas.isEmpty() ? null : new Object[] {sheet - 1, base, areas};
    }

    private static List<int[]> areas(Stream s, int at, int size) {
        List<int[]> out = new ArrayList<>();
        int end = Math.min(at + size, s.size());
        int p = at;
        while (p < end) {
            int ptg = s.u8(p++);
            int base = ptg & 0x1F | 0x20;
            if (ptg == 0x10) {
                continue;
            }
            if (ptg == 0x29 || ptg == 0x49 || ptg == 0x69) {
                p += 2;
                continue;
            }
            if ((ptg & 0x60) == 0) {
                return List.of();
            }
            if (base == 0x3B) {
                out.add(new int[] {s.u16(p + 14) & 0x3FFF, s.u16(p + 16) & 0x3FFF, s.u8(p + 18), s.u8(p + 19)});
                p += 20;
            } else if (base == 0x3A) {
                int r = s.u16(p + 14) & 0x3FFF;
                int c = s.u8(p + 16);
                out.add(new int[] {r, r, c, c});
                p += 17;
            } else if (base == 0x25) {
                out.add(new int[] {s.u16(p) & 0x3FFF, s.u16(p + 2) & 0x3FFF, s.u8(p + 4), s.u8(p + 5)});
                p += 6;
            } else {
                return List.of();
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static String xml(List<Object[]> names, List<String> sheets, int[] position) {
        StringBuilder b = new StringBuilder();
        for (Object[] n : names) {
            int sheet = (Integer) n[0];
            if (sheet < 0 || sheet >= sheets.size() || position[sheet] < 0) {
                continue;
            }
            String quoted = "'" + sheets.get(sheet).replace("'", "''") + "'!";
            StringBuilder v = new StringBuilder();
            for (int[] a : (List<int[]>) n[2]) {
                v.append(v.isEmpty() ? "" : ",").append(quoted);
                boolean allCols = a[2] == 0 && a[3] == 255;
                boolean allRows = a[0] == 0 && a[1] >= 16383;
                if (allCols && !allRows) {
                    v.append('$').append(a[0] + 1).append(":$").append(a[1] + 1);
                } else if (allRows && !allCols) {
                    v.append('$').append(Sheet.col(a[2])).append(":$").append(Sheet.col(a[3]));
                } else {
                    v.append('$').append(Sheet.col(a[2])).append('$').append(a[0] + 1).append(":$")
                            .append(Sheet.col(a[3])).append('$').append(a[1] + 1);
                }
            }
            b.append("<definedName name=\"_xlnm.").append(n[1]).append("\" localSheetId=\"").append(position[sheet])
                    .append("\">").append(Xml.attr(v.toString())).append("</definedName>");
        }
        return b.toString();
    }
}
