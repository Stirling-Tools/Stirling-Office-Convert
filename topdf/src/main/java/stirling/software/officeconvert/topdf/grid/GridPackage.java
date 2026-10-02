package stirling.software.officeconvert.topdf.grid;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.xls.Parts;
import stirling.software.officeconvert.topdf.xls.Xml;

/** A {@link Grid} as the SpreadsheetML package the XLSX renderer draws, laid out as Excel lays out a new workbook it
 * opens such a file into: Calibri 11, its default widths and margins, no header or footer. */
public final class GridPackage {

    private static final String CT = "application/vnd.openxmlformats-officedocument.spreadsheetml.";

    private GridPackage() {}

    public static long estimate(long bytes) {
        long v = Admission.BASE_BYTES + Math.max(0, bytes) * 40;
        return v < 0 ? Long.MAX_VALUE : v;
    }

    public static void write(Grid grid, String sheetName, OutputStream out) throws IOException {
        Map<String, Integer> xfs = new LinkedHashMap<>();
        Map<String, Integer> formats = new LinkedHashMap<>();
        xfs.put("0|0|0|", 0);
        Parts parts = new Parts(out);
        try (Parts.Part p = parts.open("xl/worksheets/sheet1.xml")) {
            p.write(Xml.HEAD + "<worksheet xmlns=\"" + Xml.MAIN + "\" xmlns:r=\"" + Xml.REL + "\"><sheetViews><sheetView"
                    + " workbookViewId=\"0\"/></sheetViews><sheetFormatPr" + (grid.defaultWidth > 0 ? " defaultColWidth=\""
                    + grid.defaultWidth + "\"" : "") + " defaultRowHeight=\"15\"/>");
            if (!grid.widths.isEmpty()) {
                StringBuilder cols = new StringBuilder("<cols>");
                grid.widths.forEach((c, w) -> cols.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1)
                        .append("\" width=\"").append(w).append("\" customWidth=\"1\"/>"));
                p.write(cols.append("</cols>").toString());
            }
            p.write("<sheetData>");
            for (Map.Entry<Integer, TreeMap<Integer, Grid.Cell>> row : grid.rows.entrySet()) {
                StringBuilder b = new StringBuilder("<row r=\"").append(row.getKey() + 1).append("\">");
                for (Map.Entry<Integer, Grid.Cell> e : row.getValue().entrySet()) {
                    b.append(cell(row.getKey(), e.getKey(), e.getValue(), xfs, formats));
                }
                p.write(b.append("</row>").toString());
                if (p.full()) {
                    grid.truncated = true;
                    break;
                }
            }
            p.write("</sheetData><pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\""
                    + " footer=\"0.3\"/></worksheet>");
        }
        parts.put("xl/styles.xml", styles(xfs, formats));
        String name = sheetName == null || sheetName.isBlank() ? "Sheet1" : sheetName.strip();
        name = name.replaceAll("[\\[\\]:*?/\\\\]", "_");
        if (name.length() > 31) {
            name = name.substring(0, 31);
        }
        parts.put("xl/workbook.xml", Xml.HEAD + "<workbook xmlns=\"" + Xml.MAIN + "\" xmlns:r=\"" + Xml.REL
                + "\"><sheets><sheet name=\"" + Xml.attr(name) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        parts.put("xl/_rels/workbook.xml.rels", Xml.HEAD + "<Relationships xmlns=\"" + Xml.PKG_REL + "\"><Relationship"
                + " Id=\"rId1\" Type=\"" + Xml.REL + "/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship"
                + " Id=\"rId2\" Type=\"" + Xml.REL + "/styles\" Target=\"styles.xml\"/></Relationships>");
        parts.put("_rels/.rels", Xml.HEAD + "<Relationships xmlns=\"" + Xml.PKG_REL + "\"><Relationship Id=\"rId1\""
                + " Type=\"" + Xml.REL + "/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        parts.put("[Content_Types].xml", Xml.HEAD + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/"
                + "content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package."
                + "relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override"
                + " PartName=\"/xl/workbook.xml\" ContentType=\"" + CT + "sheet.main+xml\"/><Override PartName=\""
                + "/xl/styles.xml\" ContentType=\"" + CT + "styles+xml\"/><Override PartName=\"/xl/worksheets/"
                + "sheet1.xml\" ContentType=\"" + CT + "worksheet+xml\"/></Types>");
        parts.finish();
    }

    private static String cell(int row, int col, Grid.Cell c, Map<String, Integer> xfs, Map<String, Integer> formats) {
        int format = 0;
        if (c.format() != null && !c.format().isEmpty() && !c.format().equalsIgnoreCase("General")) {
            format = formats.computeIfAbsent(c.format(), f -> 164 + formats.size());
        }
        String key = format + "|" + (c.bold() ? 1 : 0) + "|" + (c.italic() ? 1 : 0) + "|" + (c.align() == null ? ""
                : c.align());
        int xf = xfs.computeIfAbsent(key, k -> xfs.size());
        String ref = ref(row, col);
        String s = xf == 0 ? "" : " s=\"" + xf + "\"";
        Object v = c.value();
        if (v instanceof Double d && Double.isFinite(d)) {
            return "<c r=\"" + ref + "\"" + s + "><v>" + number(d) + "</v></c>";
        }
        if (v instanceof Boolean b) {
            return "<c r=\"" + ref + "\"" + s + " t=\"b\"><v>" + (b ? 1 : 0) + "</v></c>";
        }
        if (v instanceof Grid.Error e) {
            return "<c r=\"" + ref + "\"" + s + " t=\"e\"><v>" + Xml.attr(e.code()) + "</v></c>";
        }
        if (v instanceof String str && !str.isEmpty()) {
            return "<c r=\"" + ref + "\"" + s + " t=\"inlineStr\"><is><t xml:space=\"preserve\">" + Xml.text(str)
                    + "</t></is></c>";
        }
        return xf == 0 ? "" : "<c r=\"" + ref + "\"" + s + "/>";
    }

    private static String styles(Map<String, Integer> xfs, Map<String, Integer> formats) {
        StringBuilder b = new StringBuilder(Xml.HEAD).append("<styleSheet xmlns=\"").append(Xml.MAIN).append("\">");
        if (!formats.isEmpty()) {
            b.append("<numFmts count=\"").append(formats.size()).append("\">");
            formats.forEach((code, id) -> b.append("<numFmt numFmtId=\"").append(id).append("\" formatCode=\"")
                    .append(Xml.attr(code)).append("\"/>"));
            b.append("</numFmts>");
        }
        String font = "<sz val=\"11\"/><color theme=\"1\"/><name val=\"Calibri\"/><family val=\"2\"/>"
                + "<scheme val=\"minor\"/></font>";
        b.append("<fonts count=\"4\"><font>").append(font).append("<font><b/>").append(font).append("<font><i/>")
                .append(font).append("<font><b/><i/>").append(font).append("</fonts>")
                .append("<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill")
                .append(" patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border><left/><right/><top/>")
                .append("<bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\"")
                .append(" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"")
                .append(xfs.size()).append("\">");
        for (String key : xfs.keySet()) {
            String[] k = key.split("\\|", -1);
            int font2 = (k[1].equals("1") ? 1 : 0) + (k[2].equals("1") ? 2 : 0);
            b.append("<xf numFmtId=\"").append(k[0]).append("\" fontId=\"").append(font2)
                    .append("\" fillId=\"0\" borderId=\"0\" xfId=\"0\"");
            if (!k[3].isEmpty()) {
                b.append(" applyAlignment=\"1\"><alignment horizontal=\"").append(k[3]).append("\"/></xf>");
            } else {
                b.append("/>");
            }
        }
        b.append("</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/>")
                .append("</cellStyles></styleSheet>");
        return b.toString();
    }

    static String number(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    static String ref(int row, int col) {
        StringBuilder b = new StringBuilder(4);
        int n = col + 1;
        while (n > 0) {
            int m = (n - 1) % 26;
            b.insert(0, (char) ('A' + m));
            n = (n - 1) / 26;
        }
        return b.append(row + 1).toString();
    }
}
