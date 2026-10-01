package stirling.software.officeconvert.topdf.xlsx;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.model.ThemesTable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.io.ActiveContent;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.Relationship;
import stirling.software.officeconvert.topdf.io.Relationships;

final class WorkbookModel {

    record SheetRef(int index, String name, String part, boolean visible, boolean worksheet, boolean chartsheet) {}

    final List<SheetRef> sheets = new ArrayList<>();

    final boolean date1904;

    final boolean savedByExcel;

    final boolean savedOnMac;

    final StylesTable styles;

    final ThemesTable theme;

    final String themePart;

    final SharedStrings strings;

    private final Map<Integer, String> printAreas = new HashMap<>();

    private final Map<Integer, String> printTitles = new HashMap<>();

    WorkbookModel(RenderJob job) throws IOException {
        OfficeZip zip = job.zip();
        String main = zip.mainPart();
        Document doc = zip.xml(main);
        Element root = doc.getDocumentElement();
        Relationships rels = zip.relationships(main);
        Element pr = Dml.child(root, "workbookPr");
        date1904 = pr != null && Dml.flag(pr, "date1904");
        Element version = Dml.child(root, "fileVersion");
        savedByExcel = version != null && "xl".equals(Dml.attr(version, "appName"));
        savedOnMac = savedByExcel && MacExcel.saved(zip);
        int index = 0;
        for (Element s : Dml.children(Dml.child(root, "sheets"), "sheet")) {
            String name = Dml.attr(s, "name");
            String state = Dml.attr(s, "state");
            Relationship r = rels.get(Dml.attrNs(s, "id"));
            boolean follow = r != null && ActiveContent.mayFollow(r) && zip.exists(r.part());
            boolean worksheet = follow && r.typeName().toLowerCase(Locale.ROOT).equals("worksheet");
            boolean chartsheet = follow && r.typeName().toLowerCase(Locale.ROOT).equals("chartsheet");
            boolean visible = state == null || !(state.equals("hidden") || state.equals("veryHidden"));
            sheets.add(new SheetRef(index++, name == null ? "Sheet" + index : name, follow ? r.part() : null, visible,
                    worksheet, chartsheet));
            if (sheets.size() >= 4096) {
                break;
            }
        }
        for (Element d : Dml.children(Dml.child(root, "definedNames"), "definedName")) {
            String name = Dml.attr(d, "name");
            String local = Dml.attr(d, "localSheetId");
            if (name == null || local == null) {
                continue;
            }
            int sheet;
            try {
                sheet = Integer.parseInt(local.trim());
            } catch (NumberFormatException e) {
                continue;
            }
            String value = d.getTextContent();
            if (name.equalsIgnoreCase("_xlnm.Print_Area")) {
                printAreas.put(sheet, value);
            } else if (name.equalsIgnoreCase("_xlnm.Print_Titles")) {
                printTitles.put(sheet, value);
            }
        }
        styles = styles(zip, rels.first("styles"), job);
        Relationship themeRel = rels.first("theme");
        themePart = themeRel != null && ActiveContent.mayFollow(themeRel) ? themeRel.part() : null;
        theme = theme(zip, themeRel, job);
        if (styles != null && theme != null) {
            styles.setTheme(theme);
        }
        Relationship sst = rels.first("sharedStrings");
        SharedStrings strings = SharedStrings.NONE;
        if (sst != null && ActiveContent.mayFollow(sst) && zip.exists(sst.part())) {
            try (InputStream in = zip.open(sst)) {
                strings = SharedStrings.read(in, job);
            }
        }
        this.strings = strings;
    }

    String printArea(int sheet) {
        return printAreas.get(sheet);
    }

    CellRangeAddress titleRows(int sheet) {
        return titles(sheet, true);
    }

    CellRangeAddress titleCols(int sheet) {
        return titles(sheet, false);
    }

    private CellRangeAddress titles(int sheet, boolean rows) {
        String value = printTitles.get(sheet);
        if (value == null) {
            return null;
        }
        for (String part : PrintRanges.split(value)) {
            String ref = part.trim();
            int bang = ref.lastIndexOf('!');
            if (bang >= 0) {
                ref = ref.substring(bang + 1);
            }
            ref = ref.replace("$", "").trim();
            String[] ends = ref.split(":");
            if (ends.length != 2) {
                continue;
            }
            boolean digits = ends[0].chars().allMatch(Character::isDigit) && ends[1].chars().allMatch(Character::isDigit);
            boolean letters = ends[0].chars().allMatch(Character::isLetter)
                    && ends[1].chars().allMatch(Character::isLetter);
            if (rows && digits && !ends[0].isEmpty()) {
                try {
                    int a = Integer.parseInt(ends[0]) - 1;
                    int b = Integer.parseInt(ends[1]) - 1;
                    if (a >= 0 && b >= 0 && a < Grid.MAX_ROWS && b < Grid.MAX_ROWS) {
                        return new CellRangeAddress(Math.min(a, b), Math.max(a, b), -1, -1);
                    }
                } catch (NumberFormatException ignored) {
                    return null;
                }
            } else if (!rows && letters && !ends[0].isEmpty()) {
                int a = WorksheetReader.column(ends[0], -1);
                int b = WorksheetReader.column(ends[1], -1);
                if (a >= 0 && b >= 0 && a < Columns.MAX && b < Columns.MAX) {
                    return new CellRangeAddress(-1, -1, Math.min(a, b), Math.max(a, b));
                }
            }
        }
        return null;
    }

    private static StylesTable styles(OfficeZip zip, Relationship r, RenderJob job) throws InterruptedIOException {
        if (r == null || !ActiveContent.mayFollow(r) || !zip.exists(r.part())) {
            return new StylesTable();
        }
        try (InputStream in = zip.open(r)) {
            return new StylesTable(in);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            job.warn("The cell styles could not be read; default styles are used");
            return new StylesTable();
        }
    }

    private static ThemesTable theme(OfficeZip zip, Relationship r, RenderJob job) throws InterruptedIOException {
        if (r == null || !ActiveContent.mayFollow(r) || !zip.exists(r.part())) {
            return null;
        }
        try (InputStream in = zip.open(r)) {
            return new ThemesTable(in);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            job.warn("The theme could not be read; default theme colours are used");
            return null;
        }
    }
}
