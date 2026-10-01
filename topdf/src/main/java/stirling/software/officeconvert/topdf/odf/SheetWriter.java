package stirling.software.officeconvert.topdf.odf;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

final class SheetWriter {

    static final int MAX_ROWS = 1_048_576;

    static final int MAX_COLS = 16_384;

    static final int MAX_EMITTED_ROWS = 300_000;

    static final long MAX_CELLS = 4_000_000;

    static final int FILLER = 2000;

    static final int STYLED_RUN = 100;

    private static final String ROW = "\u0001";

    private final OdsWriter w;

    private final Element table;

    private final int index;

    private final String name;

    private final List<Double> colWidths = new ArrayList<>();

    private final List<double[]> rowRuns = new ArrayList<>();

    private final List<int[]> merges = new ArrayList<>();

    private final List<Integer> rowBreaks = new ArrayList<>();

    private final List<Integer> colBreaks = new ArrayList<>();

    private final List<String> columnDefaults = new ArrayList<>();

    private final List<Element> frames = new ArrayList<>();

    private final List<int[]> frameCells = new ArrayList<>();

    private int headerRowStart = -1;

    private int headerRowEnd = -1;

    private int headerColStart = -1;

    private double fillerWidth = Double.NaN;

    private int headerColEnd = -1;

    private long cells;

    private int emittedRows;

    private int valueEnd;

    private int valueRow = -1;

    private int valueMaxRow = -1;

    private int maxCol = -1;

    private int maxRow = -1;

    private boolean hidden;

    SheetWriter(OdsWriter w, Element table, int index, String name) {
        this.w = w;
        this.table = table;
        this.index = index;
        this.name = name;
    }

    boolean hidden() {
        return hidden;
    }

    void write(String part) throws IOException {
        Props tp = w.styles.props("table", Dom.attr(table, Ns.TABLE, "style-name"), Styles.Scope.CONTENT,
                "table-properties", false);
        hidden = "false".equals(tp.get("table:display"));
        boolean rtl = WordPara.rtl(tp);
        StringBuilder cols = new StringBuilder();
        columns(table, cols, 0);
        StringBuilder data = new StringBuilder();
        rows(table, data, new int[] {0}, false, 0);
        if (maxRow - valueRow > STYLED_RUN) {
            data.setLength(valueEnd);
            maxRow = valueMaxRow;
        }
        for (Element s : Dom.kids(Dom.kid(table, Ns.TABLE, "shapes"))) {
            frames.add(s);
            frameCells.add(null);
        }
        String master = w.styles.inherited("table", Dom.attr(table, Ns.TABLE, "style-name"), Styles.Scope.CONTENT,
                Ns.STYLE, "master-page-name");
        SheetPage page = new SheetPage(w.styles, master);
        StringBuilder x = new StringBuilder(Xml.HEAD).append("<worksheet xmlns=\"").append(Xml.S)
                .append("\" xmlns:r=\"").append(Xml.R).append("\">");
        if (page.fitToPage()) {
            x.append("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>");
        }
        if (maxRow >= 0 && maxCol >= 0) {
            x.append("<dimension ref=\"A1:").append(ref(maxCol, maxRow)).append("\"/>");
        }
        x.append("<sheetViews><sheetView workbookViewId=\"0\"").append(rtl ? " rightToLeft=\"1\"" : "")
                .append("/></sheetViews>");
        x.append("<sheetFormatPr defaultColWidth=\"").append(chars(Double.isNaN(fillerWidth) ? 64 : fillerWidth))
                .append("\" defaultRowHeight=\"")
                .append(defaultRowHeight()).append("\"/>");
        if (!cols.isEmpty()) {
            x.append("<cols>").append(cols).append("</cols>");
        }
        x.append("<sheetData>").append(data).append("</sheetData>");
        if (!merges.isEmpty()) {
            x.append("<mergeCells count=\"").append(merges.size()).append("\">");
            for (int[] m : merges) {
                x.append("<mergeCell ref=\"").append(ref(m[0], m[1])).append(':').append(ref(m[2], m[3]))
                        .append("\"/>");
            }
            x.append("</mergeCells>");
        }
        x.append(page.xml());
        breaks(x, "rowBreaks", rowBreaks, MAX_COLS - 1);
        breaks(x, "colBreaks", colBreaks, MAX_ROWS - 1);
        String drawing = drawing(part);
        if (drawing != null) {
            x.append("<drawing r:id=\"").append(drawing).append("\"/>");
        }
        x.append("</worksheet>");
        w.out.xml(part, Xml.CT + "spreadsheetml.worksheet+xml", x);
    }

    private static void breaks(StringBuilder x, String tag, List<Integer> at, int max) {
        if (at.isEmpty()) {
            return;
        }
        x.append('<').append(tag).append(" count=\"").append(at.size()).append("\" manualBreakCount=\"")
                .append(at.size()).append("\">");
        for (int b : at) {
            x.append("<brk id=\"").append(b).append("\" max=\"").append(max).append("\" man=\"1\"/>");
        }
        x.append("</").append(tag).append('>');
    }

    private String defaultRowHeight() {
        return String.valueOf(defaultRowPoints());
    }

    private double defaultRowPoints() {
        return Math.round(w.cellStyles.defaultSize() * 1.28 * 4) / 4.0;
    }

    private void columns(Element parent, StringBuilder cols, int depth) {
        if (depth > 4) {
            return;
        }
        for (Element k : Dom.kids(parent)) {
            String local = Dom.local(k);
            if (!Ns.TABLE.equals(k.getNamespaceURI())) {
                continue;
            }
            if (local.equals("table-column")) {
                int repeat = Math.max(1, Dom.integer(k, Ns.TABLE, "number-columns-repeated", 1));
                int start = colWidths.size();
                if (start >= MAX_COLS) {
                    return;
                }
                repeat = Math.min(repeat, MAX_COLS - start);
                Props cp = w.styles.props("table-column", Dom.attr(k, Ns.TABLE, "style-name"), Styles.Scope.CONTENT,
                        "table-column-properties", false);
                double width = cp.pt("style:column-width", Double.NaN);
                String vis = Dom.attr(k, Ns.TABLE, "visibility", "visible");
                boolean colHidden = vis.equals("collapse") || vis.equals("filter");
                String def = Dom.attr(k, Ns.TABLE, "default-cell-style-name");
                SheetStyles.Xf xf = def == null ? null : w.cellStyles.xf(def);
                for (int i = 0; i < repeat; i++) {
                    colWidths.add(Double.isNaN(width) ? 64.0 : width);
                    columnDefaults.add(def);
                }
                if ("page".equals(cp.get("fo:break-before")) && start > 0) {
                    colBreaks.add(start);
                }
                if (repeat > FILLER && (xf == null || !xf.visible()) && !colHidden) {
                    if (!Double.isNaN(width)) {
                        fillerWidth = width;
                    }
                    continue;
                }
                cols.append("<col min=\"").append(start + 1).append("\" max=\"").append(start + repeat).append('"');
                if (!Double.isNaN(width)) {
                    cols.append(" width=\"").append(chars(width)).append("\" customWidth=\"1\"");
                }
                if (colHidden) {
                    cols.append(" hidden=\"1\"");
                }
                cols.append("/>");
            } else if (local.equals("table-header-columns")) {
                headerColStart = colWidths.size();
                columns(k, cols, depth + 1);
                headerColEnd = colWidths.size() - 1;
            } else if (local.equals("table-columns") || local.equals("table-column-group")) {
                columns(k, cols, depth + 1);
            }
        }
    }

    private String chars(double pt) {
        double k = digitWidth(w.cellStyles.defaultFont()) * w.cellStyles.defaultSize();
        double c = pt / k;
        return String.valueOf(Math.round(Math.max(0, Math.min(255, c)) * 1000) / 1000.0);
    }

    static double digitWidth(String font) {
        String f = font == null ? "" : font.toLowerCase(Locale.ROOT);
        return switch (f) {
            case "calibri", "carlito" -> 0.5046;
            case "cambria", "caladea" -> 0.5181;
            case "arial", "liberation sans", "helvetica", "arimo" -> 0.5546;
            case "times new roman", "liberation serif", "times", "tinos" -> 0.5;
            case "courier new", "liberation mono", "courier", "cousine" -> 0.6001;
            case "aptos narrow" -> 0.5728;
            case "aptos", "aptos display" -> 0.5728;
            case "verdana", "dejavu sans" -> 0.6362;
            case "tahoma" -> 0.5459;
            case "segoe ui" -> 0.5591;
            case "georgia" -> 0.6182;
            default -> 0.55;
        };
    }

    private void rows(Element parent, StringBuilder data, int[] row, boolean header, int depth) {
        if (depth > 4) {
            return;
        }
        for (Element k : Dom.kids(parent)) {
            if (!Ns.TABLE.equals(k.getNamespaceURI())) {
                continue;
            }
            switch (Dom.local(k)) {
                case "table-row" -> row(k, data, row);
                case "table-header-rows" -> {
                    headerRowStart = row[0];
                    rows(k, data, row, true, depth + 1);
                    headerRowEnd = row[0] - 1;
                }
                case "table-rows", "table-row-group" -> rows(k, data, row, header, depth + 1);
                default -> {
                }
            }
        }
    }

    private void row(Element r, StringBuilder data, int[] at) {
        int repeat = Math.max(1, Dom.integer(r, Ns.TABLE, "number-rows-repeated", 1));
        int start = at[0];
        if (start >= MAX_ROWS) {
            return;
        }
        repeat = Math.min(repeat, MAX_ROWS - start);
        at[0] = start + repeat;
        Props rp = w.styles.props("table-row", Dom.attr(r, Ns.TABLE, "style-name"), Styles.Scope.CONTENT,
                "table-row-properties", false);
        double height = rp.pt("style:row-height", Double.NaN);
        boolean optimal = !"false".equals(rp.get("style:use-optimal-row-height"));
        rowRuns.add(new double[] {start, repeat, Double.isNaN(height) ? 12.8 : height});
        if ("page".equals(rp.get("fo:break-before")) && start > 0) {
            rowBreaks.add(start);
        }
        String vis = Dom.attr(r, Ns.TABLE, "visibility", "visible");
        boolean rowHidden = vis.equals("collapse") || vis.equals("filter");
        StringBuilder cellsXml = new StringBuilder();
        List<int[]> rowMerges = new ArrayList<>();
        boolean content = cells(r, start, cellsXml, rowMerges);
        boolean hasValue = cellsXml.indexOf("<v>") >= 0;
        boolean wraps = rowWraps;
        boolean custom = !optimal && !Double.isNaN(height);
        if (!content && !custom && !rowHidden) {
            return;
        }
        if (!hasValue && repeat > STYLED_RUN) {
            return;
        }
        int copies = content ? Math.min(repeat, 10_000) : repeat;
        for (int i = 0; i < copies; i++) {
            if (emittedRows >= MAX_EMITTED_ROWS || cells > MAX_CELLS) {
                return;
            }
            emittedRows++;
            int n = start + i;
            data.append("<row r=\"").append(n + 1).append('"');
            if (!Double.isNaN(height) && (custom || rowHidden || !wraps)) {
                data.append(" ht=\"").append(Math.round(height * 100) / 100.0).append('"');
                if (custom || rowHidden) {
                    data.append(" customHeight=\"1\"");
                }
            }
            if (rowHidden) {
                data.append(" hidden=\"1\"");
            }
            data.append('>');
            if (content) {
                data.append(cellsXml.toString().replace(ROW, String.valueOf(n + 1)));
                maxRow = Math.max(maxRow, n);
            }
            data.append("</row>");
            for (int[] m : rowMerges) {
                merges.add(new int[] {m[0], n, m[2], Math.min(MAX_ROWS - 1, n + m[3] - m[1])});
            }
            if (hasValue) {
                valueEnd = data.length();
                valueRow = n;
                valueMaxRow = maxRow;
            }
        }
    }

    private boolean rowWraps;

    private boolean cells(Element r, int row, StringBuilder out, List<int[]> rowMerges) {
        rowWraps = false;
        int col = 0;
        boolean any = false;
        String rowDefault = Dom.attr(r, Ns.TABLE, "default-cell-style-name");
        for (Element c : Dom.kids(r)) {
            boolean covered = Dom.is(c, Ns.TABLE, "covered-table-cell");
            if (!covered && !Dom.is(c, Ns.TABLE, "table-cell")) {
                continue;
            }
            int repeat = Math.max(1, Dom.integer(c, Ns.TABLE, "number-columns-repeated", 1));
            if (col >= MAX_COLS) {
                break;
            }
            repeat = Math.min(repeat, MAX_COLS - col);
            for (Element f : Dom.kids(c)) {
                if (Ns.DRAW.equals(f.getNamespaceURI())) {
                    frames.add(f);
                    frameCells.add(new int[] {col, row});
                }
            }
            String style = Dom.attr(c, Ns.TABLE, "style-name");
            String columnDefault = col < columnDefaults.size() ? columnDefaults.get(col) : null;
            if (style == null) {
                style = rowDefault != null ? rowDefault : columnDefault;
            }
            SheetStyles.Xf xf = w.cellStyles.xf(style);
            String value = covered ? null : value(c, xf);
            rowWraps |= value != null && xf.wrap();
            boolean override = xf.index() != 0 && xf.visible();
            if (!covered) {
                int cs = Dom.integer(c, Ns.TABLE, "number-columns-spanned", 1);
                int rs = Dom.integer(c, Ns.TABLE, "number-rows-spanned", 1);
                if ((cs > 1 || rs > 1) && merges.size() < 100_000) {
                    rowMerges.add(new int[] {col, row, Math.min(MAX_COLS - 1, col + Math.max(1, cs) - 1),
                        row + Math.max(1, rs) - 1});
                    any = true;
                }
            }
            if ((value != null || override) && !(value == null && repeat > FILLER)) {
                for (int i = 0; i < repeat && cells < MAX_CELLS; i++) {
                    out.append("<c r=\"").append(column(col + i)).append(ROW).append('"');
                    if (xf.index() != 0) {
                        out.append(" s=\"").append(xf.index()).append('"');
                    }
                    out.append(value == null ? "/>" : value);
                    cells++;
                    maxCol = Math.max(maxCol, col + i);
                }
                any = true;
            }
            col += repeat;
        }
        return any;
    }

    private String value(Element c, SheetStyles.Xf xf) {
        String type = Dom.attr(c, Ns.OFFICE, "value-type");
        String calc = Dom.attr(c, Ns.CALCEXT, "value-type");
        if ("error".equals(calc)) {
            String t = text(c);
            return " t=\"e\"><v>" + Xml.esc(t.isEmpty() ? "#N/A" : t) + "</v></c>";
        }
        if (type == null) {
            String t = text(c);
            return t.isEmpty() ? null : " t=\"s\"><v>" + w.string(t) + "</v></c>";
        }
        switch (type) {
            case "float", "percentage", "currency" -> {
                String v = Dom.attr(c, Ns.OFFICE, "value");
                Double d = number(v);
                return d == null ? null : "><v>" + format(d) + "</v></c>";
            }
            case "date" -> {
                Double d = date(Dom.attr(c, Ns.OFFICE, "date-value"));
                return d == null ? null : "><v>" + format(d) + "</v></c>";
            }
            case "time" -> {
                Double d = time(Dom.attr(c, Ns.OFFICE, "time-value"));
                if (d == null) {
                    d = number(Dom.attr(c, Ns.OFFICE, "value"));
                }
                return d == null ? null : "><v>" + format(d) + "</v></c>";
            }
            case "boolean" -> {
                String b = Dom.attr(c, Ns.OFFICE, "boolean-value", "false");
                return " t=\"b\"><v>" + ("true".equalsIgnoreCase(b) || "1".equals(b) ? 1 : 0) + "</v></c>";
            }
            default -> {
                String s = Dom.attr(c, Ns.OFFICE, "string-value");
                String t = s != null ? s : text(c);
                if (t.isEmpty() || w.stringsFull()) {
                    return null;
                }
                return " t=\"s\"><v>" + w.string(t) + "</v></c>";
            }
        }
    }

    private static Double number(String v) {
        if (v == null) {
            return null;
        }
        try {
            double d = Double.parseDouble(v.trim());
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double date(String v) {
        if (v == null || v.length() < 10) {
            return null;
        }
        try {
            LocalDate day = LocalDate.parse(v.substring(0, 10));
            double serial = ChronoUnit.DAYS.between(w.nullDate, day);
            if (v.length() > 10 && v.charAt(10) == 'T') {
                LocalDateTime t = LocalDateTime.parse(v.length() > 29 ? v.substring(0, 29) : v);
                serial += t.toLocalTime().toNanoOfDay() / 86_400e9;
            }
            return serial;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Double time(String v) {
        if (v == null) {
            return null;
        }
        try {
            Duration d = Duration.parse(v.trim());
            return d.toNanos() / 86_400e9;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String format(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    static String text(Element c) {
        StringBuilder b = new StringBuilder();
        boolean first = true;
        for (Element p : Dom.kids(c)) {
            if (!Dom.is(p, Ns.TEXT, "p") && !Dom.is(p, Ns.TEXT, "h")) {
                continue;
            }
            if (!first) {
                b.append('\n');
            }
            first = false;
            inline(p, b, 0);
            if (b.length() > 32_767) {
                return b.substring(0, 32_767);
            }
        }
        return b.toString();
    }

    private static void inline(Element e, StringBuilder b, int depth) {
        if (depth > 32) {
            return;
        }
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                b.append(n.getNodeValue().replaceAll("[\\t\\n\\r ]+", " "));
            } else if (n instanceof Element k && Ns.TEXT.equals(k.getNamespaceURI())) {
                switch (Dom.local(k)) {
                    case "s" -> b.append(" ".repeat(Math.max(1, Math.min(1000, Dom.integer(k, Ns.TEXT, "c", 1)))));
                    case "tab" -> b.append('\t');
                    case "line-break" -> b.append('\n');
                    case "note", "bookmark", "bookmark-start", "bookmark-end", "soft-page-break" -> {
                    }
                    default -> inline(k, b, depth + 1);
                }
            }
        }
    }

    static String ref(int col, int row) {
        StringBuilder s = new StringBuilder();
        int c = col + 1;
        while (c > 0) {
            int m = (c - 1) % 26;
            s.insert(0, (char) ('A' + m));
            c = (c - 1) / 26;
        }
        return s.append(row + 1).toString();
    }

    String definedNames() {
        StringBuilder b = new StringBuilder();
        String q = "'" + name.replace("'", "''") + "'";
        String ranges = Dom.attr(table, Ns.TABLE, "print-ranges");
        if (ranges != null && !ranges.isBlank()) {
            List<String> parts = new ArrayList<>();
            for (String r : PrintRange.split(ranges.trim())) {
                String a = PrintRange.area(r);
                if (a != null) {
                    parts.add(q + "!" + a);
                }
            }
            if (!parts.isEmpty()) {
                b.append("<definedName name=\"_xlnm.Print_Area\" localSheetId=\"").append(index).append("\">")
                        .append(Xml.esc(String.join(",", parts))).append("</definedName>");
            }
        }
        List<String> titles = new ArrayList<>();
        if (headerColStart >= 0 && headerColEnd >= headerColStart) {
            titles.add(q + "!$" + column(headerColStart) + ":$" + column(headerColEnd));
        }
        if (headerRowStart >= 0 && headerRowEnd >= headerRowStart) {
            titles.add(q + "!$" + (headerRowStart + 1) + ":$" + (headerRowEnd + 1));
        }
        if (!titles.isEmpty()) {
            b.append("<definedName name=\"_xlnm.Print_Titles\" localSheetId=\"").append(index).append("\">")
                    .append(Xml.esc(String.join(",", titles))).append("</definedName>");
        }
        return b.toString();
    }

    static String column(int col) {
        String r = ref(col, 0);
        return r.substring(0, r.length() - 1);
    }

    private String drawing(String sheetPart) throws IOException {
        if (frames.isEmpty()) {
            return null;
        }
        int n = ++w.drawings;
        Part part = new Part("xl/drawings/drawing" + n + ".xml");
        SheetDrawing d = new SheetDrawing(w, part, colWidths, rowRuns);
        String xml = d.xml(frames, frameCells);
        if (xml == null) {
            return null;
        }
        w.out.xml(part.name, Xml.CT + "drawing+xml", xml);
        if (!part.rels.isEmpty()) {
            w.out.xml("xl/drawings/_rels/drawing" + n + ".xml.rels", null, part.rels.xml());
        }
        Rels rels = new Rels();
        String id = rels.add("drawing", "../drawings/drawing" + n + ".xml");
        String file = sheetPart.substring(sheetPart.lastIndexOf('/') + 1);
        w.out.xml("xl/worksheets/_rels/" + file + ".rels", null, rels.xml());
        return id;
    }
}
