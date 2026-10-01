package stirling.software.officeconvert.topdf.odf;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.w3c.dom.Element;

final class OdfChart {

    static final int MAX_POINTS = 4000;

    static final int MAX_SERIES = 64;

    private static final String[] PALETTE = {"004586", "FF420E", "FFD320", "579D1C", "7E0021", "83CAFF", "314004",
        "AECF00", "4B1F6F", "FF950E", "C5000B", "0084D1"};

    private final Styles styles;

    private final Element chart;

    private OdfChart(Styles styles, Element chart) {
        this.styles = styles;
        this.chart = chart;
    }

    static String part(Element content, Element objectStyles) {
        Element body = Dom.kid(Dom.kid(content, Ns.OFFICE, "body"), Ns.OFFICE, "chart");
        Element chart = Dom.kid(body, CHART, "chart");
        if (chart == null) {
            return null;
        }
        try {
            return new OdfChart(new Styles(content, objectStyles == null ? content : objectStyles), chart).xml();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static final String CHART = "urn:oasis:names:tc:opendocument:xmlns:chart:1.0";

    private Props props(Element e, String kind) {
        return styles.props("chart", Dom.attr(e, CHART, "style-name"), Styles.Scope.CONTENT, kind, false);
    }

    private String xml() {
        String cls = Dom.attr(chart, CHART, "class", "chart:bar").replace("chart:", "");
        Element plot = Dom.kid(chart, CHART, "plot-area");
        Props pc = props(plot, "chart-properties");
        List<String> categories = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<List<Double>> columns = new ArrayList<>();
        table(categories, names, columns, "rows".equals(pc.get("chart:series-source")));
        List<Element> series = Dom.kids(plot, CHART, "series");
        StringBuilder b = new StringBuilder(Xml.HEAD).append("<c:chartSpace xmlns:c=\"")
                .append("http://schemas.openxmlformats.org/drawingml/2006/chart\" xmlns:a=\"").append(Xml.A)
                .append("\" xmlns:r=\"").append(Xml.R).append("\"><c:roundedCorners val=\"0\"/><c:chart>");
        Element title = Dom.kid(chart, CHART, "title");
        if (title != null) {
            b.append("<c:title>").append(rich(title)).append("<c:overlay val=\"0\"/></c:title>")
                    .append("<c:autoTitleDeleted val=\"0\"/>");
        } else {
            b.append("<c:autoTitleDeleted val=\"1\"/>");
        }
        b.append("<c:plotArea><c:layout/>");
        boolean pie = cls.equals("circle") || cls.equals("ring");
        boolean scatter = cls.equals("scatter") || cls.equals("bubble");
        String group = "true".equals(pc.get("chart:percentage")) ? "percentStacked"
                : "true".equals(pc.get("chart:stacked")) ? "stacked" : "standard";
        String tag = switch (cls) {
            case "line", "stock" -> "lineChart";
            case "area" -> "areaChart";
            case "circle" -> "pieChart";
            case "ring" -> "doughnutChart";
            case "scatter", "bubble" -> "scatterChart";
            case "radar", "filled-radar" -> "radarChart";
            default -> "barChart";
        };
        b.append("<c:").append(tag).append('>');
        switch (tag) {
            case "barChart" -> b.append("<c:barDir val=\"").append("true".equals(pc.get("chart:vertical")) ? "bar" : "col")
                    .append("\"/><c:grouping val=\"").append(group.equals("standard") ? "clustered" : group)
                    .append("\"/>");
            case "lineChart", "areaChart" -> b.append("<c:grouping val=\"").append(group).append("\"/>");
            case "scatterChart" -> b.append("<c:scatterStyle val=\"lineMarker\"/>");
            case "radarChart" -> b.append("<c:radarStyle val=\"").append(cls.equals("filled-radar") ? "filled" : "marker")
                    .append("\"/>");
            default -> {
            }
        }
        b.append("<c:varyColors val=\"").append(pie ? 1 : 0).append("\"/>");
        int valueColumn = scatter ? 1 : 0;
        for (int i = 0; i < series.size() && i < MAX_SERIES; i++) {
            Element s = series.get(i);
            int col = scatter ? valueColumn + i : i;
            if (col >= columns.size()) {
                break;
            }
            b.append(series(s, i, col < names.size() ? names.get(col) : "Series " + (i + 1), categories,
                    scatter ? columns.get(0) : null, columns.get(col), tag, pie));
        }
        if (tag.equals("barChart")) {
            b.append("<c:gapWidth val=\"100\"/>");
            if (!group.equals("standard")) {
                b.append("<c:overlap val=\"100\"/>");
            }
        }
        if (tag.equals("doughnutChart")) {
            b.append("<c:firstSliceAng val=\"0\"/><c:holeSize val=\"50\"/>");
        } else if (tag.equals("pieChart")) {
            b.append("<c:firstSliceAng val=\"0\"/>");
        }
        if (!pie) {
            b.append("<c:axId val=\"500000001\"/><c:axId val=\"500000002\"/>");
        }
        b.append("</c:").append(tag).append('>');
        if (!pie) {
            b.append(axes(plot, scatter, "true".equals(pc.get("chart:vertical"))));
        }
        b.append(fill(props(plot, "graphic-properties"), false));
        b.append("</c:plotArea>");
        Element legend = Dom.kid(chart, CHART, "legend");
        if (legend != null) {
            String pos = switch (Dom.attr(legend, CHART, "legend-position", "end")) {
                case "start" -> "l";
                case "top" -> "t";
                case "bottom" -> "b";
                default -> "r";
            };
            b.append("<c:legend><c:legendPos val=\"").append(pos).append("\"/><c:overlay val=\"0\"/>")
                    .append(textProps(props(legend, "text-properties"))).append("</c:legend>");
        }
        b.append("<c:plotVisOnly val=\"1\"/></c:chart>").append(fill(props(chart, "graphic-properties"), true))
                .append("</c:chartSpace>");
        return b.toString();
    }

    private String series(Element s, int index, String name, List<String> categories, List<Double> xs,
            List<Double> values, String tag, boolean pie) {
        StringBuilder b = new StringBuilder("<c:ser><c:idx val=\"").append(index).append("\"/><c:order val=\"")
                .append(index).append("\"/><c:tx><c:strRef><c:f></c:f><c:strCache><c:ptCount val=\"1\"/><c:pt idx=\"0\">")
                .append("<c:v>").append(Xml.esc(name)).append("</c:v></c:pt></c:strCache></c:strRef></c:tx>");
        Props g = props(s, "graphic-properties");
        String color = PALETTE[index % PALETTE.length];
        boolean line = tag.equals("lineChart") || tag.equals("scatterChart") || tag.equals("radarChart");
        if (!pie) {
            String fill = Colors.fill(g.get("draw:fill-color"));
            String stroke = Colors.fill(g.get("svg:stroke-color"));
            b.append("<c:spPr>");
            if (line) {
                b.append("<a:ln w=\"").append(Length.emu(Math.max(1.5, g.pt("svg:stroke-width", 1.5))))
                        .append("\"><a:solidFill><a:srgbClr val=\"")
                        .append(stroke != null ? stroke : fill != null ? fill : color).append("\"/></a:solidFill></a:ln>");
            } else {
                b.append("<a:solidFill><a:srgbClr val=\"").append(fill != null ? fill : color).append("\"/></a:solidFill>");
                if ("none".equals(g.get("draw:stroke")) || stroke == null) {
                    b.append("<a:ln><a:noFill/></a:ln>");
                } else {
                    b.append("<a:ln><a:solidFill><a:srgbClr val=\"").append(stroke).append("\"/></a:solidFill></a:ln>");
                }
            }
            b.append("</c:spPr>");
            if (line) {
                Props cp = props(s, "chart-properties");
                String symbol = cp.get("chart:symbol-type", "none");
                b.append(symbol.equals("none") ? "<c:marker><c:symbol val=\"none\"/></c:marker>" : "");
            }
        } else {
            int n = 0;
            for (Element dp : Dom.kids(s, CHART, "data-point")) {
                int repeat = Math.max(1, Math.min(MAX_POINTS, Dom.integer(dp, CHART, "repeated", 1)));
                Props dg = props(dp, "graphic-properties");
                String fill = Colors.fill(dg.get("draw:fill-color"));
                for (int k = 0; k < repeat && n < values.size(); k++, n++) {
                    b.append("<c:dPt><c:idx val=\"").append(n).append("\"/><c:bubble3D val=\"0\"/><c:spPr><a:solidFill>")
                            .append("<a:srgbClr val=\"").append(fill != null ? fill : PALETTE[n % PALETTE.length])
                            .append("\"/></a:solidFill></c:spPr></c:dPt>");
                }
            }
            for (; n < values.size() && n < MAX_POINTS; n++) {
                b.append("<c:dPt><c:idx val=\"").append(n).append("\"/><c:bubble3D val=\"0\"/><c:spPr><a:solidFill>")
                        .append("<a:srgbClr val=\"").append(PALETTE[n % PALETTE.length])
                        .append("\"/></a:solidFill></c:spPr></c:dPt>");
            }
        }
        if (xs != null) {
            b.append("<c:xVal>").append(numbers(xs)).append("</c:xVal><c:yVal>").append(numbers(values))
                    .append("</c:yVal><c:smooth val=\"0\"/>");
        } else {
            if (!categories.isEmpty()) {
                b.append("<c:cat><c:strLit><c:ptCount val=\"").append(Math.min(MAX_POINTS, categories.size())).append("\"/>");
                for (int i = 0; i < categories.size() && i < MAX_POINTS; i++) {
                    b.append("<c:pt idx=\"").append(i).append("\"><c:v>").append(Xml.esc(categories.get(i)))
                            .append("</c:v></c:pt>");
                }
                b.append("</c:strLit></c:cat>");
            }
            b.append("<c:val>").append(numbers(values)).append("</c:val>");
            if (tag.equals("lineChart")) {
                b.append("<c:smooth val=\"0\"/>");
            }
        }
        return b.append("</c:ser>").toString();
    }

    private static String numbers(List<Double> values) {
        StringBuilder b = new StringBuilder("<c:numLit><c:formatCode>General</c:formatCode><c:ptCount val=\"")
                .append(Math.min(MAX_POINTS, values.size())).append("\"/>");
        for (int i = 0; i < values.size() && i < MAX_POINTS; i++) {
            Double v = values.get(i);
            if (v != null) {
                b.append("<c:pt idx=\"").append(i).append("\"><c:v>").append(v).append("</c:v></c:pt>");
            }
        }
        return b.append("</c:numLit>").toString();
    }

    private String axes(Element plot, boolean scatter, boolean horizontal) {
        Element x = null;
        Element y = null;
        for (Element a : Dom.kids(plot, CHART, "axis")) {
            String d = Dom.attr(a, CHART, "dimension", "");
            String n = Dom.attr(a, CHART, "name", "");
            if (d.equals("x") && (x == null || n.startsWith("primary"))) {
                x = a;
            } else if (d.equals("y") && (y == null || n.startsWith("primary"))) {
                y = a;
            }
        }
        String catPos = horizontal ? "l" : "b";
        String valPos = horizontal ? "b" : "l";
        StringBuilder b = new StringBuilder();
        if (scatter) {
            b.append(axis("valAx", 500000001, 500000002, x, catPos, true));
        } else {
            b.append(axis("catAx", 500000001, 500000002, x, catPos, false));
        }
        b.append(axis("valAx", 500000002, 500000001, y, valPos, true));
        return b.toString();
    }

    private String axis(String tag, long id, long cross, Element a, String pos, boolean value) {
        Props cp = a == null ? new Props() : props(a, "chart-properties");
        boolean shown = a != null && !"false".equals(cp.get("chart:display-label"));
        StringBuilder b = new StringBuilder("<c:").append(tag).append("><c:axId val=\"").append(id)
                .append("\"/><c:scaling><c:orientation val=\"")
                .append("true".equals(cp.get("chart:reverse-direction")) ? "maxMin" : "minMax").append("\"/>");
        if (value) {
            if (cp.has("chart:maximum") && !"true".equals(cp.get("chart:auto-maximum"))) {
                b.append("<c:max val=\"").append(number(cp.get("chart:maximum"))).append("\"/>");
            }
            if (cp.has("chart:minimum") && !"true".equals(cp.get("chart:auto-minimum"))) {
                b.append("<c:min val=\"").append(number(cp.get("chart:minimum"))).append("\"/>");
            }
        }
        b.append("</c:scaling><c:delete val=\"").append(shown ? 0 : 1).append("\"/><c:axPos val=\"").append(pos)
                .append("\"/>");
        for (Element grid : Dom.kids(a, CHART, "grid")) {
            String cls = Dom.attr(grid, CHART, "class", "major");
            b.append(cls.equals("minor") ? "<c:minorGridlines/>" : "<c:majorGridlines/>");
        }
        Element title = Dom.kid(a, CHART, "title");
        if (title != null) {
            b.append("<c:title>").append(rich(title)).append("<c:overlay val=\"0\"/></c:title>");
        }
        b.append("<c:numFmt formatCode=\"General\" sourceLinked=\"1\"/><c:majorTickMark val=\"out\"/>")
                .append("<c:minorTickMark val=\"none\"/><c:tickLblPos val=\"nextTo\"/>")
                .append(a == null ? "" : textProps(props(a, "text-properties"))).append("<c:crossAx val=\"")
                .append(cross).append("\"/><c:crosses val=\"autoZero\"/>");
        if (tag.equals("valAx")) {
            b.append("<c:crossBetween val=\"between\"/>");
        } else {
            b.append("<c:auto val=\"1\"/><c:lblAlgn val=\"ctr\"/><c:lblOffset val=\"100\"/>");
        }
        return b.append("</c:").append(tag).append('>').toString();
    }

    private static String number(String v) {
        try {
            double d = Double.parseDouble(v.trim());
            return Double.isFinite(d) ? String.valueOf(d) : "0";
        } catch (NumberFormatException e) {
            return "0";
        }
    }

    private String rich(Element title) {
        StringBuilder text = new StringBuilder();
        for (Element p : Dom.kids(title, Ns.TEXT, "p")) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(p.getTextContent());
        }
        Props tp = props(title, "text-properties");
        double size = tp.pt("fo:font-size", 13);
        StringBuilder b = new StringBuilder("<c:tx><c:rich><a:bodyPr/><a:lstStyle/>");
        for (String line : text.toString().split("\n", -1)) {
            b.append("<a:p><a:pPr><a:defRPr sz=\"").append(Math.round(size * 100)).append("\" b=\"")
                    .append(WordRun.bold(tp.get("fo:font-weight")) ? 1 : 0).append("\"/></a:pPr><a:r><a:rPr sz=\"")
                    .append(Math.round(size * 100)).append("\" b=\"").append(WordRun.bold(tp.get("fo:font-weight")) ? 1 : 0)
                    .append("\"/><a:t>").append(Xml.esc(line)).append("</a:t></a:r></a:p>");
        }
        return b.append("</c:rich></c:tx>").toString();
    }

    private static String textProps(Props tp) {
        double size = tp.pt("fo:font-size", Double.NaN);
        if (Double.isNaN(size)) {
            return "";
        }
        return "<c:txPr><a:bodyPr/><a:lstStyle/><a:p><a:pPr><a:defRPr sz=\"" + Math.round(size * 100)
                + "\"/></a:pPr><a:endParaRPr lang=\"en-US\"/></a:p></c:txPr>";
    }

    private static String fill(Props g, boolean chartArea) {
        String fill = g.get("draw:fill");
        String color = Colors.fill(g.get("draw:fill-color"));
        StringBuilder b = new StringBuilder("<c:spPr>");
        if ("none".equals(fill) || fill == null && color == null) {
            b.append("<a:noFill/>");
        } else {
            b.append("<a:solidFill><a:srgbClr val=\"").append(color == null ? "FFFFFF" : color).append("\"/></a:solidFill>");
        }
        if ("none".equals(g.get("draw:stroke")) || !chartArea && !g.has("draw:stroke")) {
            b.append("<a:ln><a:noFill/></a:ln>");
        }
        return b.append("</c:spPr>").toString();
    }

    private void table(List<String> categories, List<String> names, List<List<Double>> columns, boolean byRows) {
        Element table = null;
        for (Element t : Dom.kids(chart, Ns.TABLE, "table")) {
            table = t;
        }
        if (table == null) {
            return;
        }
        List<Element> rows = new ArrayList<>();
        Element header = Dom.kid(table, Ns.TABLE, "table-header-rows");
        Element headerRow = Dom.kid(header, Ns.TABLE, "table-row");
        for (Element r : Dom.kids(Dom.kid(table, Ns.TABLE, "table-rows"), Ns.TABLE, "table-row")) {
            rows.add(r);
        }
        for (Element r : Dom.kids(table, Ns.TABLE, "table-row")) {
            rows.add(r);
        }
        boolean labelColumn = Dom.kid(table, Ns.TABLE, "table-header-columns") != null;
        if (byRows) {
            byRows(headerRow, rows, labelColumn, categories, names, columns);
            return;
        }
        if (headerRow != null) {
            List<Element> cells = cells(headerRow);
            for (int i = labelColumn ? 1 : 0; i < cells.size() && names.size() < MAX_SERIES; i++) {
                names.add(SheetWriter.text(cells.get(i)));
            }
        }
        for (int r = 0; r < rows.size() && r < MAX_POINTS; r++) {
            List<Element> cells = cells(rows.get(r));
            int start = labelColumn ? 1 : 0;
            if (labelColumn && !cells.isEmpty()) {
                categories.add(SheetWriter.text(cells.get(0)));
            }
            for (int i = start; i < cells.size() && i - start < MAX_SERIES; i++) {
                while (columns.size() <= i - start) {
                    columns.add(new ArrayList<>());
                }
                List<Double> col = columns.get(i - start);
                while (col.size() < r) {
                    col.add(null);
                }
                col.add(value(cells.get(i)));
            }
        }
    }

    private static void byRows(Element headerRow, List<Element> rows, boolean labelColumn, List<String> categories,
            List<String> names, List<List<Double>> columns) {
        int start = labelColumn ? 1 : 0;
        if (headerRow != null) {
            List<Element> cells = cells(headerRow);
            for (int i = start; i < cells.size() && categories.size() < MAX_POINTS; i++) {
                categories.add(SheetWriter.text(cells.get(i)));
            }
        }
        for (int r = 0; r < rows.size() && r < MAX_SERIES; r++) {
            List<Element> cells = cells(rows.get(r));
            names.add(labelColumn && !cells.isEmpty() ? SheetWriter.text(cells.get(0)) : "Series " + (r + 1));
            List<Double> values = new ArrayList<>();
            for (int i = start; i < cells.size() && values.size() < MAX_POINTS; i++) {
                values.add(value(cells.get(i)));
            }
            columns.add(values);
        }
    }

    private static List<Element> cells(Element row) {
        List<Element> out = new ArrayList<>();
        for (Element c : Dom.kids(row)) {
            if (Dom.is(c, Ns.TABLE, "table-cell") || Dom.is(c, Ns.TABLE, "covered-table-cell")) {
                int repeat = Math.max(1, Math.min(MAX_SERIES, Dom.integer(c, Ns.TABLE, "number-columns-repeated", 1)));
                for (int i = 0; i < repeat; i++) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    private static Double value(Element c) {
        String v = Dom.attr(c, Ns.OFFICE, "value");
        String t = v != null ? v : SheetWriter.text(c).trim();
        if (t.isEmpty() || t.toLowerCase(Locale.ROOT).equals("nan")) {
            return null;
        }
        try {
            double d = Double.parseDouble(t);
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
