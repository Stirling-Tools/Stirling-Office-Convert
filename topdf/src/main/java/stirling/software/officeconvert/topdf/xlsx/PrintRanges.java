package stirling.software.officeconvert.topdf.xlsx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;

final class PrintRanges {

    private PrintRanges() {}

    static List<CellRangeAddress> parse(String area, int lastRow, int lastCol) {
        List<CellRangeAddress> out = new ArrayList<>();
        if (area == null || area.isBlank()) {
            return out;
        }
        for (String part : split(area)) {
            CellRangeAddress r = range(part, lastRow, lastCol);
            if (r != null) {
                out.add(r);
            }
            if (out.size() >= 256) {
                break;
            }
        }
        return out;
    }

    static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            }
            if (c == ',' && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    static CellRangeAddress range(String part, int lastRow, int lastCol) {
        String ref = part.trim();
        int bang = ref.lastIndexOf('!');
        if (bang >= 0) {
            ref = ref.substring(bang + 1);
        }
        ref = ref.replace("$", "").trim().toUpperCase(Locale.ROOT);
        if (ref.isEmpty() || ref.contains("#REF")) {
            return null;
        }
        try {
            String[] ends = ref.split(":");
            if (ends.length == 1) {
                CellReference a = new CellReference(ends[0]);
                return valid(new CellRangeAddress(a.getRow(), a.getRow(), a.getCol(), a.getCol()));
            }
            if (ends.length != 2) {
                return null;
            }
            String a = ends[0];
            String b = ends[1];
            if (a.chars().allMatch(Character::isLetter) && b.chars().allMatch(Character::isLetter)) {
                int c0 = CellReference.convertColStringToIndex(a);
                int c1 = CellReference.convertColStringToIndex(b);
                return valid(new CellRangeAddress(0, Math.max(0, lastRow), Math.min(c0, c1), Math.max(c0, c1)));
            }
            if (a.chars().allMatch(Character::isDigit) && b.chars().allMatch(Character::isDigit)) {
                int r0 = Integer.parseInt(a) - 1;
                int r1 = Integer.parseInt(b) - 1;
                return valid(new CellRangeAddress(Math.min(r0, r1), Math.max(r0, r1), 0, Math.max(0, lastCol)));
            }
            CellReference ca = new CellReference(a);
            CellReference cb = new CellReference(b);
            return valid(new CellRangeAddress(Math.min(ca.getRow(), cb.getRow()), Math.max(ca.getRow(), cb.getRow()),
                    Math.min(ca.getCol(), cb.getCol()), Math.max(ca.getCol(), cb.getCol())));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static CellRangeAddress valid(CellRangeAddress r) {
        if (r.getFirstRow() < 0 || r.getFirstColumn() < 0 || r.getLastRow() >= Grid.MAX_ROWS
                || r.getLastColumn() >= Columns.MAX) {
            return null;
        }
        return r;
    }
}
