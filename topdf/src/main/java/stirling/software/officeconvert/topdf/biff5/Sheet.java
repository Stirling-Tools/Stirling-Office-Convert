package stirling.software.officeconvert.topdf.biff5;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import stirling.software.officeconvert.topdf.xls.Parts;
import stirling.software.officeconvert.topdf.xls.Xml;

/** One BIFF5 worksheet substream as worksheet XML. Cells are collected per row (BIFF5 may store a row's cells after
 * later rows' ROW records) and written in order; formulas keep their cached results only. */
final class Sheet {

    private static final int MAX_ROW = 65_535;

    private static final int MAX_COL = 255;

    private static final int MAX_CELLS = 4_000_000;

    private final Stream s;

    private final Text text;

    private final Styles styles;

    private final TreeMap<Integer, String> rows = new TreeMap<>();

    private final TreeMap<Integer, TreeMap<Integer, String>> cells = new TreeMap<>();

    private final StringBuilder cols = new StringBuilder();

    private final List<String> rowBreaks = new ArrayList<>();

    private final List<String> colBreaks = new ArrayList<>();

    private String view = "";

    private String format = "";

    private int baseWidth = -1;

    private double standardWidth = -1;

    private int defaultHeight = 255;

    private boolean fitToPage;

    private String header = "";

    private String footer = "";

    private final double[] margins = {0.75, 0.75, 1, 1};

    private String setup = "";

    private double[] headerFooterMargins = {0.5, 0.5};

    private boolean hCenter;

    private boolean vCenter;

    private boolean gridLines;

    private boolean headings;

    private int[] pendingString;

    private int count;

    boolean objects;

    boolean truncated;

    Sheet(Stream s, Text text, Styles styles) {
        this.s = s;
        this.text = text;
        this.styles = styles;
    }

    void read() throws IOException {
        int depth = 0;
        while (s.next()) {
            int type = s.type();
            if (type == 0x0809) {
                depth++;
                objects = true;
                continue;
            }
            if (type == 0x000A) {
                if (depth-- == 0) {
                    return;
                }
                continue;
            }
            if (depth > 0) {
                continue;
            }
            record(type);
        }
    }

    private void record(int type) {
        switch (type) {
            case 0x0208 -> row();
            case 0x007D -> colInfo();
            case 0x0055 -> baseWidth = s.u16(0);
            case 0x0099 -> standardWidth = s.u16(0) / 256.0;
            case 0x0225 -> defaultHeight = s.u16(2) & 0x7FFF;
            case 0x0201 -> put(s.u16(0), s.u16(2), s.u16(4), "", null);
            case 0x00BE -> mulBlank();
            case 0x0203 -> number(s.u16(0), s.u16(2), s.u16(4), s.f64(6));
            case 0x027E -> number(s.u16(0), s.u16(2), s.u16(4), rk(s.i32(6)));
            case 0x00BD -> mulRk();
            case 0x0204 -> label(false);
            case 0x00D6 -> label(true);
            case 0x0205 -> boolErr(s.u16(0), s.u16(2), s.u16(4), s.u8(6), s.u8(7) != 0);
            case 0x0006, 0x0406 -> formula();
            case 0x0207 -> cachedString();
            case 0x0014 -> header = text.read(s, 1, s.u8(0));
            case 0x0015 -> footer = text.read(s, 1, s.u8(0));
            case 0x0026, 0x0027, 0x0028, 0x0029 -> margin(type - 0x0026, s.f64(0));
            case 0x00A1 -> setup = setup();
            case 0x0083 -> hCenter = s.u16(0) != 0;
            case 0x0084 -> vCenter = s.u16(0) != 0;
            case 0x002B -> gridLines = s.u16(0) != 0;
            case 0x002A -> headings = s.u16(0) != 0;
            case 0x0081 -> fitToPage = (s.u16(0) & 0x0100) != 0;
            case 0x001B -> breaks(rowBreaks);
            case 0x001A -> breaks(colBreaks);
            case 0x023E -> view = view(s.u16(0));
            case 0x005D, 0x00E9, 0x007F -> objects = true;
            default -> {
            }
        }
        if (type != 0x0006 && type != 0x0406 && type != 0x0207) {
            pendingString = null;
        }
    }

    private void margin(int which, double inches) {
        if (Double.isFinite(inches) && inches >= 0 && inches < 50) {
            margins[which] = inches;
        }
    }

    private static double rk(int v) {
        double d = (v & 0x02) != 0 ? (double) (v >> 2) : Double.longBitsToDouble(((long) (v & 0xFFFFFFFC)) << 32);
        return (v & 0x01) != 0 ? d / 100 : d;
    }

    private void row() {
        int r = s.u16(0);
        int height = s.u16(6);
        int flags = s.u16(12);
        int xf = s.u16(14) & 0x0FFF;
        if (r > MAX_ROW || rows.size() > MAX_ROW) {
            return;
        }
        StringBuilder b = new StringBuilder("<row r=\"").append(r + 1).append('"');
        if ((flags & 0x0080) != 0) {
            b.append(" s=\"").append(styles.cellXf(xf)).append("\" customFormat=\"1\"");
        }
        int h = height & 0x7FFF;
        if ((flags & 0x0020) != 0) {
            b.append(" hidden=\"1\"");
        }
        if (h > 0 && h < 8192) {
            b.append(" ht=\"").append(h / 20.0).append('"');
            if ((flags & 0x0040) != 0) {
                b.append(" customHeight=\"1\"");
            }
        }
        int level = flags & 0x07;
        if (level > 0) {
            b.append(" outlineLevel=\"").append(level).append('"');
        }
        rows.put(r, b.toString());
    }

    private void colInfo() {
        int first = s.u16(0);
        int last = Math.min(s.u16(2), MAX_COL);
        int width = s.u16(4);
        int xf = s.u16(6);
        int flags = s.u16(8);
        if (first > last || cols.length() > 1 << 20) {
            return;
        }
        cols.append("<col min=\"").append(first + 1).append("\" max=\"").append(last + 1).append("\" width=\"")
                .append(width / 256.0).append("\" customWidth=\"1\"");
        if (xf > 0) {
            cols.append(" style=\"").append(styles.cellXf(xf)).append('"');
        }
        if ((flags & 0x0001) != 0) {
            cols.append(" hidden=\"1\"");
        }
        cols.append("/>");
    }

    private void mulBlank() {
        int r = s.u16(0);
        int c = s.u16(2);
        int n = (s.size() - 6) / 2;
        for (int i = 0; i < n; i++) {
            put(r, c + i, s.u16(4 + 2 * i), "", null);
        }
    }

    private void mulRk() {
        int r = s.u16(0);
        int c = s.u16(2);
        int n = (s.size() - 6) / 6;
        for (int i = 0; i < n; i++) {
            number(r, c + i, s.u16(4 + 6 * i), rk(s.i32(6 + 6 * i)));
        }
    }

    private void number(int r, int c, int xf, double v) {
        if (Double.isFinite(v)) {
            put(r, c, xf, "", "<v>" + number(v) + "</v>");
        }
    }

    static String number(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    private void label(boolean rich) {
        int r = s.u16(0);
        int c = s.u16(2);
        int xf = s.u16(4);
        int n = s.u16(6);
        String str = text.read(s, 8, n);
        String body = "<t xml:space=\"preserve\">" + Xml.text(str) + "</t>";
        if (rich) {
            int at = 8 + n;
            int runs = s.u8(at);
            body = runs(str, runs, at + 1, body);
        }
        put(r, c, xf, " t=\"inlineStr\"", "<is>" + body + "</is>");
    }

    private String runs(String str, int runs, int at, String plain) {
        if (runs == 0) {
            return plain;
        }
        StringBuilder b = new StringBuilder();
        int[] start = new int[runs];
        int[] font = new int[runs];
        for (int i = 0; i < runs; i++) {
            start[i] = s.u8(at + 2 * i);
            font[i] = s.u8(at + 2 * i + 1);
        }
        if (start[0] > 0) {
            b.append("<r><t xml:space=\"preserve\">").append(Xml.text(str.substring(0, Math.min(start[0], str.length()))))
                    .append("</t></r>");
        }
        for (int i = 0; i < runs; i++) {
            int from = Math.min(start[i], str.length());
            int to = i + 1 < runs ? Math.min(Math.max(start[i + 1], from), str.length()) : str.length();
            if (to > from) {
                b.append("<r>").append(styles.runProperties(font[i])).append("<t xml:space=\"preserve\">")
                        .append(Xml.text(str.substring(from, to))).append("</t></r>");
            }
        }
        return b.isEmpty() ? plain : b.toString();
    }

    private void boolErr(int r, int c, int xf, int value, boolean error) {
        if (error) {
            put(r, c, xf, " t=\"e\"", "<v>" + error(value) + "</v>");
        } else {
            put(r, c, xf, " t=\"b\"", "<v>" + (value != 0 ? 1 : 0) + "</v>");
        }
    }

    private static String error(int code) {
        return switch (code) {
            case 0x00 -> "#NULL!";
            case 0x07 -> "#DIV/0!";
            case 0x0F -> "#VALUE!";
            case 0x17 -> "#REF!";
            case 0x1D -> "#NAME?";
            case 0x24 -> "#NUM!";
            default -> "#N/A";
        };
    }

    private void formula() {
        int r = s.u16(0);
        int c = s.u16(2);
        int xf = s.u16(4);
        if (s.u16(12) == 0xFFFF) {
            int kind = s.u8(6);
            switch (kind) {
                case 0 -> pendingString = new int[] {r, c, xf};
                case 1 -> boolErr(r, c, xf, s.u8(8), false);
                case 2 -> boolErr(r, c, xf, s.u8(8), true);
                default -> put(r, c, xf, "", null);
            }
            return;
        }
        number(r, c, xf, s.f64(6));
    }

    private void cachedString() {
        if (pendingString == null) {
            return;
        }
        String str = text.read(s, 2, s.u16(0));
        put(pendingString[0], pendingString[1], pendingString[2], " t=\"str\"", "<v>" + Xml.text(str) + "</v>");
        pendingString = null;
    }

    private void put(int r, int c, int xf, String type, String body) {
        if (r > MAX_ROW || c > MAX_COL) {
            return;
        }
        if (count++ > MAX_CELLS) {
            truncated = true;
            return;
        }
        int x = styles.cellXf(xf);
        if (body == null && x == 0) {
            return;
        }
        String ref = col(c) + (r + 1);
        String cell = "<c r=\"" + ref + "\"" + (x == 0 ? "" : " s=\"" + x + "\"") + type
                + (body == null || body.isEmpty() ? "/>" : ">" + body + "</c>");
        cells.computeIfAbsent(r, k -> new TreeMap<>()).put(c, cell);
    }

    static long cells(Stream s) throws InterruptedIOException {
        long most = 0;
        long sheet = 0;
        while (s.next()) {
            switch (s.type()) {
                case 0x0809 -> sheet = 0;
                case 0x00BE -> sheet += Math.max(0, (s.size() - 6) / 2);
                case 0x00BD -> sheet += Math.max(0, (s.size() - 6) / 6);
                case 0x0201, 0x0203, 0x027E, 0x0204, 0x00D6, 0x0205, 0x0006, 0x0406 -> sheet++;
                default -> {
                }
            }
            most = Math.max(most, Math.min(sheet, MAX_CELLS));
        }
        return most;
    }

    static String col(int c) {
        StringBuilder b = new StringBuilder(2);
        int n = c + 1;
        while (n > 0) {
            int m = (n - 1) % 26;
            b.insert(0, (char) ('A' + m));
            n = (n - 1) / 26;
        }
        return b.toString();
    }

    private String setup() {
        int paper = s.u16(0);
        int scale = s.u16(2);
        int first = s.u16(4);
        int fitWidth = s.u16(6);
        int fitHeight = s.u16(8);
        int f = s.u16(10);
        double headerMargin = s.f64(16);
        double footerMargin = s.f64(24);
        StringBuilder b = new StringBuilder("<pageSetup");
        boolean valid = (f & 0x0004) == 0;
        if (valid && paper > 0 && paper < 256) {
            b.append(" paperSize=\"").append(paper).append('"');
        }
        if (valid && scale >= 10 && scale <= 400) {
            b.append(" scale=\"").append(scale).append('"');
        }
        if ((f & 0x0080) != 0) {
            b.append(" firstPageNumber=\"").append((short) first).append("\" useFirstPageNumber=\"1\"");
        }
        b.append(" fitToWidth=\"").append(fitWidth).append("\" fitToHeight=\"").append(fitHeight).append('"');
        if ((f & 0x0001) != 0) {
            b.append(" pageOrder=\"overThenDown\"");
        }
        if (valid && (f & 0x0040) == 0) {
            b.append(" orientation=\"").append((f & 0x0002) != 0 ? "portrait" : "landscape").append('"');
        }
        if ((f & 0x0008) != 0) {
            b.append(" blackAndWhite=\"1\"");
        }
        b.append("/>");
        headerFooterMargins = new double[] {headerMargin, footerMargin};
        return b.toString();
    }

    private void breaks(List<String> list) {
        int n = s.u16(0);
        for (int i = 0; i < n && i < 1026; i++) {
            int at = s.u16(2 + 2 * i);
            if (at > 0) {
                list.add("<brk id=\"" + at + "\" max=\"" + (list == rowBreaks ? 255 : 65535) + "\" man=\"1\"/>");
            }
        }
    }

    private static String view(int f) {
        StringBuilder b = new StringBuilder();
        if ((f & 0x0002) == 0) {
            b.append(" showGridLines=\"0\"");
        }
        if ((f & 0x0010) == 0) {
            b.append(" showZeros=\"0\"");
        }
        if ((f & 0x0040) != 0) {
            b.append(" rightToLeft=\"1\"");
        }
        if ((f & 0x0001) != 0) {
            b.append(" showFormulas=\"1\"");
        }
        return b.toString();
    }

    void write(Parts.Part out) throws IOException {
        out.write(Xml.HEAD + "<worksheet xmlns=\"" + Xml.MAIN + "\" xmlns:r=\"" + Xml.REL + "\">");
        if (fitToPage) {
            out.write("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>");
        }
        out.write("<sheetViews><sheetView workbookViewId=\"0\"" + view + "/></sheetViews>");
        StringBuilder f = new StringBuilder("<sheetFormatPr");
        if (baseWidth > 0 && baseWidth < 256) {
            f.append(" baseColWidth=\"").append(baseWidth).append('"');
        }
        if (standardWidth > 0 && standardWidth < 256) {
            f.append(" defaultColWidth=\"").append(standardWidth).append('"');
        }
        f.append(" defaultRowHeight=\"").append((defaultHeight > 0 && defaultHeight < 8192 ? defaultHeight : 255) / 20.0)
                .append("\"/>");
        out.write(f.toString());
        if (!cols.isEmpty()) {
            out.write("<cols>" + cols + "</cols>");
        }
        out.write("<sheetData>");
        TreeMap<Integer, String> all = new TreeMap<>(rows);
        for (Integer r : cells.keySet()) {
            all.putIfAbsent(r, "<row r=\"" + (r + 1) + "\"");
        }
        for (var e : all.entrySet()) {
            TreeMap<Integer, String> row = cells.get(e.getKey());
            out.write(e.getValue() + (row == null ? "/>" : ">"));
            if (row != null) {
                for (String c : row.values()) {
                    out.write(c);
                }
                out.write("</row>");
            }
            if (out.full()) {
                truncated = true;
                break;
            }
        }
        out.write("</sheetData>");
        if (hCenter || vCenter || gridLines || headings) {
            out.write("<printOptions" + (hCenter ? " horizontalCentered=\"1\"" : "")
                    + (vCenter ? " verticalCentered=\"1\"" : "") + (headings ? " headings=\"1\"" : "")
                    + (gridLines ? " gridLines=\"1\"" : "") + "/>");
        }
        out.write("<pageMargins left=\"" + margins[0] + "\" right=\"" + margins[1] + "\" top=\"" + margins[2]
                + "\" bottom=\"" + margins[3] + "\" header=\"" + safe(headerFooterMargins[0]) + "\" footer=\""
                + safe(headerFooterMargins[1]) + "\"/>");
        out.write(setup);
        if (!header.isEmpty() || !footer.isEmpty()) {
            out.write("<headerFooter>" + (header.isEmpty() ? "" : "<oddHeader>" + Xml.attr(header) + "</oddHeader>")
                    + (footer.isEmpty() ? "" : "<oddFooter>" + Xml.attr(footer) + "</oddFooter>") + "</headerFooter>");
        }
        if (!rowBreaks.isEmpty()) {
            out.write("<rowBreaks count=\"" + rowBreaks.size() + "\" manualBreakCount=\"" + rowBreaks.size() + "\">"
                    + String.join("", rowBreaks) + "</rowBreaks>");
        }
        if (!colBreaks.isEmpty()) {
            out.write("<colBreaks count=\"" + colBreaks.size() + "\" manualBreakCount=\"" + colBreaks.size() + "\">"
                    + String.join("", colBreaks) + "</colBreaks>");
        }
        out.write("</worksheet>");
    }

    private static double safe(double v) {
        return Double.isFinite(v) && v >= 0 && v < 50 ? v : 0.5;
    }
}
