package stirling.software.officeconvert.xlsx;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import stirling.software.officeconvert.sheet.SheetXml;

final class XlsxParts {

    private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PACKAGE_RELS = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

    private XlsxParts() {}

    static String workbook(List<String> sheets, List<String[]> definedNames) {
        StringBuilder sb = new StringBuilder(HEAD).append("<workbook xmlns=\"").append(MAIN).append("\" xmlns:r=\"")
                .append(REL).append("\"><workbookPr/><bookViews><workbookView activeTab=\"0\"/></bookViews><sheets>");
        for (int i = 0; i < sheets.size(); i++) {
            sb.append("<sheet name=\"");
            SheetXml.escape(sb, sheets.get(i));
            sb.append("\" sheetId=\"").append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        sb.append("</sheets>");
        if (!definedNames.isEmpty()) {
            sb.append("<definedNames>");
            for (String[] n : definedNames) {
                sb.append("<definedName name=\"");
                SheetXml.escape(sb, n[0]);
                sb.append(n.length > 2 ? "\" localSheetId=\"" + n[2] + "\">" : "\">");
                SheetXml.escape(sb, n[1]);
                sb.append("</definedName>");
            }
            sb.append("</definedNames>");
        }
        return sb.append("<calcPr calcId=\"191029\"/></workbook>").toString();
    }

    static String workbookRels(int sheets) {
        StringBuilder sb = new StringBuilder(HEAD).append("<Relationships xmlns=\"").append(PACKAGE_RELS).append("\">");
        for (int i = 1; i <= sheets; i++) {
            sb.append("<Relationship Id=\"rId").append(i).append("\" Type=\"").append(REL)
                    .append("/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
        }
        sb.append("<Relationship Id=\"rId").append(sheets + 1).append("\" Type=\"").append(REL)
                .append("/styles\" Target=\"styles.xml\"/>");
        sb.append("<Relationship Id=\"rId").append(sheets + 2).append("\" Type=\"").append(REL)
                .append("/sharedStrings\" Target=\"sharedStrings.xml\"/>");
        return sb.append("</Relationships>").toString();
    }

    static String contentTypes(int sheets) {
        String ml = "application/vnd.openxmlformats-officedocument.spreadsheetml.";
        StringBuilder sb = new StringBuilder(HEAD)
                .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"").append(ml).append("sheet.main+xml\"/>");
        for (int i = 1; i <= sheets; i++) {
            sb.append("<Override PartName=\"/xl/worksheets/sheet").append(i).append(".xml\" ContentType=\"").append(ml)
                    .append("worksheet+xml\"/>");
        }
        return sb.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"").append(ml).append("styles+xml\"/>")
                .append("<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"").append(ml)
                .append("sharedStrings+xml\"/>")
                .append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package."
                        + "core-properties+xml\"/>")
                .append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-"
                        + "officedocument.extended-properties+xml\"/>")
                .append("</Types>").toString();
    }

    static String rootRels() {
        return HEAD + "<Relationships xmlns=\"" + PACKAGE_RELS + "\">"
                + "<Relationship Id=\"rId1\" Type=\"" + REL + "/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"" + PACKAGE_RELS + "/metadata/core-properties\""
                + " Target=\"docProps/core.xml\"/>"
                + "<Relationship Id=\"rId3\" Type=\"" + REL + "/extended-properties\" Target=\"docProps/app.xml\"/>"
                + "</Relationships>";
    }

    static String core(String title, String author) {
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        return HEAD + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\""
                + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + (title == null || title.isBlank() ? "" : "<dc:title>" + SheetXml.escape(title) + "</dc:title>")
                + (author == null || author.isBlank() ? "" : "<dc:creator>" + SheetXml.escape(author) + "</dc:creator>")
                + "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:created>"
                + "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:modified>"
                + "</cp:coreProperties>";
    }

    static String app() {
        return HEAD + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">"
                + "<Application>Stirling-PDF</Application></Properties>";
    }
}
