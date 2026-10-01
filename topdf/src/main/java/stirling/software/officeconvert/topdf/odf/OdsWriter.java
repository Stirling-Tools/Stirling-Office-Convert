package stirling.software.officeconvert.topdf.odf;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Element;

/** An OpenDocument spreadsheet rewritten as the SpreadsheetML package the XLSX renderer draws. Cells keep their cached
 * values; formulas are never evaluated. */
final class OdsWriter {

    static final int MAX_SHEETS = 4096;

    final OdfDocument doc;

    final Styles styles;

    final PackageOut out;

    final SheetStyles cellStyles;

    final LocalDate nullDate;

    private final Map<String, Integer> strings = new LinkedHashMap<>();

    private final List<String> warnings = new ArrayList<>();

    private final StringBuilder sharedXml = new StringBuilder();

    private long stringBytes;

    int drawings;

    boolean externalSkipped;

    OdsWriter(OdfDocument doc, PackageOut out) {
        this.doc = doc;
        this.out = out;
        this.styles = new Styles(doc);
        this.cellStyles = new SheetStyles(styles);
        this.nullDate = nullDate(doc);
    }

    private static LocalDate nullDate(OdfDocument doc) {
        Element body = Dom.kid(Dom.kid(doc.content(), Ns.OFFICE, "body"), Ns.OFFICE, "spreadsheet");
        Element settings = Dom.kid(body, Ns.TABLE, "calculation-settings");
        Element nd = Dom.kid(settings, Ns.TABLE, "null-date");
        String v = Dom.attr(nd, Ns.TABLE, "date-value");
        if (v != null) {
            try {
                return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
            } catch (RuntimeException e) {
                return LocalDate.of(1899, 12, 30);
            }
        }
        return LocalDate.of(1899, 12, 30);
    }

    boolean date1904() {
        return nullDate.equals(LocalDate.of(1904, 1, 1));
    }

    int string(String s) {
        Integer i = strings.get(s);
        if (i != null) {
            return i;
        }
        int index = strings.size();
        strings.put(s, index);
        String t = Xml.esc(s);
        stringBytes += t.length();
        boolean preserve = !s.isEmpty() && (Character.isWhitespace(s.charAt(0))
                || Character.isWhitespace(s.charAt(s.length() - 1)) || s.contains("\n"));
        sharedXml.append("<si><t").append(preserve ? " xml:space=\"preserve\"" : "").append('>').append(t)
                .append("</t></si>");
        return index;
    }

    boolean stringsFull() {
        return stringBytes > (200L << 20);
    }

    List<String> write() throws IOException {
        Element body = Dom.kid(Dom.kid(doc.content(), Ns.OFFICE, "body"), Ns.OFFICE, "spreadsheet");
        List<Element> tables = Dom.kids(body, Ns.TABLE, "table");
        StringBuilder book = new StringBuilder(Xml.HEAD).append("<workbook xmlns=\"").append(Xml.S)
                .append("\" xmlns:r=\"").append(Xml.R).append("\">");
        if (date1904()) {
            book.append("<workbookPr date1904=\"1\"/>");
        }
        book.append("<bookViews><workbookView/></bookViews><sheets>");
        Rels rels = new Rels();
        StringBuilder names = new StringBuilder();
        Map<String, Integer> used = new HashMap<>();
        int n = 0;
        for (Element t : tables) {
            if (n >= MAX_SHEETS) {
                warnings.add("Only the first " + MAX_SHEETS + " sheets were converted");
                break;
            }
            String name = sheetName(Dom.attr(t, Ns.TABLE, "name", "Sheet" + (n + 1)), used);
            SheetWriter sheet = new SheetWriter(this, t, n, name);
            String part = "xl/worksheets/sheet" + (n + 1) + ".xml";
            sheet.write(part);
            String rid = rels.add("worksheet", "worksheets/sheet" + (n + 1) + ".xml");
            book.append("<sheet name=\"").append(Xml.esc(name)).append("\" sheetId=\"").append(n + 1).append('"');
            if (sheet.hidden()) {
                book.append(" state=\"hidden\"");
            }
            book.append(" r:id=\"").append(rid).append("\"/>");
            names.append(sheet.definedNames());
            n++;
        }
        if (n == 0) {
            String name = "Sheet1";
            out.xml("xl/worksheets/sheet1.xml", Xml.CT + "spreadsheetml.worksheet+xml", Xml.HEAD + "<worksheet xmlns=\""
                    + Xml.S + "\"><sheetData/></worksheet>");
            String rid = rels.add("worksheet", "worksheets/sheet1.xml");
            book.append("<sheet name=\"").append(name).append("\" sheetId=\"1\" r:id=\"").append(rid).append("\"/>");
        }
        book.append("</sheets>");
        if (!names.isEmpty()) {
            book.append("<definedNames>").append(names).append("</definedNames>");
        }
        book.append("</workbook>");
        out.xml("xl/workbook.xml", Xml.CT + "spreadsheetml.sheet.main+xml", book);
        out.xml("xl/styles.xml", Xml.CT + "spreadsheetml.styles+xml", cellStyles.xml());
        rels.add("styles", "styles.xml");
        out.xml("xl/sharedStrings.xml", Xml.CT + "spreadsheetml.sharedStrings+xml", Xml.HEAD + "<sst xmlns=\"" + Xml.S
                + "\" count=\"" + strings.size() + "\" uniqueCount=\"" + strings.size() + "\">" + sharedXml + "</sst>");
        rels.add("sharedStrings", "sharedStrings.xml");
        out.xml("xl/_rels/workbook.xml.rels", null, rels.xml());
        out.xml("_rels/.rels", null, Xml.HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/"
                + "relationships\"><Relationship Id=\"rId1\" Type=\"" + Xml.REL_TYPE + "officeDocument\" Target=\""
                + "xl/workbook.xml\"/></Relationships>");
        out.finish();
        if (externalSkipped) {
            warnings.add("Skipped active content: linked files and pictures (not fetched)");
        }
        return warnings;
    }

    private static String sheetName(String raw, Map<String, Integer> used) {
        String s = raw.replaceAll("[\\[\\]:*?/\\\\]", "_");
        if (s.length() > 31) {
            s = s.substring(0, 31);
        }
        if (s.isBlank()) {
            s = "Sheet";
        }
        String base = s;
        int k = 1;
        while (used.containsKey(s.toLowerCase(java.util.Locale.ROOT))) {
            String suffix = " (" + (++k) + ")";
            s = (base.length() + suffix.length() > 31 ? base.substring(0, 31 - suffix.length()) : base) + suffix;
        }
        used.put(s.toLowerCase(java.util.Locale.ROOT), 1);
        return s;
    }
}
