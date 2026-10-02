package stirling.software.officeconvert.topdf.sml;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import stirling.software.officeconvert.topdf.xls.Xml;

/** Print_Area and Print_Titles named ranges, written in R1C1 notation, as SpreadsheetML defined names. */
final class PrintNames {

    private static final Pattern REF = Pattern.compile(
            "^(?:'((?:[^']|'')+)'|([^!]+))!(?:R(\\d+)C(\\d+)(?::R(\\d+)C(\\d+))?|R(\\d+)(?::R(\\d+))?|C(\\d+)(?::C(\\d+))?)$");

    record Name(String sheet, String base, String value) {}

    private PrintNames() {}

    static Name parse(String name, String refersTo, String scopeSheet) {
        if (name == null || refersTo == null) {
            return null;
        }
        String base = name.replaceFirst("^_xlnm\\.", "");
        if (!base.equals("Print_Area") && !base.equals("Print_Titles")) {
            return null;
        }
        String f = refersTo.trim().replaceFirst("^=", "");
        List<String> parts = new ArrayList<>();
        String sheet = scopeSheet;
        for (String piece : split(f)) {
            Matcher m = REF.matcher(piece.trim());
            if (!m.matches()) {
                return null;
            }
            String s = m.group(1) != null ? m.group(1).replace("''", "'") : m.group(2).trim();
            if (sheet == null) {
                sheet = s;
            }
            String quoted = "'" + s.replace("'", "''") + "'!";
            if (m.group(3) != null) {
                int r1 = Integer.parseInt(m.group(3));
                int c1 = Integer.parseInt(m.group(4));
                int r2 = m.group(5) == null ? r1 : Integer.parseInt(m.group(5));
                int c2 = m.group(6) == null ? c1 : Integer.parseInt(m.group(6));
                parts.add(quoted + abs(r1, c1) + ":" + abs(r2, c2));
            } else if (m.group(7) != null) {
                int r1 = Integer.parseInt(m.group(7));
                int r2 = m.group(8) == null ? r1 : Integer.parseInt(m.group(8));
                parts.add(quoted + "$" + r1 + ":$" + r2);
            } else {
                int c1 = Integer.parseInt(m.group(9));
                int c2 = m.group(10) == null ? c1 : Integer.parseInt(m.group(10));
                parts.add(quoted + "$" + col(c1) + ":$" + col(c2));
            }
        }
        return parts.isEmpty() || sheet == null ? null : new Name(sheet, base, String.join(",", parts));
    }

    private static List<String> split(String f) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < f.length() && out.size() < 64; i++) {
            char c = f.charAt(i);
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

    private static String abs(int row, int col) {
        return "$" + col(col) + "$" + row;
    }

    private static String col(int c) {
        String ref = Sheet.ref(1, Math.max(1, Math.min(16_384, c)));
        return ref.substring(0, ref.length() - 1);
    }

    static String xml(Name n, int sheet) {
        return "<definedName name=\"_xlnm." + n.base() + "\" localSheetId=\"" + sheet + "\">" + Xml.attr(n.value())
                + "</definedName>";
    }
}
