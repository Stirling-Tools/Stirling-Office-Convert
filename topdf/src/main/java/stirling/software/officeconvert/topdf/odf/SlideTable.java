package stirling.software.officeconvert.topdf.odf;

import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;

final class SlideTable {

    static final int MAX_CELLS = 10_000;

    private final Styles styles;

    private final Styles.Scope scope;

    private final DmlText.Fields fields;

    SlideTable(Styles styles, Styles.Scope scope, DmlText.Fields fields) {
        this.styles = styles;
        this.scope = scope;
        this.fields = fields;
    }

    String xml(Element table, Box box, int id) {
        List<Double> widths = new ArrayList<>();
        for (Element c : Dom.kids(table, Ns.TABLE, "table-column")) {
            int repeat = Math.max(1, Math.min(64, Dom.integer(c, Ns.TABLE, "number-columns-repeated", 1)));
            Props cp = styles.props("table-column", Dom.attr(c, Ns.TABLE, "style-name"), scope, "table-column-properties",
                    false);
            for (int i = 0; i < repeat && widths.size() < 64; i++) {
                widths.add(cp.pt("style:column-width", Double.NaN));
            }
        }
        List<Element> rows = new ArrayList<>();
        collect(table, rows, 0);
        int cols = widths.size();
        for (Element r : rows) {
            int n = 0;
            for (Element c : Dom.kids(r)) {
                if (Dom.is(c, Ns.TABLE, "table-cell") || Dom.is(c, Ns.TABLE, "covered-table-cell")) {
                    n += Math.max(1, Dom.integer(c, Ns.TABLE, "number-columns-repeated", 1));
                }
            }
            cols = Math.max(cols, Math.min(64, n));
        }
        if (cols == 0 || rows.isEmpty()) {
            return "";
        }
        double known = 0;
        int unknown = 0;
        for (int i = 0; i < cols; i++) {
            double v = i < widths.size() ? widths.get(i) : Double.NaN;
            if (Double.isNaN(v)) {
                unknown++;
            } else {
                known += v;
            }
        }
        StringBuilder grid = new StringBuilder("<a:tblGrid>");
        double total = 0;
        for (int i = 0; i < cols; i++) {
            double v = i < widths.size() ? widths.get(i) : Double.NaN;
            if (Double.isNaN(v)) {
                v = unknown > 0 ? Math.max(9, (box.w() - known) / unknown) : 72;
            }
            total += v;
            grid.append("<a:gridCol w=\"").append(Length.emu(v)).append("\"/>");
        }
        grid.append("</a:tblGrid>");
        StringBuilder b = new StringBuilder("<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"").append(id)
                .append("\" name=\"Table ").append(id).append("\"/><p:cNvGraphicFramePr><a:graphicFrameLocks noGrp=\"1\"/>")
                .append("</p:cNvGraphicFramePr><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x=\"")
                .append(Length.emu(box.x())).append("\" y=\"").append(Length.emu(box.y())).append("\"/><a:ext cx=\"")
                .append(Length.emu(total)).append("\" cy=\"").append(Length.emu(box.h())).append("\"/></p:xfrm>")
                .append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\"><a:tbl>")
                .append("<a:tblPr/>").append(grid);
        int[] vleft = new int[cols];
        int cells = 0;
        for (Element r : rows) {
            Props rp = styles.props("table-row", Dom.attr(r, Ns.TABLE, "style-name"), scope, "table-row-properties",
                    false);
            double h = rp.pt("style:row-height", rp.pt("style:min-row-height", 18));
            b.append("<a:tr h=\"").append(Length.emu(h)).append("\">");
            String rowDefault = Dom.attr(r, Ns.TABLE, "default-cell-style-name");
            int col = 0;
            for (Element c : Dom.kids(r)) {
                boolean covered = Dom.is(c, Ns.TABLE, "covered-table-cell");
                if (!covered && !Dom.is(c, Ns.TABLE, "table-cell")) {
                    continue;
                }
                int repeat = Math.max(1, Math.min(64, Dom.integer(c, Ns.TABLE, "number-columns-repeated", 1)));
                for (int k = 0; k < repeat && col < cols; k++, col++) {
                    if (++cells > MAX_CELLS) {
                        break;
                    }
                    String style = Dom.attr(c, Ns.TABLE, "style-name");
                    if (style == null) {
                        style = rowDefault;
                    }
                    if (covered) {
                        boolean vertical = vleft[col] > 0;
                        b.append("<a:tc").append(vertical ? " vMerge=\"1\"" : " hMerge=\"1\"")
                                .append("><a:txBody><a:bodyPr/><a:lstStyle/><a:p/></a:txBody><a:tcPr/></a:tc>");
                        if (vleft[col] > 0) {
                            vleft[col]--;
                        }
                        continue;
                    }
                    int span = Math.max(1, Dom.integer(c, Ns.TABLE, "number-columns-spanned", 1));
                    int down = Math.max(1, Dom.integer(c, Ns.TABLE, "number-rows-spanned", 1));
                    if (down > 1) {
                        for (int s = col; s < Math.min(cols, col + span); s++) {
                            vleft[s] = down - 1;
                        }
                    }
                    b.append("<a:tc");
                    if (span > 1) {
                        b.append(" gridSpan=\"").append(Math.min(span, cols - col)).append('"');
                    }
                    if (down > 1) {
                        b.append(" rowSpan=\"").append(down).append('"');
                    }
                    b.append('>').append(cell(c, style)).append("</a:tc>");
                }
            }
            while (col < cols) {
                b.append("<a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p/></a:txBody><a:tcPr/></a:tc>");
                col++;
            }
            b.append("</a:tr>");
        }
        return b.append("</a:tbl></a:graphicData></a:graphic></p:graphicFrame>").toString();
    }

    private void collect(Element parent, List<Element> rows, int depth) {
        if (depth > 4) {
            return;
        }
        for (Element k : Dom.kids(parent)) {
            if (Dom.is(k, Ns.TABLE, "table-row")) {
                int repeat = Math.max(1, Math.min(200, Dom.integer(k, Ns.TABLE, "number-rows-repeated", 1)));
                for (int i = 0; i < repeat && rows.size() < 1000; i++) {
                    rows.add(k);
                }
            } else if (Dom.is(k, Ns.TABLE, "table-header-rows") || Dom.is(k, Ns.TABLE, "table-rows")
                    || Dom.is(k, Ns.TABLE, "table-row-group")) {
                collect(k, rows, depth + 1);
            }
        }
    }

    private String cell(Element c, String style) {
        Props g = styles.props("table-cell", style, scope, "graphic-properties", false);
        Props cp = styles.props("table-cell", style, scope, "table-cell-properties", false);
        Props pp = styles.props("table-cell", style, scope, "paragraph-properties", false);
        Props base = new Props(styles.props("graphic", null, scope, "text-properties", true));
        base.merge(styles.props("table-cell", style, scope, "text-properties", false));
        DmlText.Levels levels = new DmlText.Levels() {
            @Override
            public Props paragraph(int level) {
                return styles.props("graphic", null, scope, "paragraph-properties", true);
            }

            @Override
            public Props text(int level) {
                return base;
            }

            @Override
            public Element listStyle() {
                return null;
            }
        };
        String text = new DmlText(styles, scope, levels, fields).paragraphs(c);
        StringBuilder b = new StringBuilder("<a:txBody><a:bodyPr/><a:lstStyle/>").append(text).append("</a:txBody>");
        Props all = new Props(cp);
        all.merge(g);
        all.merge(pp);
        String anchor = switch (all.get("draw:textarea-vertical-align", all.get("style:vertical-align", "top"))) {
            case "middle" -> "ctr";
            case "bottom" -> "b";
            default -> "t";
        };
        b.append("<a:tcPr marL=\"").append(Length.emu(all.pt("fo:padding-left", 7.2))).append("\" marR=\"")
                .append(Length.emu(all.pt("fo:padding-right", 7.2))).append("\" marT=\"")
                .append(Length.emu(all.pt("fo:padding-top", 3.6))).append("\" marB=\"")
                .append(Length.emu(all.pt("fo:padding-bottom", 3.6))).append("\" anchor=\"").append(anchor).append("\">");
        for (String[] side : new String[][] {{"left", "lnL"}, {"right", "lnR"}, {"top", "lnT"}, {"bottom", "lnB"}}) {
            Border border = Border.parse(all.get("fo:border-" + side[0]), all.get("style:border-line-width-" + side[0]));
            if (border == null) {
                b.append("<a:").append(side[1]).append(" w=\"0\"><a:noFill/></a:").append(side[1]).append('>');
            } else {
                b.append("<a:").append(side[1]).append(" w=\"").append(Length.emu(border.width()))
                        .append("\"><a:solidFill><a:srgbClr val=\"").append(border.color())
                        .append("\"/></a:solidFill></a:").append(side[1]).append('>');
            }
        }
        String fill = Dml.fill(all, styles, null);
        b.append(fill == null ? "<a:noFill/>" : fill);
        return b.append("</a:tcPr>").toString();
    }
}
