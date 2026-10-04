package stirling.software.officeconvert.topdf.xlsx;

import java.awt.Color;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.poi.ss.usermodel.BorderFormatting;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.DifferentialStyleProvider;
import org.apache.poi.ss.usermodel.FontFormatting;
import org.apache.poi.ss.usermodel.PatternFormatting;
import org.apache.poi.ss.usermodel.TableStyle;
import org.apache.poi.ss.usermodel.TableStyleType;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFDxfStyleProvider;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCfRule;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCfvo;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTConditionalFormatting;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTDxf;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorksheet;
import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.io.ActiveContent;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.Relationship;

// Formatting Excel draws on top of the cell styles: table styles (under direct formatting) and conditional formats
// whose rules need only cached values and constants (over it); formula rules are never evaluated
final class Overlays {

    record Delta(Color fill, Color font, boolean bold, boolean italic, BorderLine top, BorderLine bottom,
            BorderLine left, BorderLine right, String format) {

        static final Delta EMPTY = new Delta(null, null, false, false, null, null, null, null, null);

        Delta then(Delta o) {
            return new Delta(o.fill != null ? o.fill : fill, o.font != null ? o.font : font, bold || o.bold,
                    italic || o.italic, o.top != null ? o.top : top, o.bottom != null ? o.bottom : bottom,
                    o.left != null ? o.left : left, o.right != null ? o.right : right,
                    o.format != null ? o.format : format);
        }
    }

    record Bar(double fraction, Color color) {}

    private static final int MAX_CELLS = 500_000;

    private final Book book;

    private final Map<Long, Delta> under = new HashMap<>();

    private final Map<Long, Delta> over = new HashMap<>();

    private final Map<Long, Bar> bars = new HashMap<>();

    private long budget = 5_000_000;

    private Overlays(Book book) {
        this.book = book;
    }

    static Overlays read(Book book, String sheetPart, CTWorksheet ws, Grid grid, RenderJob job)
            throws InterruptedIOException {
        Overlays o = new Overlays(book);
        try {
            o.tables(sheetPart, job);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (java.io.IOException | RuntimeException e) {
            job.warn("A table style could not be read");
        }
        try {
            o.conditional(ws, grid, job);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (RuntimeException e) {
            job.warn("A conditional format could not be read");
        }
        return o;
    }

    boolean isEmpty() {
        return under.isEmpty() && over.isEmpty() && bars.isEmpty();
    }

    Map<Long, Delta> under() {
        return under;
    }

    Map<Long, Delta> over() {
        return over;
    }

    boolean hasBars() {
        return !bars.isEmpty();
    }

    Bar bar(int row, int col) {
        return bars.get(key(row, col));
    }

    static long key(int row, int col) {
        return (long) row << 16 | col;
    }

    private void tables(String sheetPart, RenderJob job) throws java.io.IOException {
        OfficeZip zip = job.zip();
        StylesTable styles = book.workbook().styles;
        for (Relationship r : zip.relationships(sheetPart).ofType("table")) {
            if (!ActiveContent.mayFollow(r) || under.size() > MAX_CELLS) {
                continue;
            }
            Element t;
            try {
                t = zip.xml(r).getDocumentElement();
            } catch (java.io.IOException e) {
                continue;
            }
            Element info = Dml.child(t, "tableStyleInfo");
            String name = Dml.attr(info, "name");
            TableStyle style = null;
            if (name != null && styles != null) {
                try {
                    style = styles.getTableStyle(name);
                } catch (RuntimeException e) {
                    style = null;
                }
            }
            String ref = Dml.attr(t, "ref");
            if (style == null || ref == null) {
                continue;
            }
            CellRangeAddress range = CellRangeAddress.valueOf(ref);
            int header = (int) Dml.number(t, "headerRowCount", 1);
            int totals = (int) Dml.number(t, "totalsRowCount", 0);
            table(style, range, header, totals, Dml.flag(info, "showFirstColumn"), Dml.flag(info, "showLastColumn"),
                    Dml.flag(info, "showRowStripes"), Dml.flag(info, "showColumnStripes"), job);
        }
    }

    private void table(TableStyle style, CellRangeAddress range, int header, int totals, boolean firstCol,
            boolean lastCol, boolean rowStripes, boolean colStripes, RenderJob job) throws InterruptedIOException {
        int r0 = range.getFirstRow();
        int r1 = range.getLastRow();
        int c0 = range.getFirstColumn();
        int c1 = range.getLastColumn();
        if ((long) (r1 - r0 + 1) * (c1 - c0 + 1) > MAX_CELLS) {
            return;
        }
        int dataFirst = r0 + header;
        int dataLast = r1 - totals;
        Map<Long, Delta> cells = new HashMap<>();
        region(cells, style.getStyle(TableStyleType.wholeTable), r0, r1, c0, c1);
        if (colStripes) {
            stripes(cells, style, TableStyleType.firstColumnStripe, TableStyleType.secondColumnStripe, c0, c1, false,
                    dataFirst, dataLast);
        }
        if (rowStripes) {
            stripes(cells, style, TableStyleType.firstRowStripe, TableStyleType.secondRowStripe, dataFirst, dataLast,
                    true, c0, c1);
        }
        if (lastCol) {
            region(cells, style.getStyle(TableStyleType.lastColumn), r0, r1, c1, c1);
        }
        if (firstCol) {
            region(cells, style.getStyle(TableStyleType.firstColumn), r0, r1, c0, c0);
        }
        if (header > 0) {
            region(cells, style.getStyle(TableStyleType.headerRow), r0, dataFirst - 1, c0, c1);
        }
        if (totals > 0) {
            region(cells, style.getStyle(TableStyleType.totalRow), dataLast + 1, r1, c0, c1);
        }
        if (header > 0 && firstCol) {
            region(cells, style.getStyle(TableStyleType.firstHeaderCell), r0, r0, c0, c0);
        }
        if (header > 0 && lastCol) {
            region(cells, style.getStyle(TableStyleType.lastHeaderCell), r0, r0, c1, c1);
        }
        if (totals > 0 && firstCol) {
            region(cells, style.getStyle(TableStyleType.firstTotalCell), r1, r1, c0, c0);
        }
        if (totals > 0 && lastCol) {
            region(cells, style.getStyle(TableStyleType.lastTotalCell), r1, r1, c1, c1);
        }
        job.checkpoint();
        for (Map.Entry<Long, Delta> e : cells.entrySet()) {
            under.merge(e.getKey(), e.getValue(), Delta::then);
        }
    }

    private void stripes(Map<Long, Delta> cells, TableStyle style, TableStyleType first, TableStyleType second,
            int from, int to, boolean rows, int a0, int a1) {
        DifferentialStyleProvider s1 = style.getStyle(first);
        DifferentialStyleProvider s2 = style.getStyle(second);
        int n1 = Math.max(1, s1 == null ? 1 : s1.getStripeSize());
        int n2 = Math.max(1, s2 == null ? 1 : s2.getStripeSize());
        int at = from;
        boolean odd = true;
        while (at <= to) {
            int size = odd ? n1 : n2;
            int end = Math.min(to, at + size - 1);
            DifferentialStyleProvider s = odd ? s1 : s2;
            if (rows) {
                region(cells, s, at, end, a0, a1);
            } else {
                region(cells, s, a0, a1, at, end);
            }
            at = end + 1;
            odd = !odd;
        }
    }

    private void region(Map<Long, Delta> cells, DifferentialStyleProvider s, int r0, int r1, int c0, int c1) {
        if (s == null || r1 < r0 || c1 < c0) {
            return;
        }
        Color fill = fill(s.getPatternFormatting());
        FontFormatting f = s.getFontFormatting();
        Color font = f == null ? null : color(f.getFontColor());
        boolean bold = f != null && f.isBold();
        boolean italic = f != null && f.isItalic();
        BorderFormatting b = s.getBorderFormatting();
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                BorderLine top = null;
                BorderLine bottom = null;
                BorderLine left = null;
                BorderLine right = null;
                if (b != null) {
                    top = r == r0 ? line(b.getBorderTop(), b.getTopBorderColorColor())
                            : line(b.getBorderHorizontal(), b.getHorizontalBorderColorColor());
                    bottom = r == r1 ? line(b.getBorderBottom(), b.getBottomBorderColorColor())
                            : line(b.getBorderHorizontal(), b.getHorizontalBorderColorColor());
                    left = c == c0 ? line(b.getBorderLeft(), b.getLeftBorderColorColor())
                            : line(b.getBorderVertical(), b.getVerticalBorderColorColor());
                    right = c == c1 ? line(b.getBorderRight(), b.getRightBorderColorColor())
                            : line(b.getBorderVertical(), b.getVerticalBorderColorColor());
                }
                Delta d = new Delta(fill, font, bold, italic, top, bottom, left, right, null);
                cells.merge(key(r, c), d, Delta::then);
            }
        }
    }

    private BorderLine line(BorderStyle style, org.apache.poi.ss.usermodel.Color color) {
        if (style == null || style == BorderStyle.NONE) {
            return null;
        }
        return new BorderLine(style, color == null ? Color.BLACK : color(color));
    }

    private Color fill(PatternFormatting p) {
        if (p == null) {
            return null;
        }
        Color c = color(p.getFillBackgroundColorColor());
        return c != null ? c : color(p.getFillForegroundColorColor());
    }

    private Color color(org.apache.poi.ss.usermodel.Color c) {
        if (!(c instanceof XSSFColor x)) {
            return null;
        }
        return book.colors().resolve(x, null);
    }

    private void conditional(CTWorksheet ws, Grid grid, RenderJob job) throws InterruptedIOException {
        if (ws == null || ws.sizeOfConditionalFormattingArray() == 0) {
            return;
        }
        List<Object[]> rules = new ArrayList<>();
        for (CTConditionalFormatting cf : ws.getConditionalFormattingArray()) {
            List<CellRangeAddress> ranges = new ArrayList<>();
            for (Object o : cf.getSqref() == null ? List.of() : cf.getSqref()) {
                for (String part : o.toString().split("\\s+")) {
                    try {
                        ranges.add(CellRangeAddress.valueOf(part));
                    } catch (RuntimeException e) {
                        continue;
                    }
                }
            }
            for (CTCfRule rule : cf.getCfRuleArray()) {
                rules.add(new Object[] {rule, ranges});
            }
            if (rules.size() > 1000) {
                break;
            }
        }
        rules.sort(Comparator.comparingInt((Object[] x) -> ((CTCfRule) x[0]).getPriority()).reversed());
        for (Object[] x : rules) {
            job.checkpoint();
            @SuppressWarnings("unchecked")
            List<CellRangeAddress> ranges = (List<CellRangeAddress>) x[1];
            rule((CTCfRule) x[0], ranges, grid);
        }
    }

    private record Value(int row, int col, CellEntry entry) {

        boolean numeric() {
            return entry.text() != null && entry.text().numeric();
        }

        double number() {
            return entry.text().number();
        }

        String text() {
            return entry.text() == null ? "" : entry.text().plain();
        }
    }

    private List<Value> values(List<CellRangeAddress> ranges, Grid grid) {
        List<Value> out = new ArrayList<>();
        for (CellRangeAddress r : ranges) {
            for (Grid.RowInfo row : grid.rows(r.getFirstRow(), r.getLastRow()).values()) {
                for (CellEntry e : row.cells(r.getFirstColumn(), r.getLastColumn())) {
                    out.add(new Value(row.index, e.col(), e));
                    if (out.size() > MAX_CELLS) {
                        return out;
                    }
                }
            }
        }
        return out;
    }

    private void rule(CTCfRule rule, List<CellRangeAddress> ranges, Grid grid) {
        String type = rule.getType() == null ? "" : rule.getType().toString();
        if (budget <= 0) {
            return;
        }
        List<Value> values = values(ranges, grid);
        budget -= values.size();
        if (values.isEmpty()) {
            return;
        }
        switch (type) {
            case "colorScale" -> colorScale(rule, values);
            case "dataBar" -> dataBar(rule, values);
            default -> {
                Delta d = dxf(rule);
                if (d == null) {
                    return;
                }
                for (Value v : matches(rule, type, values)) {
                    over.merge(key(v.row, v.col), d, Delta::then);
                }
            }
        }
    }

    private List<Value> matches(CTCfRule rule, String type, List<Value> values) {
        List<Value> out = new ArrayList<>();
        switch (type) {
            case "cellIs" -> {
                String[] f = rule.getFormulaArray();
                String op = rule.isSetOperator() ? rule.getOperator().toString() : "equal";
                Object a = f.length > 0 ? constant(f[0]) : null;
                Object b = f.length > 1 ? constant(f[1]) : null;
                if (a == null || (op.endsWith("etween") && b == null)) {
                    return out;
                }
                for (Value v : values) {
                    if (cellIs(v, op, a, b)) {
                        out.add(v);
                    }
                }
            }
            case "containsText", "notContainsText", "beginsWith", "endsWith" -> {
                String text = rule.getText();
                if (text == null) {
                    return out;
                }
                String t = text.toLowerCase(Locale.ROOT);
                for (Value v : values) {
                    String s = v.text().toLowerCase(Locale.ROOT);
                    boolean hit = switch (type) {
                        case "containsText" -> s.contains(t);
                        case "notContainsText" -> !s.contains(t);
                        case "beginsWith" -> s.startsWith(t);
                        default -> s.endsWith(t);
                    };
                    if (hit) {
                        out.add(v);
                    }
                }
            }
            case "containsBlanks", "notContainsBlanks" -> {
                for (Value v : values) {
                    if (v.text().isBlank() == type.equals("containsBlanks")) {
                        out.add(v);
                    }
                }
            }
            case "top10" -> {
                double[] nums = values.stream().filter(Value::numeric).mapToDouble(Value::number).sorted().toArray();
                if (nums.length == 0) {
                    return out;
                }
                long rank = rule.isSetRank() ? rule.getRank() : 10;
                int n = (int) Math.max(1, rule.getPercent() ? Math.floor(nums.length * rank / 100.0) : rank);
                n = Math.min(n, nums.length);
                double cut = rule.getBottom() ? nums[n - 1] : nums[nums.length - n];
                for (Value v : values) {
                    if (v.numeric() && (rule.getBottom() ? v.number() <= cut : v.number() >= cut)) {
                        out.add(v);
                    }
                }
            }
            case "aboveAverage" -> {
                double[] nums = values.stream().filter(Value::numeric).mapToDouble(Value::number).toArray();
                if (nums.length == 0) {
                    return out;
                }
                double mean = Arrays.stream(nums).average().orElse(0);
                double sd = Math.sqrt(Arrays.stream(nums).map(x -> (x - mean) * (x - mean)).sum()
                        / Math.max(1, nums.length - 1));
                boolean above = !rule.isSetAboveAverage() || rule.getAboveAverage();
                double limit = mean + (above ? 1 : -1) * (rule.isSetStdDev() ? rule.getStdDev() * sd : 0);
                for (Value v : values) {
                    if (!v.numeric()) {
                        continue;
                    }
                    double x = v.number();
                    boolean hit = above ? x > limit || rule.getEqualAverage() && x == limit
                            : x < limit || rule.getEqualAverage() && x == limit;
                    if (hit) {
                        out.add(v);
                    }
                }
            }
            case "duplicateValues", "uniqueValues" -> {
                Map<String, Integer> counts = new HashMap<>();
                for (Value v : values) {
                    counts.merge(identity(v), 1, Integer::sum);
                }
                for (Value v : values) {
                    if (!v.text().isEmpty() && (counts.get(identity(v)) > 1) == type.equals("duplicateValues")) {
                        out.add(v);
                    }
                }
            }
            default -> {
            }
        }
        return out;
    }

    private static String identity(Value v) {
        return v.numeric() ? "n" + v.number() : "t" + v.text().toLowerCase(Locale.ROOT);
    }

    // Only a number or a quoted string counts; a reference or a function would need evaluation and is skipped
    private static Object constant(String formula) {
        if (formula == null) {
            return null;
        }
        String f = formula.strip();
        if (f.length() >= 2 && f.startsWith("\"") && f.endsWith("\"")) {
            return f.substring(1, f.length() - 1).replace("\"\"", "\"");
        }
        try {
            return Double.parseDouble(f);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean cellIs(Value v, String op, Object a, Object b) {
        int ca;
        int cb = 0;
        if (a instanceof Double x) {
            if (!v.numeric()) {
                return false;
            }
            ca = Double.compare(v.number(), x);
            if (b instanceof Double y) {
                cb = Double.compare(v.number(), y);
            }
        } else {
            if (v.numeric()) {
                return false;
            }
            ca = v.text().compareToIgnoreCase((String) a);
            if (b instanceof String y) {
                cb = v.text().compareToIgnoreCase(y);
            }
        }
        return switch (op) {
            case "equal" -> ca == 0;
            case "notEqual" -> ca != 0;
            case "greaterThan" -> ca > 0;
            case "greaterThanOrEqual" -> ca >= 0;
            case "lessThan" -> ca < 0;
            case "lessThanOrEqual" -> ca <= 0;
            case "between" -> b != null && (ca >= 0 && cb <= 0 || ca <= 0 && cb >= 0);
            case "notBetween" -> b != null && !(ca >= 0 && cb <= 0 || ca <= 0 && cb >= 0);
            default -> false;
        };
    }

    private Delta dxf(CTCfRule rule) {
        StylesTable styles = book.workbook().styles;
        if (!rule.isSetDxfId() || styles == null || rule.getDxfId() < 0 || rule.getDxfId() >= styles._getDXfsSize()) {
            return null;
        }
        CTDxf dxf = styles.getDxfAt((int) rule.getDxfId());
        Map<Long, Delta> one = new HashMap<>();
        region(one, new XSSFDxfStyleProvider(dxf, 1, styles.getIndexedColors()), 0, 0, 0, 0);
        Delta d = one.getOrDefault(0L, Delta.EMPTY);
        String format = dxf.isSetNumFmt() ? dxf.getNumFmt().getFormatCode() : null;
        return format == null || format.isEmpty() ? d : d.then(new Delta(null, null, false, false, null, null, null,
                null, format));
    }

    private double[] scale(CTCfvo[] cfvo, List<Value> values) {
        double[] nums = values.stream().filter(Value::numeric).mapToDouble(Value::number).sorted().toArray();
        if (nums.length == 0) {
            return null;
        }
        double[] out = new double[cfvo.length];
        for (int i = 0; i < cfvo.length; i++) {
            String type = cfvo[i].getType() == null ? "num" : cfvo[i].getType().toString();
            Object c = cfvo[i].getVal() == null ? null : constant(cfvo[i].getVal());
            double v = c instanceof Double d ? d : 0;
            out[i] = switch (type) {
                case "min" -> nums[0];
                case "max" -> nums[nums.length - 1];
                case "num" -> {
                    if (!(c instanceof Double)) {
                        yield Double.NaN;
                    }
                    yield v;
                }
                case "percent" -> nums[0] + (nums[nums.length - 1] - nums[0]) * v / 100;
                case "percentile" -> percentile(nums, v);
                default -> Double.NaN;
            };
            if (Double.isNaN(out[i])) {
                return null;
            }
        }
        return out;
    }

    private static double percentile(double[] sorted, double p) {
        double pos = Math.max(0, Math.min(1, p / 100)) * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(sorted.length - 1, lo + 1);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
    }

    private void colorScale(CTCfRule rule, List<Value> values) {
        if (!rule.isSetColorScale()) {
            return;
        }
        CTCfvo[] cfvo = rule.getColorScale().getCfvoArray();
        var ctColors = rule.getColorScale().getColorArray();
        if (cfvo.length < 2 || ctColors.length != cfvo.length) {
            return;
        }
        double[] at = scale(cfvo, values);
        if (at == null) {
            return;
        }
        Color[] colors = new Color[ctColors.length];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = book.colors().resolve(ctColors[i], Color.WHITE);
        }
        for (Value v : values) {
            if (!v.numeric()) {
                continue;
            }
            double x = v.number();
            Color c = colors[colors.length - 1];
            if (x <= at[0]) {
                c = colors[0];
            } else {
                for (int i = 0; i + 1 < at.length; i++) {
                    if (x <= at[i + 1]) {
                        double t = at[i + 1] == at[i] ? 1 : (x - at[i]) / (at[i + 1] - at[i]);
                        c = mix(colors[i], colors[i + 1], t);
                        break;
                    }
                }
            }
            over.merge(key(v.row, v.col), new Delta(c, null, false, false, null, null, null, null, null), Delta::then);
        }
    }

    private static Color mix(Color a, Color b, double t) {
        return new Color((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    private void dataBar(CTCfRule rule, List<Value> values) {
        if (!rule.isSetDataBar()) {
            return;
        }
        CTCfvo[] cfvo = rule.getDataBar().getCfvoArray();
        if (cfvo.length < 2) {
            return;
        }
        double[] at = scale(cfvo, values);
        if (at == null) {
            return;
        }
        Color color = book.colors().resolve(rule.getDataBar().getColor(), new Color(0x638EC6));
        double min = rule.getDataBar().isSetMinLength() ? rule.getDataBar().getMinLength() / 100.0 : 0.1;
        double max = rule.getDataBar().isSetMaxLength() ? rule.getDataBar().getMaxLength() / 100.0 : 0.9;
        for (Value v : values) {
            if (!v.numeric()) {
                continue;
            }
            double t = at[1] == at[0] ? 1 : (v.number() - at[0]) / (at[1] - at[0]);
            t = Math.max(0, Math.min(1, t));
            bars.put(key(v.row, v.col), new Bar(min + (max - min) * t, color));
        }
    }
}
