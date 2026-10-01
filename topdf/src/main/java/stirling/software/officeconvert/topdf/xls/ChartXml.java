package stirling.software.officeconvert.topdf.xls;

import java.util.List;
import java.util.Map;

final class ChartXml {

    private static final String C = "http://schemas.openxmlformats.org/drawingml/2006/chart";

    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";

    private ChartXml() {}

    static String write(BiffChart chart) {
        StringBuilder b = new StringBuilder(Xml.HEAD).append("<c:chartSpace xmlns:c=\"").append(C)
                .append("\" xmlns:a=\"").append(A).append("\" xmlns:r=\"").append(Xml.REL)
                .append("\"><c:roundedCorners val=\"0\"/><c:chart>");
        if (chart.title() != null) {
            b.append("<c:title>").append(rich(chart.title())).append("<c:overlay val=\"0\"/></c:title>");
        }
        b.append("<c:autoTitleDeleted val=\"1\"/><c:plotArea><c:layout/>");
        boolean[] used = new boolean[2];
        boolean scatter = false;
        for (BiffChart.Group g : chart.groups()) {
            group(b, g);
            if (g.kind() != BiffChart.Kind.PIE) {
                used[g.axisGroup()] = true;
            }
            scatter |= g.kind() == BiffChart.Kind.SCATTER;
        }
        boolean horizontal = chart.groups().stream().anyMatch(BiffChart.Group::horizontal);
        for (int g = 0; g < 2; g++) {
            if (used[g]) {
                axes(b, chart, g, horizontal, scatter);
            }
        }
        b.append("<c:spPr>").append(fill(chart.plotFill())).append("</c:spPr></c:plotArea>");
        if (chart.legend() != null) {
            legend(b, chart.legend());
        }
        b.append("<c:plotVisOnly val=\"1\"/></c:chart><c:spPr>").append(fill(chart.areaFill()))
                .append(line(chart.areaLine(), 0.75f)).append("</c:spPr><c:txPr><a:bodyPr/><a:lstStyle/><a:p>")
                .append("<a:pPr><a:defRPr sz=\"").append(Math.round(chart.textSize() * 100)).append("\">");
        if (chart.font() != null && !chart.font().isBlank()) {
            b.append("<a:latin typeface=\"").append(Xml.attr(chart.font())).append("\"/><a:ea typeface=\"")
                    .append(Xml.attr(chart.font())).append("\"/>");
        }
        return b.append("</a:defRPr></a:pPr><a:endParaRPr lang=\"en-US\"/></a:p></c:txPr></c:chartSpace>")
                .toString();
    }

    private static String rich(BiffChart.Text t) {
        String rpr = "sz=\"" + Math.round(t.size() * 100) + "\" b=\"" + (t.bold() ? 1 : 0) + "\"";
        StringBuilder b = new StringBuilder("<c:tx><c:rich><a:bodyPr/><a:lstStyle/>");
        for (String line : t.text().split("\r\n|\r|\n", -1)) {
            b.append("<a:p><a:pPr><a:defRPr ").append(rpr).append("/></a:pPr><a:r><a:rPr lang=\"en-US\" ")
                    .append(rpr).append("/><a:t>").append(Xml.text(line)).append("</a:t></a:r></a:p>");
        }
        return b.append("</c:rich></c:tx>").toString();
    }

    private static void group(StringBuilder b, BiffChart.Group g) {
        String grouping = g.percent() ? "percentStacked" : g.stacked() ? "stacked" : null;
        String tag = switch (g.kind()) {
            case BAR -> "barChart";
            case LINE -> "lineChart";
            case AREA -> "areaChart";
            case PIE -> g.donut() > 0 ? "doughnutChart" : "pieChart";
            case SCATTER -> "scatterChart";
            case RADAR -> "radarChart";
        };
        b.append("<c:").append(tag).append('>');
        switch (g.kind()) {
            case BAR -> b.append("<c:barDir val=\"").append(g.horizontal() ? "bar" : "col")
                    .append("\"/><c:grouping val=\"").append(grouping == null ? "clustered" : grouping)
                    .append("\"/>");
            case LINE, AREA -> b.append("<c:grouping val=\"").append(grouping == null ? "standard" : grouping)
                    .append("\"/>");
            case SCATTER -> b.append("<c:scatterStyle val=\"lineMarker\"/>");
            case RADAR -> b.append("<c:radarStyle val=\"marker\"/>");
            default -> {
            }
        }
        b.append("<c:varyColors val=\"").append(g.vary() ? 1 : 0).append("\"/>");
        int index = 0;
        for (BiffChart.Series s : g.series()) {
            series(b, g.kind(), s, index++);
        }
        switch (g.kind()) {
            case BAR -> b.append("<c:gapWidth val=\"").append(Math.max(0, Math.min(500, g.gap())))
                    .append("\"/><c:overlap val=\"").append(Math.max(-100, Math.min(100, -g.overlap())))
                    .append("\"/>");
            case LINE -> b.append("<c:marker val=\"1\"/>");
            case PIE -> {
                b.append("<c:firstSliceAng val=\"").append(Math.floorMod(g.firstSlice(), 360)).append("\"/>");
                if (g.donut() > 0) {
                    b.append("<c:holeSize val=\"").append(Math.max(10, Math.min(90, g.donut()))).append("\"/>");
                }
            }
            default -> {
            }
        }
        if (g.kind() != BiffChart.Kind.PIE) {
            b.append("<c:axId val=\"").append(100 + g.axisGroup()).append("\"/><c:axId val=\"")
                    .append(200 + g.axisGroup()).append("\"/>");
        }
        b.append("</c:").append(tag).append('>');
    }

    private static void series(StringBuilder b, BiffChart.Kind kind, BiffChart.Series s, int index) {
        boolean lines = kind == BiffChart.Kind.LINE || kind == BiffChart.Kind.SCATTER
                || kind == BiffChart.Kind.RADAR;
        b.append("<c:ser><c:idx val=\"").append(index).append("\"/><c:order val=\"").append(index)
                .append("\"/><c:tx><c:v>").append(Xml.text(s.name())).append("</c:v></c:tx><c:spPr>");
        if (lines) {
            b.append("<a:noFill/>");
        } else {
            b.append(fill(s.fill()));
        }
        b.append(line(s.line(), s.lineWidth())).append("</c:spPr>");
        if (lines) {
            String symbol = s.marker() == null ? "none" : s.marker();
            b.append("<c:marker><c:symbol val=\"").append(symbol).append("\"/>");
            if (!symbol.equals("none")) {
                b.append("<c:size val=\"5\"/><c:spPr>").append(fill(s.markerFill())).append(line(s.markerFill(),
                        0.75f)).append("</c:spPr>");
            }
            b.append("</c:marker>");
        }
        for (Map.Entry<Integer, Integer> p : s.points().entrySet()) {
            b.append("<c:dPt><c:idx val=\"").append(p.getKey()).append("\"/><c:spPr>").append(fill(p.getValue()))
                    .append("</c:spPr></c:dPt>");
        }
        if (kind == BiffChart.Kind.SCATTER) {
            b.append("<c:xVal>").append(numbers(s.xs() == null ? sequence(s.values().length) : s.xs()))
                    .append("</c:xVal><c:yVal>").append(numbers(s.values())).append("</c:yVal>");
        } else {
            b.append("<c:cat>").append(strings(s.categories())).append("</c:cat><c:val>").append(numbers(s.values()))
                    .append("</c:val>");
        }
        if (lines) {
            b.append("<c:smooth val=\"").append(s.smooth() ? 1 : 0).append("\"/>");
        }
        b.append("</c:ser>");
    }

    private static double[] sequence(int n) {
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            v[i] = i + 1;
        }
        return v;
    }

    private static String strings(List<String> values) {
        StringBuilder b = new StringBuilder("<c:strLit><c:ptCount val=\"").append(values.size()).append("\"/>");
        for (int i = 0; i < values.size(); i++) {
            b.append("<c:pt idx=\"").append(i).append("\"><c:v>").append(Xml.text(values.get(i)))
                    .append("</c:v></c:pt>");
        }
        return b.append("</c:strLit>").toString();
    }

    private static String numbers(double[] values) {
        StringBuilder b = new StringBuilder("<c:numLit><c:formatCode>General</c:formatCode><c:ptCount val=\"")
                .append(values.length).append("\"/>");
        for (int i = 0; i < values.length; i++) {
            if (Double.isFinite(values[i])) {
                b.append("<c:pt idx=\"").append(i).append("\"><c:v>").append(values[i]).append("</c:v></c:pt>");
            }
        }
        return b.append("</c:numLit>").toString();
    }

    private static void axes(StringBuilder b, BiffChart chart, int group, boolean horizontal, boolean scatter) {
        BiffChart.Axis cat = chart.categories()[group];
        BiffChart.Axis val = chart.values()[group];
        boolean secondary = group == 1;
        String catPos = horizontal ? "l" : "b";
        String valPos = secondary ? (horizontal ? "t" : "r") : horizontal ? "b" : "l";
        boolean catDeleted = secondary && cat == null || cat != null && cat.deleted();
        axis(b, scatter ? "valAx" : "catAx", 100 + group, 200 + group, catPos, cat, catDeleted, null, false);
        axis(b, "valAx", 200 + group, 100 + group, valPos, val, val == null || val.deleted(),
                val == null ? null : val.format(), secondary);
    }

    private static void axis(StringBuilder b, String tag, int id, int cross, String pos, BiffChart.Axis a,
            boolean deleted, String format, boolean crossMax) {
        b.append("<c:").append(tag).append("><c:axId val=\"").append(id).append("\"/><c:scaling>");
        if (a != null && a.log()) {
            b.append("<c:logBase val=\"10\"/>");
        }
        b.append("<c:orientation val=\"").append(a != null && a.reversed() ? "maxMin" : "minMax").append("\"/>");
        if (a != null && a.max() != null) {
            b.append("<c:max val=\"").append(a.max()).append("\"/>");
        }
        if (a != null && a.min() != null) {
            b.append("<c:min val=\"").append(a.min()).append("\"/>");
        }
        b.append("</c:scaling><c:delete val=\"").append(deleted ? 1 : 0).append("\"/><c:axPos val=\"").append(pos)
                .append("\"/>");
        if (a != null && a.gridlines()) {
            b.append("<c:majorGridlines><c:spPr><a:ln w=\"3175\"><a:solidFill><a:srgbClr val=\"000000\"/>")
                    .append("</a:solidFill></a:ln></c:spPr></c:majorGridlines>");
        }
        if (a != null && a.title() != null) {
            b.append("<c:title>").append(rich(a.title())).append("<c:overlay val=\"0\"/></c:title>");
        }
        b.append("<c:numFmt formatCode=\"").append(Xml.attr(format == null ? "General" : format))
                .append("\" sourceLinked=\"0\"/><c:majorTickMark val=\"out\"/><c:minorTickMark val=\"none\"/>")
                .append("<c:tickLblPos val=\"nextTo\"/><c:spPr><a:ln w=\"9525\"><a:solidFill><a:srgbClr val=\"")
                .append("000000\"/></a:solidFill></a:ln></c:spPr><c:crossAx val=\"").append(cross)
                .append("\"/><c:crosses val=\"").append(crossMax ? "max" : "autoZero").append("\"/>");
        if (tag.equals("valAx")) {
            b.append("<c:crossBetween val=\"between\"/>");
            if (a != null && a.major() != null) {
                b.append("<c:majorUnit val=\"").append(a.major()).append("\"/>");
            }
        }
        b.append("</c:").append(tag).append('>');
    }

    private static void legend(StringBuilder b, BiffChart.Legend l) {
        b.append("<c:legend><c:legendPos val=\"").append(l.position()).append("\"/>");
        double[] box = l.box();
        if (box != null) {
            b.append("<c:layout><c:manualLayout><c:xMode val=\"edge\"/><c:yMode val=\"edge\"/><c:x val=\"")
                    .append(clamp(box[0])).append("\"/><c:y val=\"").append(clamp(box[1])).append("\"/><c:w val=\"")
                    .append(clamp(box[2])).append("\"/><c:h val=\"").append(clamp(box[3]))
                    .append("\"/></c:manualLayout></c:layout>");
        }
        b.append("<c:overlay val=\"0\"/><c:spPr><a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill>")
                .append("<a:ln w=\"9525\"><a:solidFill><a:srgbClr val=\"000000\"/></a:solidFill></a:ln></c:spPr>")
                .append("</c:legend>");
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static String fill(Integer rgb) {
        return rgb == null ? "<a:noFill/>" : "<a:solidFill><a:srgbClr val=\"" + hex(rgb) + "\"/></a:solidFill>";
    }

    private static String line(Integer rgb, float width) {
        return rgb == null ? "<a:ln><a:noFill/></a:ln>" : "<a:ln w=\"" + Math.round(width * 12700)
                + "\"><a:solidFill><a:srgbClr val=\"" + hex(rgb) + "\"/></a:solidFill></a:ln>";
    }

    private static String hex(int rgb) {
        return String.format("%06X", rgb & 0xFFFFFF);
    }
}
