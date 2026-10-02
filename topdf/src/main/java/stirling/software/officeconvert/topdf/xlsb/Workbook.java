package stirling.software.officeconvert.topdf.xlsb;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import stirling.software.officeconvert.topdf.xls.Xml;

/** workbook.bin: its sheets in order, the 1904 date system, and the print areas and titles of each sheet. */
final class Workbook {

    record SheetRef(String name, String relId, int state) {}

    private static final int MAX_SHEETS = 4096;

    private static final int MAX_FORMULA_CHARS = 8192;

    final List<SheetRef> sheets = new ArrayList<>();

    private boolean date1904;

    private final List<Object[]> names = new ArrayList<>();

    private final Set<String> printed = new HashSet<>();

    static Workbook read(InputStream in) throws IOException {
        Workbook w = new Workbook();
        Records r = new Records(in);
        int future = 0;
        List<Object[]> pending = new ArrayList<>();
        while (r.next()) {
            if (r.type() == Ids.AC_BEGIN) {
                future++;
                continue;
            }
            if (r.type() == Ids.AC_END) {
                future = Math.max(0, future - 1);
                continue;
            }
            if (future > 0) {
                continue;
            }
            Data d = r.data();
            switch (r.type()) {
                case Ids.SHEET -> {
                    SheetRef sheet = sheet(r.data(), 1);
                    if (sheet == null) {
                        sheet = sheet(d, 2);
                    }
                    if (sheet != null && w.sheets.size() < MAX_SHEETS) {
                        w.sheets.add(sheet);
                    }
                }
                case Ids.WORKBOOKPR -> w.date1904 = (d.u32() & 0x01) != 0;
                case Ids.DEFINEDNAME -> {
                    Object[] name = name(d);
                    if (name != null) {
                        pending.add(name);
                    }
                }
                default -> {
                }
            }
        }
        for (Object[] n : pending) {
            w.printName((String) n[0], (Integer) n[1], castAreas(n[2]));
        }
        return w;
    }

    private static SheetRef sheet(Data d, int numbers) {
        int state = d.i32();
        d.skip(4 * numbers);
        String rel = d.string();
        String name = d.string();
        return d.overrun() || rel.isEmpty() ? null : new SheetRef(name, rel, state);
    }

    @SuppressWarnings("unchecked")
    private static List<Formula.Area> castAreas(Object o) {
        return (List<Formula.Area>) o;
    }

    private static Object[] name(Data d) {
        long flags = d.u32();
        d.skip(1);
        int sheet = d.i32();
        String name = d.string();
        if ((flags & 0x20) == 0) {
            return null;
        }
        String base = name.startsWith("_xlnm.") ? name.substring(6) : name;
        if (!base.equals("Print_Area") && !base.equals("Print_Titles")) {
            return null;
        }
        return new Object[] {base, sheet, Formula.areas(d)};
    }

    private void printName(String base, int sheet, List<Formula.Area> areas) {
        if (sheet < 0 || sheet >= sheets.size() || areas.isEmpty()) {
            return;
        }
        if (!printed.add(sheet + "|" + base)) {
            return;
        }
        String quoted = "'" + sheets.get(sheet).name().replace("'", "''") + "'!";
        if (quoted.length() > MAX_FORMULA_CHARS) {
            return;
        }
        StringBuilder v = new StringBuilder();
        for (Formula.Area a : areas) {
            if (v.length() + quoted.length() + 32 > MAX_FORMULA_CHARS) {
                break;
            }
            v.append(v.isEmpty() ? "" : ",").append(quoted);
            boolean allCols = a.col0() == 0 && a.col1() == Refs.MAX_COL;
            boolean allRows = a.row0() == 0 && a.row1() == Refs.MAX_ROW;
            if (allCols && !allRows) {
                v.append('$').append(a.row0() + 1).append(":$").append(a.row1() + 1);
            } else if (allRows && !allCols) {
                v.append('$').append(Refs.col(a.col0())).append(":$").append(Refs.col(a.col1()));
            } else {
                v.append('$').append(Refs.col(a.col0())).append('$').append(a.row0() + 1).append(":$")
                        .append(Refs.col(a.col1())).append('$').append(a.row1() + 1);
            }
        }
        names.add(new Object[] {sheet, base, v.toString()});
    }

    String xml(java.util.Set<String> kept) {
        StringBuilder b = new StringBuilder(Xml.HEAD).append("<workbook xmlns=\"").append(Xml.MAIN)
                .append("\" xmlns:r=\"").append(Xml.REL).append("\">");
        if (date1904) {
            b.append("<workbookPr date1904=\"1\"/>");
        }
        b.append("<sheets>");
        int[] position = new int[sheets.size()];
        int at = 0;
        for (int i = 0; i < sheets.size(); i++) {
            SheetRef s = sheets.get(i);
            position[i] = kept.contains(s.relId()) ? at++ : -1;
            if (position[i] < 0) {
                continue;
            }
            b.append("<sheet name=\"").append(Xml.attr(s.name())).append("\" sheetId=\"").append(i + 1).append('"');
            if (s.state() == 1) {
                b.append(" state=\"hidden\"");
            } else if (s.state() == 2) {
                b.append(" state=\"veryHidden\"");
            }
            b.append(" r:id=\"").append(Xml.attr(s.relId())).append("\"/>");
        }
        b.append("</sheets>");
        StringBuilder defined = new StringBuilder();
        for (Object[] n : names) {
            int p = position[(Integer) n[0]];
            if (p >= 0) {
                defined.append("<definedName name=\"_xlnm.").append(n[1]).append("\" localSheetId=\"").append(p)
                        .append("\">").append(Xml.attr((String) n[2])).append("</definedName>");
            }
        }
        if (!defined.isEmpty()) {
            b.append("<definedNames>").append(defined).append("</definedNames>");
        }
        return b.append("</workbook>").toString();
    }
}
