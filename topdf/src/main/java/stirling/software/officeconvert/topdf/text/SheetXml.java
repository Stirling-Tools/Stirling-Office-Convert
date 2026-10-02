package stirling.software.officeconvert.topdf.text;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipOutputStream;

final class SheetXml {

    static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

    static final String ROW_HEIGHT = "13.25";

    static final String SHORT_ROW_HEIGHT = "13.18";

    static final double ROW_PITCH = 12.784;

    static final double PRINTER_PIXEL = 0.12;

    static final long MAX_COLUMN_PIXELS = (long) Math.floor(ColumnWidths.PRINTABLE_WIDTH / ColumnWidths.OUR_PIXEL);

    static final int MAX_SHEET_NAME = 255;

    private static final String TYPES = "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument"
            + ".spreadsheetml.sheet.main+xml\"/>"
            + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats"
            + "-officedocument.spreadsheetml.worksheet+xml\"/>"
            + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument"
            + ".spreadsheetml.styles+xml\"/></Types>";

    private static final String ROOT_RELS = "<Relationships xmlns=\"" + Parts.PACKAGE_RELS + "\">"
            + "<Relationship Id=\"rId1\" Type=\"" + Parts.RELS + "/officeDocument\" Target=\"xl/workbook.xml\"/>"
            + "</Relationships>";

    private static final String WORKBOOK_RELS = "<Relationships xmlns=\"" + Parts.PACKAGE_RELS + "\">"
            + "<Relationship Id=\"rId1\" Type=\"" + Parts.RELS + "/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
            + "<Relationship Id=\"rId2\" Type=\"" + Parts.RELS + "/styles\" Target=\"styles.xml\"/>"
            + "</Relationships>";

    private static final String STYLES = "<styleSheet xmlns=\"" + MAIN + "\"><fonts count=\"1\"><font>"
            + "<sz val=\"10\"/><name val=\"" + ColumnWidths.FONT + "\"/><family val=\"2\"/></font></fonts>"
            + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill"
            + " patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border><left/><right/><top/><bottom/>"
            + "<diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\""
            + " borderId=\"0\"/></cellStyleXfs><cellXfs count=\"3\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\""
            + " borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\""
            + " applyAlignment=\"1\"><alignment horizontal=\"right\"/></xf><xf numFmtId=\"0\" fontId=\"0\""
            + " fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment wrapText=\"1\"/></xf>"
            + "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/>"
            + "</cellStyles></styleSheet>";

    private static final String PAGE = "<pageMargins left=\"0.77768\" right=\"0.787401574803\""
            + " top=\"1.07185\" bottom=\"1.082677165354\" header=\"0.787401574803\" footer=\"0.787401574803\"/>"
            + "<pageSetup paperSize=\"9\" orientation=\"portrait\"/><headerFooter>"
            + "<oddHeader>&amp;C&amp;\"" + ColumnWidths.FONT + ",Regular\"&amp;10&amp;A</oddHeader>"
            + "<oddFooter>&amp;C&amp;\"" + ColumnWidths.FONT + ",Regular\"&amp;10Page &amp;P</oddFooter>"
            + "</headerFooter>";

    private final Writer out;

    private final ColumnWidths widths;

    private final StringBuilder buf = new StringBuilder(1 << 12);

    SheetXml(Writer out, ColumnWidths widths) {
        this.out = out;
        this.widths = widths;
    }

    static void packageParts(ZipOutputStream zip, String sheetName) throws IOException {
        String name = sheetName.length() > MAX_SHEET_NAME ? sheetName.substring(0, MAX_SHEET_NAME) : sheetName;
        Parts.put(zip, "[Content_Types].xml", TYPES);
        Parts.put(zip, "_rels/.rels", ROOT_RELS);
        Parts.put(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS);
        Parts.put(zip, "xl/workbook.xml", "<workbook xmlns=\"" + MAIN + "\" xmlns:r=\"" + Parts.RELS + "\"><sheets>"
                + "<sheet name=\"" + Parts.escape(name) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        Parts.put(zip, "xl/styles.xml", STYLES);
    }

    static int bands(ColumnWidths widths) {
        int last = -1;
        for (int c = 0; c < widths.columns(); c++) {
            if (widths.measured(c)) {
                last = c;
            }
        }
        int bands = 1;
        double used = 0;
        for (int c = 0; c <= last; c++) {
            double w = widths.points(c);
            if (used > 0 && used + w > ColumnWidths.PRINTABLE_WIDTH) {
                bands++;
                used = 0;
            }
            used += w;
        }
        return bands;
    }

    void start() throws IOException {
        buf.append("<worksheet xmlns=\"").append(MAIN).append("\" xmlns:r=\"").append(Parts.RELS).append("\">")
                .append("<sheetViews><sheetView workbookViewId=\"0\"/></sheetViews><sheetFormatPr defaultColWidth=\"")
                .append(format(ColumnWidths.chars(ColumnWidths.DEFAULT_WIDTH))).append("\" defaultRowHeight=\"")
                .append(ROW_HEIGHT).append("\" customHeight=\"1\"/>");
        if (widths.columns() > 0) {
            double[] chars = columnChars(widths);
            buf.append("<cols>");
            int c = 0;
            while (c < chars.length) {
                int last = c;
                while (last + 1 < chars.length && chars[last + 1] == chars[c]) {
                    last++;
                }
                buf.append("<col min=\"").append(c + 1).append("\" max=\"").append(last + 1).append("\" width=\"")
                        .append(format(chars[c])).append("\" customWidth=\"1\"/>");
                c = last + 1;
            }
            buf.append("</cols>");
        }
        buf.append("<sheetData>");
        flush();
    }

    void row(int r, List<String> cells) throws IOException {
        int start = buf.length();
        boolean any = false;
        buf.append("<row r=\"").append(r + 1).append("\" ht=\"").append(shortRow(r) ? SHORT_ROW_HEIGHT : ROW_HEIGHT)
                .append("\">");
        for (int c = 0; c < cells.size(); c++) {
            String field = cells.get(c);
            if (field.isEmpty()) {
                continue;
            }
            CellValue v = CellValue.of(field);
            int style = v.number() ? 1 : widths.overflows(v.text()) ? 2 : 0;
            buf.append("<c r=\"");
            column(c);
            buf.append(r + 1).append('"');
            if (style > 0) {
                buf.append(" s=\"").append(style).append('"');
            }
            buf.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(Parts.escape(cellText(v.text())))
                    .append("</t></is></c>");
            any = true;
        }
        if (!any) {
            buf.setLength(start);
            return;
        }
        buf.append("</row>");
        if (buf.length() >= 1 << 14) {
            flush();
        }
    }

    void end() throws IOException {
        buf.append("</sheetData>").append(PAGE).append("</worksheet>");
        flush();
    }

    static double[] columnChars(ColumnWidths widths) {
        double[] chars = new double[widths.columns()];
        double target = 0;
        long drawn = 0;
        for (int c = 0; c < chars.length; c++) {
            target += widths.points(c);
            long edge = Math.round(target / ColumnWidths.OUR_PIXEL);
            long px = Math.max(1, Math.min(MAX_COLUMN_PIXELS, edge - drawn));
            drawn += px;
            chars[c] = Math.floor(px * 256.0 / 7) / 256;
        }
        return chars;
    }

    static boolean shortRow(int r) {
        double px = ROW_PITCH / PRINTER_PIXEL;
        return Math.round((r + 1) * px) - Math.round(r * px) < Math.round(px);
    }

    private static String cellText(String text) {
        return text.indexOf('\t') < 0 ? text : text.replace('\t', ' ');
    }

    private void column(int c) {
        int n = c + 1;
        int at = buf.length();
        while (n > 0) {
            int d = (n - 1) % 26;
            buf.insert(at, (char) ('A' + d));
            n = (n - 1) / 26;
        }
    }

    private static String format(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private void flush() throws IOException {
        out.append(buf);
        buf.setLength(0);
    }
}
