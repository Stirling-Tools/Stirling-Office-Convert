package stirling.software.officeconvert.topdf.uof;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.io.OfficeZip;

final class UofSheets {

    static final int MAX_ROWS = 1_048_576;

    static final int MAX_COLUMNS = 16_384;

    static final int MAX_SHEETS = 1_000;

    static final long MAX_CELLS = 1_000_000;

    private final Element root;

    private final UofParts parts;

    private final UofUnits units;

    private final Map<String, String> fonts;

    private final Map<String, Element> cellStyles = new HashMap<>();

    private final Map<String, Integer> xfIndex = new LinkedHashMap<>();

    private final List<String> fontXml = new ArrayList<>();

    private final List<String> fillXml = new ArrayList<>();

    private final List<String> borderXml = new ArrayList<>();

    private final List<String> xfXml = new ArrayList<>();

    private final Map<String, Integer> numFmts = new LinkedHashMap<>();

    private long cells;

    private UofSheets(Element root, UofParts parts) {
        this.root = root;
        this.parts = parts;
        String unit = Uof.text(Uof.path(root, "电子表格", "公用处理规则", "度量单位")).strip();
        this.units = UofUnits.of(unit);
        this.fonts = UofText.fonts(root);
        String normal = null;
        for (Element s : Uof.kids(Uof.kid(root, "式样集"), "单元格式样")) {
            String id = Uof.attr(s, "标识符");
            if (id != null) {
                cellStyles.putIfAbsent(id, s);
                String name = String.valueOf(Uof.attr(s, "名称"));
                if (normal == null && (name.equals("Normal") || name.equals("常规")) || id.equals("Default")) {
                    normal = id;
                }
            }
        }
        Element base = normal == null ? null : cellStyles.get(normal);
        index(fontXml, font(Uof.kid(base, "字体格式")));
        index(fillXml, "<fill><patternFill patternType=\"none\"/></fill>");
        index(fillXml, "<fill><patternFill patternType=\"gray125\"/></fill>");
        index(borderXml, "<border><left/><right/><top/><bottom/><diagonal/></border>");
        index(xfXml, base == null ? "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                : xml(base, null));
        if (base != null) {
            xfIndex.put(normal + '\u0000' + null, 0);
        }
    }

    static void write(Element root, UofParts parts) throws IOException {
        new UofSheets(root, parts).write();
    }

    private void write() throws IOException {
        List<Element> sheets = Uof.kids(Uof.path(root, "电子表格", "主体"), "工作表");
        if (sheets.size() > MAX_SHEETS) {
            sheets = sheets.subList(0, MAX_SHEETS);
        }
        List<String> names = new ArrayList<>();
        int n = 0;
        for (Element sheet : sheets) {
            OfficeZip.checkNotInterrupted();
            n++;
            parts.add("xl/worksheets/sheet" + n + ".xml", sheet(sheet));
            names.add(sheetName(Uof.attr(sheet, "名称"), n, names));
        }
        if (n == 0) {
            n = 1;
            parts.add("xl/worksheets/sheet1.xml", UofParts.XML + "<worksheet xmlns=\"" + MAIN + "\"><sheetData/></worksheet>");
            names.add("Sheet1");
        }
        StringBuilder wb = new StringBuilder(UofParts.XML);
        wb.append("<workbook xmlns=\"").append(MAIN).append("\" xmlns:r=\"").append(R).append("\"><sheets>");
        StringBuilder rels = new StringBuilder(UofParts.XML);
        rels.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
                .append("<Relationship Id=\"rSt\" Type=\"").append(UofParts.REL).append("styles\" Target=\"styles.xml\"/>");
        StringBuilder types = new StringBuilder(UofParts.XML);
        types.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
                .append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i = 1; i <= n; i++) {
            wb.append("<sheet name=\"").append(Uof.esc(names.get(i - 1))).append("\" sheetId=\"").append(i)
                    .append("\" r:id=\"rW").append(i).append("\"/>");
            rels.append("<Relationship Id=\"rW").append(i).append("\" Type=\"").append(UofParts.REL)
                    .append("worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
            types.append("<Override PartName=\"/xl/worksheets/sheet").append(i).append(".xml\" ContentType=\"")
                    .append("application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        wb.append("</sheets></workbook>");
        rels.append("</Relationships>");
        types.append("</Types>");
        parts.add("xl/workbook.xml", wb.toString());
        parts.add("xl/_rels/workbook.xml.rels", rels.toString());
        parts.add("xl/styles.xml", styles());
        parts.add("[Content_Types].xml", types.toString());
        parts.add("_rels/.rels", UofParts.XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"" + UofParts.REL + "officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>");
    }

    static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

    static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private static String sheetName(String name, int n, List<String> taken) {
        String s = name == null ? "" : name.replaceAll("[\\\\/?*\\[\\]:]", "_").strip();
        if (s.length() > 31) {
            s = s.substring(0, 31);
        }
        if (s.isEmpty()) {
            s = "Sheet" + n;
        }
        String base = s;
        int k = 2;
        while (contains(taken, s)) {
            String suffix = " (" + k++ + ")";
            s = (base.length() + suffix.length() > 31 ? base.substring(0, 31 - suffix.length()) : base) + suffix;
        }
        return s;
    }

    private static boolean contains(List<String> names, String s) {
        for (String n : names) {
            if (n.equalsIgnoreCase(s)) {
                return true;
            }
        }
        return false;
    }

    private String sheet(Element sheet) throws IOException {
        Element content = Uof.kid(sheet, "工作表内容");
        StringBuilder b = new StringBuilder(UofParts.XML);
        b.append("<worksheet xmlns=\"").append(MAIN).append("\" xmlns:r=\"").append(R).append("\">");
        b.append("<sheetViews><sheetView workbookViewId=\"0\"");
        Element view = Uof.path(sheet, "工作表属性", "视图");
        if (view != null && "false".equalsIgnoreCase(String.valueOf(Uof.attr(Uof.kid(view, "网格"), "值")))) {
            b.append(" showGridLines=\"0\"");
        }
        b.append("/></sheetViews>");
        double defaultWidth = Uof.number(Uof.attr(content, "缺省列宽"));
        double defaultHeight = Uof.number(Uof.attr(content, "缺省行高"));
        b.append("<sheetFormatPr defaultRowHeight=\"")
                .append(defaultHeight > 0 && defaultHeight < 400 ? defaultHeight : 13.5).append("\"/>");
        Map<Integer, Integer> fit = fit(content);
        Set<Integer> sized = new HashSet<>();
        StringBuilder cols = new StringBuilder();
        int next = 1;
        for (Element c : Uof.kids(content, "列")) {
            double index = Uof.number(Uof.attr(c, "列号"));
            int from = Double.isNaN(index) ? next : (int) Math.clamp(index, 1, MAX_COLUMNS);
            double span = Uof.number(Uof.attr(c, "跨度"));
            int to = (int) Math.clamp(from + (Double.isNaN(span) ? 1 : Math.max(1, span)) - 1, from, MAX_COLUMNS);
            next = to + 1;
            double pt = points(Uof.attr(c, "列宽"));
            boolean hidden = "true".equalsIgnoreCase(String.valueOf(Uof.attr(c, "隐藏")));
            if (pt > 0 || hidden) {
                for (int k = from; k <= to && k - from < 1024; k++) {
                    sized.add(k);
                }
                cols.append("<col min=\"").append(from).append("\" max=\"").append(to).append('"');
                if (pt > 0) {
                    cols.append(" width=\"").append(String.format(Locale.ROOT, "%.2f", Math.min(255, pt / 5.25)))
                            .append("\" customWidth=\"1\"");
                } else {
                    cols.append(" width=\"8.43\"");
                }
                if (hidden) {
                    cols.append(" hidden=\"1\"");
                }
                cols.append("/>");
            }
        }
        for (Map.Entry<Integer, Integer> e : new TreeMap<>(fit).entrySet()) {
            if (!sized.contains(e.getKey()) && e.getValue() > 8) {
                cols.append("<col min=\"").append(e.getKey()).append("\" max=\"").append(e.getKey())
                        .append("\" width=\"").append(Math.min(80, e.getValue() + 2)).append("\" customWidth=\"1\"/>");
            }
        }
        if (!cols.isEmpty()) {
            b.append("<cols>").append(sortCols(cols.toString())).append("</cols>");
        } else if (defaultWidth > 0) {
            double pt = points(Uof.attr(content, "缺省列宽"));
            if (pt > 0) {
                b.append("<cols><col min=\"1\" max=\"256\" width=\"")
                        .append(String.format(Locale.ROOT, "%.2f", Math.min(255, pt / 5.25))).append("\"/></cols>");
            }
        }
        b.append("<sheetData>");
        List<String> merges = new ArrayList<>();
        int rowNo = 1;
        for (Element r : Uof.kids(content, "行")) {
            if (rowNo > MAX_ROWS) {
                break;
            }
            double index = Uof.number(Uof.attr(r, "行号"));
            int row = Double.isNaN(index) ? rowNo : (int) Math.clamp(index, rowNo, MAX_ROWS);
            double span = Uof.number(Uof.attr(r, "跨度"));
            int full = Double.isNaN(span) ? 1 : (int) Math.clamp(span, 1, MAX_ROWS);
            int repeat = Math.min(full, 64);
            List<Element> rowCells = Uof.kids(r, "单元格");
            int start = row;
            if (rowCells.isEmpty() || row > MAX_ROWS) {
                rowNo = row + full;
                if (rowCells.isEmpty() && Uof.attr(r, "行高") == null) {
                    continue;
                }
            }
            for (int k = 0; k < repeat && row <= MAX_ROWS; k++, row++) {
                b.append("<row r=\"").append(row).append('"');
                double h = Uof.number(Uof.attr(r, "行高"));
                if (h > 0 && h < 410) {
                    b.append(" ht=\"").append(String.format(Locale.ROOT, "%.2f", h)).append("\" customHeight=\"1\"");
                }
                if ("true".equalsIgnoreCase(String.valueOf(Uof.attr(r, "隐藏")))) {
                    b.append(" hidden=\"1\"");
                }
                b.append('>');
                int col = 1;
                for (Element c : rowCells) {
                    if (cells++ > MAX_CELLS) {
                        break;
                    }
                    if ((cells & 1023) == 0) {
                        OfficeZip.checkNotInterrupted();
                    }
                    if (col > MAX_COLUMNS) {
                        break;
                    }
                    double ci = Uof.number(Uof.attr(c, "列号"));
                    if (!Double.isNaN(ci)) {
                        col = (int) Math.clamp(ci, col, MAX_COLUMNS);
                    }
                    if (col > MAX_COLUMNS) {
                        break;
                    }
                    String cell = cell(c, row, col);
                    b.append(cell);
                    if (b.length() > parts.limit()) {
                        throw new IOException("The UOF spreadsheet is too large to convert");
                    }
                    double across = Uof.number(Uof.attr(c, "合并列数"));
                    double down = Uof.number(Uof.attr(c, "合并行数"));
                    int ac = Double.isNaN(across) ? 0 : (int) Math.clamp(across, 0, MAX_COLUMNS - col);
                    int dr = Double.isNaN(down) ? 0 : (int) Math.clamp(down, 0, MAX_ROWS - row);
                    if ((ac > 0 || dr > 0) && merges.size() < 100_000) {
                        merges.add(ref(row, col) + ":" + ref(row + dr, col + ac));
                    }
                    col += 1 + ac;
                }
                b.append("</row>");
            }
            rowNo = (int) Math.min((long) start + full, MAX_ROWS + 1L);
        }
        b.append("</sheetData>");
        if (!merges.isEmpty()) {
            b.append("<mergeCells count=\"").append(merges.size()).append("\">");
            for (String m : merges) {
                b.append("<mergeCell ref=\"").append(m).append("\"/>");
            }
            b.append("</mergeCells>");
        }
        b.append(page(Uof.path(sheet, "工作表属性", "页面设置")));
        b.append("</worksheet>");
        return b.toString();
    }

    private static String sortCols(String cols) {
        TreeMap<Integer, String> byMin = new TreeMap<>();
        for (String c : cols.split("(?=<col )")) {
            if (c.isEmpty()) {
                continue;
            }
            int at = c.indexOf("min=\"") + 5;
            int min = Integer.parseInt(c.substring(at, c.indexOf('"', at)));
            byMin.putIfAbsent(min, c);
        }
        StringBuilder out = new StringBuilder();
        int last = 0;
        for (Map.Entry<Integer, String> e : byMin.entrySet()) {
            if (e.getKey() <= last) {
                continue;
            }
            String c = e.getValue();
            int at = c.indexOf("max=\"") + 5;
            last = Integer.parseInt(c.substring(at, c.indexOf('"', at)));
            out.append(c);
        }
        return out.toString();
    }

    private Map<Integer, Integer> fit(Element content) {
        Map<Integer, Integer> widest = new HashMap<>();
        int seen = 0;
        for (Element r : Uof.kids(content, "行")) {
            int col = 1;
            for (Element c : Uof.kids(r, "单元格")) {
                if (seen++ > 200_000) {
                    return widest;
                }
                if (col > MAX_COLUMNS) {
                    break;
                }
                double ci = Uof.number(Uof.attr(c, "列号"));
                if (!Double.isNaN(ci)) {
                    col = (int) Math.clamp(ci, col, MAX_COLUMNS);
                }
                Element data = Uof.kid(c, "数据");
                double across = Uof.number(Uof.attr(c, "合并列数"));
                if (data != null && (Double.isNaN(across) || across < 1) && col <= 1024) {
                    int width = 0;
                    for (String line : display(data).split("\n")) {
                        int w = 0;
                        for (int i = 0; i < line.length(); i++) {
                            w += Character.UnicodeScript.of(line.charAt(i)) == Character.UnicodeScript.HAN
                                    || line.charAt(i) >= 0xFF00 && line.charAt(i) <= 0xFFEF ? 2 : 1;
                        }
                        width = Math.max(width, w);
                    }
                    widest.merge(col, width, Math::max);
                }
                col++;
            }
        }
        return widest;
    }

    private String page(Element setup) {
        if (setup == null) {
            return "";
        }
        StringBuilder b = new StringBuilder("<pageMargins");
        Element m = Uof.kid(setup, "页边距");
        String[][] sides = {{"左", "left", "0.7"}, {"右", "right", "0.7"}, {"上", "top", "0.75"}, {"下", "bottom", "0.75"}};
        for (String[] s : sides) {
            long t = units.twips(Uof.attr(m, s[0]));
            String inches = UofUnits.known(t) && t >= 0 && t < 20_000
                    ? String.format(Locale.ROOT, "%.3f", t / 1440.0) : s[2];
            b.append(' ').append(s[1]).append("=\"").append(inches).append('"');
        }
        b.append(" header=\"0.3\" footer=\"0.3\"/>");
        b.append("<pageSetup");
        String scale = Uof.text(Uof.kid(setup, "缩放")).strip().replace("%", "");
        double sc = Uof.number(scale);
        if (sc >= 10 && sc <= 400) {
            b.append(" scale=\"").append(Math.round(sc)).append('"');
        }
        String paper = String.valueOf(Uof.attr(Uof.kid(setup, "纸张"), "纸型"));
        int size = switch (paper.toUpperCase(Locale.ROOT)) {
            case "A3" -> 8;
            case "A5" -> 11;
            case "B5" -> 13;
            case "LETTER" -> 1;
            case "LEGAL" -> 5;
            default -> 9;
        };
        b.append(" paperSize=\"").append(size).append('"');
        if ("landscape".equalsIgnoreCase(Uof.text(Uof.kid(setup, "纸张方向")).strip())) {
            b.append(" orientation=\"landscape\"");
        }
        b.append("/>");
        return b.toString();
    }

    private double points(String v) {
        long t = units.twips(v);
        return UofUnits.known(t) && t > 0 && t < 1_000_000 ? t / 20.0 : -1;
    }

    static String ref(int row, int col) {
        StringBuilder s = new StringBuilder();
        int c = col;
        while (c > 0) {
            int m = (c - 1) % 26;
            s.insert(0, (char) ('A' + m));
            c = (c - 1) / 26;
        }
        return s.append(row).toString();
    }

    private String cell(Element c, int row, int col) {
        String styleId = Uof.attr(c, "式样引用");
        Element data = Uof.kid(c, "数据");
        String shown = data == null ? null : inferredFormat(display(data));
        int xf = xf(styleId, shown);
        StringBuilder b = new StringBuilder("<c r=\"").append(ref(row, col)).append('"');
        if (xf > 0) {
            b.append(" s=\"").append(xf).append('"');
        }
        if (data == null) {
            return b.append("/>").toString();
        }
        String type = String.valueOf(Uof.attr(data, "数据类型")).toLowerCase(Locale.ROOT);
        String value = Uof.attr(data, "数据数值");
        String text = display(data);
        double number = Uof.number(value);
        boolean numeric = !Double.isNaN(number) && value != null && value.strip().matches("[-+]?[0-9.]+([eE][-+]?[0-9]+)?")
                && !type.equals("text") && !type.equals("string");
        if (numeric) {
            if (type.equals("boolean")) {
                return b.append(" t=\"b\"><v>").append(number != 0 ? 1 : 0).append("</v></c>").toString();
            }
            return b.append("><v>").append(value.strip()).append("</v></c>").toString();
        }
        if (text.isEmpty()) {
            return b.append("/>").toString();
        }
        return b.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(Uof.esc(text)).append("</t></is></c>")
                .toString();
    }

    private static String display(Element data) {
        StringBuilder t = new StringBuilder();
        for (Element p : Uof.kids(data)) {
            String n = Uof.name(p);
            if (n.equals("句")) {
                sentence(t, p);
            } else if (n.equals("段落")) {
                if (!t.isEmpty()) {
                    t.append('\n');
                }
                for (Element s : Uof.kids(p, "句")) {
                    sentence(t, s);
                }
            }
        }
        if (t.isEmpty()) {
            String v = Uof.attr(data, "数据数值");
            return v == null ? data.getTextContent().strip() : v;
        }
        return t.toString();
    }

    private static void sentence(StringBuilder t, Element s) {
        for (Element k : Uof.kids(s)) {
            switch (Uof.name(k)) {
                case "文本串" -> t.append(k.getTextContent());
                case "空格符" -> {
                    double n = Uof.number(Uof.attr(k, "个数"));
                    t.append(" ".repeat(Double.isNaN(n) ? 1 : (int) Math.clamp(n, 1, 200)));
                }
                case "换行符" -> t.append('\n');
                case "制表符" -> t.append('\t');
                default -> {}
            }
        }
    }

    static String inferredFormat(String text) {
        String t = text.strip();
        if (t.isEmpty() || t.length() > 40 || !t.matches("[-+(]?[0-9][0-9,]*(\\.[0-9]+)?%?\\)?")) {
            return null;
        }
        boolean grouped = t.contains(",");
        boolean percent = t.endsWith("%");
        int dot = t.indexOf('.');
        int decimals = 0;
        if (dot >= 0) {
            int end = dot + 1;
            while (end < t.length() && Character.isDigit(t.charAt(end))) {
                end++;
            }
            decimals = end - dot - 1;
        }
        if (!grouped && !percent && decimals == 0) {
            return null;
        }
        return (grouped ? "#,##0" : "0") + (decimals > 0 ? "." + "0".repeat(Math.min(decimals, 10)) : "")
                + (percent ? "%" : "");
    }

    private int xf(String styleId, String inferred) {
        Element s = styleId == null ? null : cellStyles.get(styleId);
        boolean general = s == null || numFmt(Uof.kid(s, "数字格式")) == 0;
        String extra = general && inferred != null ? inferred : null;
        String key = styleId + '\u0000' + extra;
        Integer known = xfIndex.get(key);
        if (known != null) {
            return known;
        }
        if (s == null && extra == null || xfXml.size() > 60_000) {
            xfIndex.put(key, 0);
            return 0;
        }
        int id = index(xfXml, xml(s, extra));
        xfIndex.put(key, id);
        return id;
    }

    private String xml(Element s, String extra) {
        int font = s == null ? 0 : index(fontXml, font(Uof.kid(s, "字体格式")));
        String fill = s == null ? null : fill(Uof.kid(s, "填充"));
        int fillId = fill == null ? 0 : index(fillXml, fill);
        int border = s == null ? 0 : index(borderXml, border(Uof.kid(s, "边框")));
        int numFmt = extra != null ? numFmts.computeIfAbsent(extra, k -> 164 + numFmts.size())
                : numFmt(Uof.kid(s, "数字格式"));
        String align = s == null ? null : alignment(Uof.kid(s, "对齐格式"));
        StringBuilder x = new StringBuilder("<xf numFmtId=\"").append(numFmt).append("\" fontId=\"").append(font)
                .append("\" fillId=\"").append(fillId).append("\" borderId=\"").append(border).append("\" xfId=\"0\"");
        x.append(" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"");
        if (align != null) {
            x.append(" applyAlignment=\"1\">").append(align).append("</xf>");
        } else {
            x.append("/>");
        }
        return x.toString();
    }

    private final Map<List<String>, Map<String, Integer>> indexes = new IdentityHashMap<>();

    private int index(List<String> list, String xml) {
        Map<String, Integer> known = indexes.computeIfAbsent(list, k -> new HashMap<>());
        Integer at = known.get(xml);
        if (at != null) {
            return at;
        }
        list.add(xml);
        known.put(xml, list.size() - 1);
        return list.size() - 1;
    }

    private String font(Element f) {
        Element font = Uof.kid(f, "字体");
        double size = Uof.number(Uof.attr(font, "字号"));
        String latin = Uof.font(fonts, Uof.attr(font, "西文字体引用"));
        String east = Uof.font(fonts, Uof.attr(font, "中文字体引用"));
        String family = east != null ? east : latin != null ? latin : "宋体";
        StringBuilder b = new StringBuilder("<font>");
        if (Uof.truth(Uof.kid(f, "粗体"))) {
            b.append("<b/>");
        }
        if (Uof.truth(Uof.kid(f, "斜体"))) {
            b.append("<i/>");
        }
        Element under = Uof.kid(f, "下划线");
        String ut = String.valueOf(Uof.attr(under, "类型"));
        if (under != null && !ut.equals("none") && !ut.equals("null") && !ut.isEmpty()) {
            b.append(ut.equals("double") ? "<u val=\"double\"/>" : "<u/>");
        }
        Element strike = Uof.kid(f, "删除线");
        String st = String.valueOf(Uof.attr(strike, "类型"));
        if (strike != null && !st.equals("none") && !st.equals("null") && !st.isEmpty()) {
            b.append("<strike/>");
        }
        b.append("<sz val=\"").append(size > 0 && size < 410 ? size : 11).append("\"/>");
        String colour = Uof.colour(Uof.attr(font, "颜色"));
        if (colour != null) {
            b.append("<color rgb=\"FF").append(colour).append("\"/>");
        }
        b.append("<name val=\"").append(Uof.esc(family)).append("\"/></font>");
        return b.toString();
    }

    private static String fill(Element f) {
        String c = Uof.colour(Uof.text(Uof.kid(f, "颜色")));
        if (c == null) {
            c = Uof.colour(Uof.attr(Uof.kid(f, "图案"), "前景色"));
        }
        return c == null ? null : "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF" + c
                + "\"/><bgColor indexed=\"64\"/></patternFill></fill>";
    }

    private String border(Element border) {
        StringBuilder b = new StringBuilder("<border>");
        for (String[] side : new String[][] {{"左", "left"}, {"右", "right"}, {"上", "top"}, {"下", "bottom"}}) {
            Element e = Uof.kid(border, side[0]);
            String type = String.valueOf(Uof.attr(e, "类型"));
            if (e == null || type.equals("none") || type.equals("null") || type.isEmpty()) {
                b.append('<').append(side[1]).append("/>");
                continue;
            }
            long w = units.twips(Uof.attr(e, "宽度"));
            String style = type.equals("double") ? "double" : type.equals("dash") ? "dashed" : type.equals("dotted")
                    ? "dotted" : UofUnits.known(w) && w > 40 ? "thick" : UofUnits.known(w) && w > 20 ? "medium" : "thin";
            String c = Uof.colour(Uof.attr(e, "颜色"));
            b.append('<').append(side[1]).append(" style=\"").append(style).append("\"><color rgb=\"FF")
                    .append(c == null ? "000000" : c).append("\"/></").append(side[1]).append('>');
        }
        return b.append("<diagonal/></border>").toString();
    }

    private int numFmt(Element f) {
        String code = Uof.attr(f, "格式码");
        if (code == null || code.isBlank() || code.equalsIgnoreCase("general") || code.equals("G/通用格式")) {
            return 0;
        }
        if (code.equals("@")) {
            return 49;
        }
        if (code.length() > 255) {
            return 0;
        }
        return numFmts.computeIfAbsent(code, k -> 164 + numFmts.size());
    }

    private static String alignment(Element a) {
        if (a == null) {
            return null;
        }
        StringBuilder b = new StringBuilder("<alignment");
        String h = switch (Uof.text(Uof.kid(a, "水平对齐方式")).strip()) {
            case "left" -> "left";
            case "center" -> "center";
            case "right" -> "right";
            case "justify", "justified" -> "justify";
            case "fill" -> "fill";
            case "distributed" -> "distributed";
            default -> null;
        };
        if (h != null) {
            b.append(" horizontal=\"").append(h).append('"');
        }
        String v = switch (Uof.text(Uof.kid(a, "垂直对齐方式")).strip()) {
            case "top" -> "top";
            case "center" -> "center";
            case "justify", "justified" -> "justify";
            case "distributed" -> "distributed";
            default -> null;
        };
        if (v != null) {
            b.append(" vertical=\"").append(v).append('"');
        }
        if (Uof.truth(Uof.kid(a, "自动换行"))) {
            b.append(" wrapText=\"1\"");
        }
        if (Uof.truth(Uof.kid(a, "缩小字体填充"))) {
            b.append(" shrinkToFit=\"1\"");
        }
        double rot = Uof.number(Uof.text(Uof.kid(a, "文字旋转角度")));
        if (rot > 0 && rot <= 90) {
            b.append(" textRotation=\"").append(Math.round(rot)).append('"');
        } else if (rot < 0 && rot >= -90) {
            b.append(" textRotation=\"").append(90 + Math.round(-rot)).append('"');
        }
        if ("vertical".equals(Uof.text(Uof.kid(a, "文字方向")).strip())) {
            b.append(" textRotation=\"255\"");
        }
        double indent = Uof.number(Uof.attr(a, "缩进"));
        if (indent > 0 && indent < 250) {
            b.append(" indent=\"").append(Math.round(indent)).append('"');
        }
        String s = b.append("/>").toString();
        return s.equals("<alignment/>") ? null : s;
    }

    private String styles() {
        StringBuilder b = new StringBuilder(UofParts.XML);
        b.append("<styleSheet xmlns=\"").append(MAIN).append("\">");
        if (!numFmts.isEmpty()) {
            b.append("<numFmts count=\"").append(numFmts.size()).append("\">");
            for (Map.Entry<String, Integer> e : numFmts.entrySet()) {
                b.append("<numFmt numFmtId=\"").append(e.getValue()).append("\" formatCode=\"")
                        .append(Uof.esc(e.getKey())).append("\"/>");
            }
            b.append("</numFmts>");
        }
        b.append("<fonts count=\"").append(fontXml.size()).append("\">").append(String.join("", fontXml))
                .append("</fonts><fills count=\"").append(fillXml.size()).append("\">").append(String.join("", fillXml))
                .append("</fills><borders count=\"").append(borderXml.size()).append("\">")
                .append(String.join("", borderXml)).append("</borders>")
                .append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")
                .append("<cellXfs count=\"").append(xfXml.size()).append("\">").append(String.join("", xfXml))
                .append("</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/>"
                        + "</cellStyles></styleSheet>");
        return b.toString();
    }
}
