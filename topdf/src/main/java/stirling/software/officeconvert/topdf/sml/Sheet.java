package stirling.software.officeconvert.topdf.sml;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.xls.Parts;
import stirling.software.officeconvert.topdf.xls.Xml;

/** One Worksheet of a SpreadsheetML 2003 workbook streamed into worksheet XML: columns, then rows as they are read
 * with their cells' cached values, then merges and the print settings of its WorksheetOptions. */
final class Sheet {

    private static final int MAX_ROWS = 1_048_576;

    private static final int MAX_COLS = 16_384;

    private static final int MAX_MERGES = 100_000;

    private final Styles styles;

    private final boolean date1904;

    private final int digitPx;

    private final List<String> merges = new ArrayList<>();

    private final StringBuilder cols = new StringBuilder();

    private String format = "";

    private final Node options;

    private final Node breaks;

    private final SpanBudget spans;

    private String tableStyle;

    private int row;

    private boolean dataOpen;

    private int spanCount;

    private double spanHeight;

    boolean truncated;

    boolean heightsDropped;

    Sheet(Styles styles, boolean date1904, int digitPx, Node options, Node breaks, SpanBudget spans) {
        this.styles = styles;
        this.date1904 = date1904;
        this.digitPx = Math.max(1, digitPx);
        this.options = options;
        this.breaks = breaks;
        this.spans = spans;
    }

    /** Reads the Worksheet the reader is on to its end, writing as it goes. */
    void write(XMLStreamReader r, Parts.Part out) throws XMLStreamException, IOException {
        out.write(Xml.HEAD + "<worksheet xmlns=\"" + Xml.MAIN + "\" xmlns:r=\"" + Xml.REL + "\">");
        while (r.hasNext()) {
            int e = r.next();
            if (e == XMLStreamConstants.END_ELEMENT) {
                break;
            }
            if (e != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            switch (r.getLocalName()) {
                case "Table" -> table(r, out);
                default -> Node.skip(r);
            }
        }
        if (!dataOpen) {
            out.write(sheetHead() + "<sheetData>");
            dataOpen = true;
        }
        out.write("</sheetData>");
        tail(out);
    }

    private String sheetHead() {
        StringBuilder b = new StringBuilder();
        if (options != null && options.kid("FitToPage") != null) {
            b.append("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>");
        }
        b.append("<sheetViews><sheetView workbookViewId=\"0\"");
        if (options != null && options.kid("DoNotDisplayGridlines") != null) {
            b.append(" showGridLines=\"0\"");
        }
        if (options != null && options.kid("DisplayRightToLeft") != null) {
            b.append(" rightToLeft=\"1\"");
        }
        if (options != null && options.kid("DoNotDisplayZeros") != null) {
            b.append(" showZeros=\"0\"");
        }
        b.append("/></sheetViews>").append(format);
        if (!cols.isEmpty()) {
            b.append("<cols>").append(cols).append("</cols>");
        }
        return b.toString();
    }

    private void table(XMLStreamReader r, Parts.Part out) throws XMLStreamException, IOException {
        Map<String, String> attrs = new java.util.HashMap<>();
        for (int i = 0; i < r.getAttributeCount(); i++) {
            attrs.put(r.getAttributeLocalName(i), r.getAttributeValue(i));
        }
        tableStyle = attrs.get("StyleID");
        double defaultWidth = Styles.parse(attrs.get("DefaultColumnWidth"), -1);
        double defaultHeight = Styles.parse(attrs.get("DefaultRowHeight"), -1);
        StringBuilder f = new StringBuilder("<sheetFormatPr");
        if (defaultWidth > 0) {
            f.append(" defaultColWidth=\"").append(chars(defaultWidth)).append('"');
        }
        f.append(" defaultRowHeight=\"").append(defaultHeight > 0 ? defaultHeight : 12.75).append('"');
        if (defaultHeight > 0) {
            f.append(" customHeight=\"1\"");
        }
        format = f.append("/>").toString();
        int col = 0;
        while (r.hasNext()) {
            int e = r.next();
            if (e == XMLStreamConstants.END_ELEMENT) {
                return;
            }
            if (e != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            switch (r.getLocalName()) {
                case "Column" -> col = column(Node.read(r), col);
                case "Row" -> {
                    if (!dataOpen) {
                        out.write(sheetHead() + "<sheetData>");
                        dataOpen = true;
                    }
                    if (truncated) {
                        Node.skip(r);
                    } else {
                        row(Node.read(r), out);
                    }
                }
                default -> Node.skip(r);
            }
        }
    }

    private double chars(double points) {
        double px = points * 4 / 3;
        double chars = px < digitPx + 5 ? px / (digitPx + 5) : (px - 5) / digitPx;
        return Math.max(0, Math.floor(chars * 100 + 0.5) / 100);
    }

    private int column(Node c, int last) {
        int index = c.integer("Index", last + 1);
        int span = Math.max(0, c.integer("Span", 0));
        if (index < 1 || index > MAX_COLS || cols.length() > 1 << 20) {
            return last;
        }
        int end = Math.min(MAX_COLS, index + span);
        cols.append("<col min=\"").append(index).append("\" max=\"").append(end).append('"');
        double width = c.number("Width", -1);
        if (width >= 0) {
            cols.append(" width=\"").append(chars(width)).append("\" customWidth=\"1\"");
        }
        if (c.flag("Hidden")) {
            cols.append(" hidden=\"1\"");
        }
        String style = c.attr("StyleID");
        if (style != null) {
            cols.append(" style=\"").append(styles.index(style)).append('"');
        }
        cols.append("/>");
        return end;
    }

    private void row(Node n, Parts.Part out) throws IOException {
        if (out.full()) {
            truncated = true;
            return;
        }
        int index = n.integer("Index", row + 1);
        if (index <= row || index > MAX_ROWS) {
            return;
        }
        if (!spannedRows(out)) {
            return;
        }
        row = index;
        String rowStyle = n.attr("StyleID");
        StringBuilder b = new StringBuilder("<row r=\"").append(index).append('"');
        double height = n.number("Height", -1);
        if (height >= 0 && height < 410) {
            b.append(" ht=\"").append(height).append("\" customHeight=\"1\"");
        }
        if (n.flag("Hidden")) {
            b.append(" hidden=\"1\"");
        }
        if (rowStyle != null) {
            b.append(" s=\"").append(styles.index(rowStyle)).append("\" customFormat=\"1\"");
        }
        b.append('>');
        int col = 0;
        for (Node c : n.all("Cell")) {
            int at = c.integer("Index", col + 1);
            if (at <= col || at > MAX_COLS) {
                continue;
            }
            col = at;
            int across = Math.max(0, c.integer("MergeAcross", 0));
            int down = Math.max(0, c.integer("MergeDown", 0));
            if ((across > 0 || down > 0) && merges.size() < MAX_MERGES) {
                merges.add(ref(index, at) + ":" + ref(Math.min(MAX_ROWS, index + down), Math.min(MAX_COLS, at + across)));
            }
            String style = c.attr("StyleID") != null ? c.attr("StyleID") : rowStyle != null ? rowStyle : tableStyle;
            b.append(cell(c, ref(index, at), styles.index(style == null ? "Default" : style)));
            col = Math.min(MAX_COLS, at + across);
        }
        out.write(b.append("</row>").toString());
        int span = Math.min(Math.max(0, n.integer("Span", 0)), MAX_ROWS - row);
        if (span > 0 && height >= 0) {
            spanCount = span;
            spanHeight = height;
        }
        row += span;
        if (out.full()) {
            truncated = true;
        }
    }

    private boolean spannedRows(Parts.Part out) throws IOException {
        int first = row - spanCount + 1;
        int granted = spans.take(spanCount);
        heightsDropped |= granted < spanCount;
        spanCount = 0;
        for (int i = 0; i < granted; i++) {
            if ((i & 4095) == 4095) {
                OfficeZip.checkNotInterrupted();
                if (out.full()) {
                    truncated = true;
                    return false;
                }
            }
            out.write("<row r=\"" + (first + i) + "\" ht=\"" + spanHeight + "\" customHeight=\"1\"/>");
        }
        return true;
    }

    private String cell(Node c, String ref, int style) {
        String s = style == 0 ? "" : " s=\"" + style + "\"";
        Node data = c.kid("Data");
        if (data == null) {
            return style == 0 ? "" : "<c r=\"" + ref + "\"" + s + "/>";
        }
        String type = data.attr("Type");
        String value = data.text();
        if (type == null) {
            type = "String";
        }
        switch (type) {
            case "Number" -> {
                double v = Styles.parse(value, Double.NaN);
                return Double.isNaN(v) ? text(ref, s, value) : "<c r=\"" + ref + "\"" + s + "><v>" + number(v) + "</v></c>";
            }
            case "DateTime" -> {
                double v = Formats.serial(value, date1904);
                return Double.isNaN(v) ? text(ref, s, value) : "<c r=\"" + ref + "\"" + s + "><v>" + number(v) + "</v></c>";
            }
            case "Boolean" -> {
                return "<c r=\"" + ref + "\"" + s + " t=\"b\"><v>" + (value.trim().equals("1")
                        || value.trim().equalsIgnoreCase("true") ? 1 : 0) + "</v></c>";
            }
            case "Error" -> {
                return "<c r=\"" + ref + "\"" + s + " t=\"e\"><v>" + Xml.attr(value.trim()) + "</v></c>";
            }
            default -> {
                if (hasMarkup(data)) {
                    return "<c r=\"" + ref + "\"" + s + " t=\"inlineStr\"><is>" + RichData.runs(data, styles) + "</is></c>";
                }
                return text(ref, s, value);
            }
        }
    }

    private static boolean hasMarkup(Node data) {
        for (Node k : data.kids()) {
            if (!k.local().equals("#text")) {
                return true;
            }
        }
        return false;
    }

    private static String text(String ref, String s, String value) {
        return "<c r=\"" + ref + "\"" + s + " t=\"inlineStr\"><is><t xml:space=\"preserve\">" + Xml.text(value)
                + "</t></is></c>";
    }

    static String number(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    static String ref(int row, int col) {
        StringBuilder b = new StringBuilder(4);
        int n = col;
        while (n > 0) {
            int m = (n - 1) % 26;
            b.insert(0, (char) ('A' + m));
            n = (n - 1) / 26;
        }
        return b.append(row).toString();
    }

    private void tail(Parts.Part out) throws IOException {
        if (!merges.isEmpty()) {
            out.write("<mergeCells count=\"" + merges.size() + "\">");
            for (String m : merges) {
                out.write("<mergeCell ref=\"" + m + "\"/>");
            }
            out.write("</mergeCells>");
        }
        out.write(PageOptions.xml(options, breaks));
        out.write("</worksheet>");
    }
}
