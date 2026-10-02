package stirling.software.officeconvert.topdf.xlsb;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.topdf.xls.Parts;
import stirling.software.officeconvert.topdf.xls.Xml;

/** One worksheet .bin streamed into worksheet XML: rows and cells as they are read, with cached formula results
 * only; the settings around them collected and written in SpreadsheetML's order. */
final class Sheet {

    private static final int MAX_LIST = 100_000;

    private final Styles styles;

    private final Parts.Part out;

    private final StringBuilder sheetPr = new StringBuilder();

    private String dimension = "";

    private String view = "";

    private String format = "";

    private final StringBuilder cols = new StringBuilder();

    private final List<String> merges = new ArrayList<>();

    private final CondFormats conditional = new CondFormats();

    private String printOptions = "";

    private String margins = "";

    private String pageSetup = "";

    private String headerFooter = "";

    private final StringBuilder rowBreaks = new StringBuilder();

    private final StringBuilder colBreaks = new StringBuilder();

    private int rowBreakCount;

    private int colBreakCount;

    private boolean inRowBreaks;

    private String drawing = "";

    private String legacyDrawing = "";

    private final List<String> tables = new ArrayList<>();

    private boolean headWritten;

    private boolean rowOpen;

    private int row = -1;

    private int col = -1;

    boolean truncated;

    boolean printsComments;

    int skippedRules() {
        return conditional.skipped;
    }

    Sheet(Styles styles, Parts.Part out) {
        this.styles = styles;
        this.out = out;
    }

    void write(InputStream in) throws IOException {
        Records r = new Records(in);
        int future = 0;
        boolean data = false;
        while (r.next()) {
            int type = r.type();
            if (type == Ids.AC_BEGIN) {
                future++;
                continue;
            }
            if (type == Ids.AC_END) {
                future = Math.max(0, future - 1);
                continue;
            }
            if (future > 0) {
                continue;
            }
            Data d = r.data();
            if (type == Ids.SHEETDATA) {
                head();
                out.write("<sheetData>");
                data = true;
                continue;
            }
            if (type == Ids.SHEETDATA_END) {
                closeRow();
                out.write("</sheetData>");
                data = false;
                continue;
            }
            if (data) {
                if (!truncated) {
                    cell(type, d);
                    if (out.full()) {
                        truncated = true;
                    }
                }
                continue;
            }
            setting(type, d);
        }
        if (data) {
            closeRow();
            out.write("</sheetData>");
        }
        head();
        tail();
    }

    private void head() throws IOException {
        if (headWritten) {
            return;
        }
        headWritten = true;
        out.write(Xml.HEAD + "<worksheet xmlns=\"" + Xml.MAIN + "\" xmlns:r=\"" + Xml.REL + "\">");
        out.write(sheetPr.isEmpty() ? "" : "<sheetPr>" + sheetPr + "</sheetPr>");
        out.write(dimension);
        out.write("<sheetViews><sheetView workbookViewId=\"0\"" + view + "/></sheetViews>");
        out.write(format);
        if (!cols.isEmpty()) {
            out.write("<cols>" + cols + "</cols>");
        }
    }

    private void tail() throws IOException {
        if (!merges.isEmpty()) {
            out.write("<mergeCells count=\"" + merges.size() + "\">");
            for (String m : merges) {
                out.write("<mergeCell ref=\"" + m + "\"/>");
            }
            out.write("</mergeCells>");
        }
        out.write(conditional.xml());
        out.write(printOptions + margins + pageSetup + headerFooter);
        if (rowBreakCount > 0) {
            out.write("<rowBreaks count=\"" + rowBreakCount + "\" manualBreakCount=\"" + rowBreakCount + "\">"
                    + rowBreaks + "</rowBreaks>");
        }
        if (colBreakCount > 0) {
            out.write("<colBreaks count=\"" + colBreakCount + "\" manualBreakCount=\"" + colBreakCount + "\">"
                    + colBreaks + "</colBreaks>");
        }
        out.write(drawing + legacyDrawing);
        if (!tables.isEmpty()) {
            out.write("<tableParts count=\"" + tables.size() + "\">");
            for (String id : tables) {
                out.write("<tablePart r:id=\"" + Xml.attr(id) + "\"/>");
            }
            out.write("</tableParts>");
        }
        out.write("</worksheet>");
    }

    private void setting(int type, Data d) {
        switch (type) {
            case Ids.SHEETPR -> {
                int f = d.u16();
                if ((f & 0x0100) != 0) {
                    sheetPr.append("<pageSetUpPr fitToPage=\"1\"/>");
                }
            }
            case Ids.DIMENSION -> {
                String ref = Refs.range(d);
                dimension = ref == null ? "" : "<dimension ref=\"" + ref + "\"/>";
            }
            case Ids.SHEETVIEW -> {
                if (view.isEmpty()) {
                    view = view(d);
                }
            }
            case Ids.SHEETFORMATPR -> format = sheetFormat(d);
            case Ids.COL -> col(d);
            case Ids.MERGECELL -> {
                String ref = Refs.range(d);
                if (ref != null && ref.indexOf(':') > 0 && merges.size() < MAX_LIST) {
                    merges.add(ref);
                }
            }
            case Ids.CONDFORMATTING, Ids.CONDFORMATTING_END, Ids.CFRULE, Ids.COLORSCALE, Ids.DATABAR, Ids.CFVO,
                    Ids.COLOR -> conditional.record(type, d);
            case Ids.PRINTOPTIONS -> printOptions = PageXml.printOptions(d);
            case Ids.PAGEMARGINS -> margins = PageXml.margins(d);
            case Ids.PAGESETUP -> {
                pageSetup = PageXml.pageSetup(d);
                printsComments = pageSetup.contains("cellComments");
            }
            case Ids.HEADERFOOTER -> headerFooter = PageXml.headerFooter(d);
            case Ids.ROWBREAKS -> inRowBreaks = true;
            case Ids.COLBREAKS -> inRowBreaks = false;
            case Ids.BRK -> brk(d);
            case Ids.DRAWING -> drawing = "<drawing r:id=\"" + Xml.attr(d.string()) + "\"/>";
            case Ids.LEGACYDRAWING -> legacyDrawing = "<legacyDrawing r:id=\"" + Xml.attr(d.string()) + "\"/>";
            case Ids.TABLEPART -> {
                if (tables.size() < MAX_LIST) {
                    tables.add(d.string());
                }
            }
            default -> {
            }
        }
    }

    private static String view(Data d) {
        int f = d.u16();
        int type = d.i32();
        StringBuilder b = new StringBuilder();
        if ((f & 0x0004) == 0) {
            b.append(" showGridLines=\"0\"");
        }
        if ((f & 0x0010) == 0) {
            b.append(" showZeros=\"0\"");
        }
        if ((f & 0x0020) != 0) {
            b.append(" rightToLeft=\"1\"");
        }
        if (type == 1) {
            b.append(" view=\"pageBreakPreview\"");
        } else if (type == 2) {
            b.append(" view=\"pageLayout\"");
        }
        return b.toString();
    }

    private String sheetFormat(Data d) {
        long width = d.u32();
        int base = d.u16();
        int height = d.u16();
        int f = d.u16();
        StringBuilder b = new StringBuilder("<sheetFormatPr");
        if (base > 0 && base < 256) {
            b.append(" baseColWidth=\"").append(base).append('"');
        }
        if (width != 0xFFFFFFFFL && width < 256L * 256) {
            b.append(" defaultColWidth=\"").append(width / 256.0).append('"');
        }
        b.append(" defaultRowHeight=\"").append(height > 0 && height < 8192 ? height / 20.0 : 15).append('"');
        if ((f & 0x0001) != 0) {
            b.append(" customHeight=\"1\"");
        }
        if ((f & 0x0002) != 0) {
            b.append(" zeroHeight=\"1\"");
        }
        return b.append("/>").toString();
    }

    private void col(Data d) {
        int first = d.i32();
        int last = d.i32();
        int width = d.i32();
        int xf = d.i32();
        int f = d.u16();
        if (first < 0 || last < first || last > Refs.MAX_COL || cols.length() > 4 << 20) {
            return;
        }
        cols.append("<col min=\"").append(first + 1).append("\" max=\"").append(last + 1).append("\" width=\"")
                .append(Math.max(0, width) / 256.0).append('"');
        if (xf > 0) {
            cols.append(" style=\"").append(xf).append('"');
        }
        if ((f & 0x0001) != 0) {
            cols.append(" hidden=\"1\"");
        }
        if ((f & 0x0002) != 0) {
            cols.append(" customWidth=\"1\"");
        }
        if ((f & 0x0004) != 0) {
            cols.append(" bestFit=\"1\"");
        }
        int level = f >> 8 & 0x07;
        if (level > 0) {
            cols.append(" outlineLevel=\"").append(level).append('"');
        }
        cols.append("/>");
    }

    private void brk(Data d) {
        int id = d.i32();
        int min = d.i32();
        int max = d.i32();
        int manual = d.i32();
        if (manual == 0 || id < 0 || Math.max(rowBreakCount, colBreakCount) >= 1026) {
            return;
        }
        String x = "<brk id=\"" + id + "\" min=\"" + Math.max(0, min) + "\" max=\"" + Math.max(0, max)
                + "\" man=\"1\"/>";
        if (inRowBreaks) {
            rowBreaks.append(x);
            rowBreakCount++;
        } else {
            colBreaks.append(x);
            colBreakCount++;
        }
    }

    private void closeRow() throws IOException {
        if (rowOpen) {
            out.write("</row>");
            rowOpen = false;
        }
    }

    private void cell(int type, Data d) throws IOException {
        if (type == Ids.ROW) {
            row(d);
            return;
        }
        boolean multi = type >= Ids.MULTCELL_BLANK && type <= Ids.MULTCELL_SI || type == Ids.MULTCELL_RSTRING;
        boolean known = multi || type >= Ids.CELL_BLANK && type <= Ids.FORMULA_ERROR || type == Ids.CELL_RSTRING;
        if (!known || !rowOpen) {
            return;
        }
        int c = multi ? col + 1 : d.i32();
        long xf = d.u32() & 0xFFFFFF;
        if (!Refs.valid(row, c) || c <= col) {
            return;
        }
        col = c;
        String s = xf == 0 ? "" : " s=\"" + xf + "\"";
        String ref = "<c r=\"" + Refs.cell(row, c) + "\"" + s;
        switch (type) {
            case Ids.CELL_BLANK, Ids.MULTCELL_BLANK -> {
                if (xf != 0) {
                    out.write(ref + "/>");
                }
            }
            case Ids.CELL_RK, Ids.MULTCELL_RK -> out.write(ref + "><v>" + Refs.number(Refs.rk(d.i32())) + "</v></c>");
            case Ids.CELL_DOUBLE, Ids.MULTCELL_DOUBLE, Ids.FORMULA_DOUBLE -> {
                double v = d.f64();
                out.write(Double.isFinite(v) ? ref + "><v>" + Refs.number(v) + "</v></c>" : ref + "/>");
            }
            case Ids.CELL_BOOL, Ids.MULTCELL_BOOL, Ids.FORMULA_BOOL ->
                out.write(ref + " t=\"b\"><v>" + (d.u8() != 0 ? 1 : 0) + "</v></c>");
            case Ids.CELL_ERROR, Ids.MULTCELL_ERROR, Ids.FORMULA_ERROR ->
                out.write(ref + " t=\"e\"><v>" + Xml.attr(Refs.error(d.u8())) + "</v></c>");
            case Ids.CELL_SI, Ids.MULTCELL_SI -> out.write(ref + " t=\"s\"><v>" + d.u32() + "</v></c>");
            case Ids.FORMULA_STRING -> out.write(ref + " t=\"str\"><v>" + Xml.text(d.string()) + "</v></c>");
            case Ids.CELL_STRING, Ids.MULTCELL_STRING ->
                out.write(ref + " t=\"inlineStr\"><is>" + RichStrings.item(d, false, styles) + "</is></c>");
            case Ids.CELL_RSTRING, Ids.MULTCELL_RSTRING ->
                out.write(ref + " t=\"inlineStr\"><is>" + RichStrings.item(d, true, styles) + "</is></c>");
            default -> {
            }
        }
    }

    private void row(Data d) throws IOException {
        int r = d.i32();
        int xf = d.i32();
        int height = d.u16();
        int f = d.u16();
        if (r == row && rowOpen) {
            return;
        }
        closeRow();
        if (r < 0 || r > Refs.MAX_ROW) {
            row = Integer.MAX_VALUE;
            return;
        }
        row = r;
        col = -1;
        StringBuilder b = new StringBuilder("<row r=\"").append(r + 1).append('"');
        if ((f & 0x4000) != 0 && xf > 0) {
            b.append(" s=\"").append(xf & 0xFFFFFF).append("\" customFormat=\"1\"");
        }
        boolean custom = (f & 0x2000) != 0;
        if (height > 0 && height < 8192) {
            b.append(" ht=\"").append(height / 20.0).append('"');
            if (custom) {
                b.append(" customHeight=\"1\"");
            }
        }
        if ((f & 0x1000) != 0) {
            b.append(" hidden=\"1\"");
        }
        int level = f >> 8 & 0x07;
        if (level > 0) {
            b.append(" outlineLevel=\"").append(level).append('"');
        }
        out.write(b.append('>').toString());
        rowOpen = true;
    }
}
