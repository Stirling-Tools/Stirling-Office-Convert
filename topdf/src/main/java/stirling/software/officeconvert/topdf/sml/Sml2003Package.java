package stirling.software.officeconvert.topdf.sml;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.flat.Sniff;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.io.SecureXml;
import stirling.software.officeconvert.topdf.io.SourceFile;
import stirling.software.officeconvert.topdf.xls.Parts;
import stirling.software.officeconvert.topdf.xls.Xml;
import stirling.software.officeconvert.topdf.xlsx.ColumnUnits;

/** An XML Spreadsheet 2003 workbook (SpreadsheetML 2003, often saved with an .xls or .xml name) rewritten as the
 * SpreadsheetML package the XLSX renderer draws. Cached values only: formulas are never evaluated. */
public final class Sml2003Package {

    public record Outcome(List<String> warnings, boolean lost) {
        public Outcome {
            warnings = List.copyOf(warnings);
        }
    }

    static final String NS = "urn:schemas-microsoft-com:office:spreadsheet";

    private static final String CT = "application/vnd.openxmlformats-officedocument.spreadsheetml.";

    private static final int MAX_SHEETS = 4096;

    private Sml2003Package() {}

    /** Whether the file is an XML document whose root is a SpreadsheetML 2003 Workbook. */
    public static boolean is(Path file) {
        return Sniff.root(file, NS, "Workbook");
    }

    public static long estimate(long bytes) {
        long v = Admission.BASE_BYTES + Math.max(0, bytes) * 4;
        return v < 0 ? Long.MAX_VALUE : v;
    }

    private static final class SheetInfo {
        String name;
        Node options;
        Node breaks;
        final List<PrintNames.Name> names = new ArrayList<>();
    }

    public static Outcome write(Path source, OutputStream out, FontLibrary fonts) throws IOException {
        Styles styles = new Styles();
        List<SheetInfo> sheets = new ArrayList<>();
        List<PrintNames.Name> global = new ArrayList<>();
        boolean[] date1904 = new boolean[1];
        try {
            scan(source, styles, sheets, global, date1904);
        } catch (XMLStreamException e) {
            throw new IOException("The XML spreadsheet is not well-formed: " + e.getMessage(), e);
        }
        styles.resolve();
        int digit = ColumnUnits.screenDigit(fonts, styles.defaultFont, styles.defaultSize);
        Parts parts = new Parts(out);
        List<String> warnings = new ArrayList<>();
        boolean lost = false;
        try (InputStream in = SourceFile.open(source)) {
            XMLStreamReader r = SecureXml.reader(in);
            int n = 0;
            while (r.hasNext() && n < sheets.size()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Conversion interrupted");
                }
                if (r.next() == XMLStreamConstants.START_ELEMENT && "Worksheet".equals(r.getLocalName())) {
                    SheetInfo info = sheets.get(n++);
                    Sheet sheet = new Sheet(styles, date1904[0], digit, info.options, info.breaks);
                    try (Parts.Part p = parts.open("xl/worksheets/sheet" + n + ".xml")) {
                        sheet.write(r, p);
                    }
                    if (sheet.truncated) {
                        lost = true;
                        warnings.add("Sheet " + info.name + " is too large; only its first rows were converted");
                    }
                }
            }
            r.close();
        } catch (XMLStreamException e) {
            throw new IOException("The XML spreadsheet is not well-formed: " + e.getMessage(), e);
        }
        if (sheets.isEmpty()) {
            throw new IOException("The XML spreadsheet has no worksheets");
        }
        StringBuilder book = new StringBuilder(Xml.HEAD).append("<workbook xmlns=\"").append(Xml.MAIN)
                .append("\" xmlns:r=\"").append(Xml.REL).append("\">");
        if (date1904[0]) {
            book.append("<workbookPr date1904=\"1\"/>");
        }
        book.append("<sheets>");
        StringBuilder rels = new StringBuilder();
        StringBuilder types = new StringBuilder();
        StringBuilder defined = new StringBuilder();
        for (int i = 0; i < sheets.size(); i++) {
            SheetInfo s = sheets.get(i);
            int n = i + 1;
            String visible = s.options == null || s.options.kid("Visible") == null ? ""
                    : s.options.kid("Visible").text().trim();
            book.append("<sheet name=\"").append(Xml.attr(s.name)).append("\" sheetId=\"").append(n).append('"')
                    .append(visible.equals("SheetHidden") ? " state=\"hidden\""
                            : visible.equals("SheetVeryHidden") ? " state=\"veryHidden\"" : "")
                    .append(" r:id=\"rId").append(n).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(n).append("\" Type=\"").append(Xml.REL)
                    .append("/worksheet\" Target=\"worksheets/sheet").append(n).append(".xml\"/>");
            types.append("<Override PartName=\"/xl/worksheets/sheet").append(n).append(".xml\" ContentType=\"")
                    .append(CT).append("worksheet+xml\"/>");
            List<PrintNames.Name> names = new ArrayList<>(s.names);
            for (PrintNames.Name g : global) {
                if (g.sheet().equals(s.name) && names.stream().noneMatch(x -> x.base().equals(g.base()))) {
                    names.add(g);
                }
            }
            for (PrintNames.Name name : names) {
                defined.append(PrintNames.xml(name, i));
            }
        }
        book.append("</sheets>");
        if (!defined.isEmpty()) {
            book.append("<definedNames>").append(defined).append("</definedNames>");
        }
        book.append("</workbook>");
        parts.put("xl/workbook.xml", book.toString());
        parts.put("xl/styles.xml", styles.xml());
        parts.put("xl/_rels/workbook.xml.rels", Xml.HEAD + "<Relationships xmlns=\"" + Xml.PKG_REL + "\">" + rels
                + "<Relationship Id=\"rIdS\" Type=\"" + Xml.REL + "/styles\" Target=\"styles.xml\"/></Relationships>");
        parts.put("_rels/.rels", Xml.HEAD + "<Relationships xmlns=\"" + Xml.PKG_REL + "\"><Relationship Id=\"rId1\""
                + " Type=\"" + Xml.REL + "/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        parts.put("[Content_Types].xml", Xml.HEAD + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/"
                + "content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package."
                + "relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override"
                + " PartName=\"/xl/workbook.xml\" ContentType=\"" + CT + "sheet.main+xml\"/><Override PartName=\""
                + "/xl/styles.xml\" ContentType=\"" + CT + "styles+xml\"/>" + types + "</Types>");
        parts.finish();
        return new Outcome(warnings, lost);
    }

    private static void scan(Path source, Styles styles, List<SheetInfo> sheets, List<PrintNames.Name> global,
            boolean[] date1904) throws IOException, XMLStreamException {
        try (InputStream in = SourceFile.open(source)) {
            XMLStreamReader r = SecureXml.reader(in);
            int depth = 0;
            SheetInfo current = null;
            while (r.hasNext()) {
                int e = r.next();
                if (e == XMLStreamConstants.END_ELEMENT) {
                    depth--;
                    if (depth == 1) {
                        current = null;
                    }
                    continue;
                }
                if (e != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                depth++;
                String local = r.getLocalName();
                if (depth == 2) {
                    switch (local) {
                        case "Styles" -> {
                            for (Node s : Node.read(r).all("Style")) {
                                styles.add(s);
                            }
                            depth--;
                        }
                        case "Names" -> {
                            names(Node.read(r), null, global);
                            depth--;
                        }
                        case "ExcelWorkbook" -> {
                            date1904[0] = Node.read(r).kid("Date1904") != null;
                            depth--;
                        }
                        case "Worksheet" -> {
                            if (sheets.size() >= MAX_SHEETS) {
                                Node.skip(r);
                                depth--;
                                continue;
                            }
                            current = new SheetInfo();
                            current.name = attr(r, "Name", "Sheet" + (sheets.size() + 1));
                            sheets.add(current);
                        }
                        default -> {
                            Node.skip(r);
                            depth--;
                        }
                    }
                } else if (depth == 3 && current != null) {
                    switch (local) {
                        case "Names" -> names(Node.read(r), current.name, current.names);
                        case "WorksheetOptions" -> current.options = Node.read(r);
                        case "PageBreaks" -> current.breaks = Node.read(r);
                        default -> Node.skip(r);
                    }
                    depth--;
                } else if (depth > 2) {
                    Node.skip(r);
                    depth--;
                }
            }
            r.close();
        }
    }

    private static String attr(XMLStreamReader r, String local, String fallback) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (local.equals(r.getAttributeLocalName(i))) {
                return r.getAttributeValue(i);
            }
        }
        return fallback;
    }

    private static void names(Node names, String sheet, List<PrintNames.Name> out) {
        for (Node n : names.all("NamedRange")) {
            PrintNames.Name p = PrintNames.parse(n.attr("Name"), n.attr("RefersTo"), sheet);
            if (p != null) {
                out.add(p);
            }
        }
    }
}
