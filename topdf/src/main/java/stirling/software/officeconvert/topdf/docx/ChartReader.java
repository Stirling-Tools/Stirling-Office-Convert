package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import stirling.software.officeconvert.topdf.io.Relationship;
import stirling.software.officeconvert.topdf.pdf.Stroke;

final class ChartReader {

    static final Color TEXT = new Color(0x595959);

    static final Color GRID = new Color(0xD9D9D9);

    // Office's automatic colour for axis lines and gridlines that carry no formatting of their own
    static final Color AUTO_LINE = new Color(0x868686);

    private static final int MAX_POINTS = 4096;

    private static final int MAX_SERIES = 256;

    private final Theme theme;

    private final Color defaultFill;

    private final Color defaultBorder;

    private ChartReader(Theme theme, Color defaultFill, Color defaultBorder) {
        this.theme = theme;
        this.defaultFill = defaultFill;
        this.defaultBorder = defaultBorder;
    }

    static Chart read(DocxPackage pkg, String part, XEl graphicData) {
        XEl ref = graphicData.child("c:chart");
        if (ref == null) {
            return null;
        }
        Relationship r = pkg.relationship(part, ref.attr("r:id"));
        String key = r == null || r.part() == null ? null : r.part();
        List<Chart> known = key == null ? null : pkg.charts.get(key);
        if (known != null) {
            return known.isEmpty() ? null : known.get(0);
        }
        Chart chart = null;
        try {
            XEl root = pkg.partXml(r);
            // Word frames a chart area that has no formatting of its own in the automatic line colour
            chart = root == null || !root.is("c:chartSpace") ? null
                    : new ChartReader(pkg.theme, Color.WHITE, AUTO_LINE).chart(root);
        } catch (IOException | RuntimeException e) {
            pkg.job.warn("A chart could not be read: " + e.getMessage());
        }
        if (key != null) {
            pkg.charts.put(key, chart == null ? List.of() : List.of(chart));
        }
        return chart;
    }

    static Chart read(XEl root, Theme theme) {
        return root == null || !root.is("c:chartSpace") ? null : new ChartReader(theme, Color.WHITE, GRID).chart(root);
    }

    private Stroke defaultBorder() {
        return defaultBorder == null ? null : Stroke.solid(0.75f, defaultBorder);
    }

    // PowerPoint leaves a chart area without formatting of its own unfilled and unframed on the slide
    static Chart readSlide(XEl root, Theme theme) {
        return root == null || !root.is("c:chartSpace") ? null : new ChartReader(theme, null, null).chart(root);
    }

    private Chart chart(XEl space) {
        XEl chart = space.child("c:chart");
        if (chart == null) {
            return null;
        }
        float textSize = size(space.child("c:txPr"), 10);
        String font = font(space.child("c:txPr"));
        XEl plot = chart.child("c:plotArea");
        List<Chart.Group> groups = new ArrayList<>();
        List<String> categories = new ArrayList<>();
        Chart.Axis values = null;
        Chart.Axis cats = null;
        Chart.Layout plotLayout = null;
        Color plotFill = null;
        Stroke plotBorder = null;
        date1904 = space.child("c:date1904") != null && Ooxml.flag(space.child("c:date1904").val(), true);
        if (plot != null) {
            List<XEl> valAxes = plot.children("c:valAx");
            XEl valAx = valAxes.isEmpty() ? null : valAxes.get(0);
            XEl catAx = plot.child("c:catAx");
            if (catAx == null) {
                catAx = plot.child("c:dateAx");
            }
            boolean scatter = false;
            if (catAx == null && valAxes.size() > 1) {
                // a scatter chart has two value axes; x runs along the bottom or top
                scatter = true;
                catAx = valAxes.get(0);
                valAx = valAxes.get(1);
                if (vertical(catAx) && !vertical(valAx)) {
                    catAx = valAxes.get(1);
                    valAx = valAxes.get(0);
                }
            }
            XEl second = null;
            String primaryId = null;
            for (XEl k : plot.kids) {
                Chart.Group g = group(k, categories);
                if (g == null) {
                    continue;
                }
                XEl own = scatter ? null : valueAxisOf(k, valAxes);
                if (own != null && primaryId == null) {
                    primaryId = axisId(own);
                    valAx = own;
                } else if (own != null && !axisId(own).equals(primaryId)) {
                    second = own;
                    g = g.onSecondary();
                }
                groups.add(g);
            }
            boolean midCat = valAx != null && valAx.child("c:crossBetween") != null
                    && "midCat".equals(valAx.child("c:crossBetween").val());
            values = axis(valAx, textSize, false);
            cats = axis(catAx, textSize, midCat);
            secondary = second == null ? null : axis(second, textSize, false);
            plotLayout = layout(plot.child("c:layout"));
            XEl pp = plot.child("c:spPr");
            if (pp != null) {
                plotFill = pp.child("a:noFill") != null ? null : fill(pp);
                plotBorder = line(pp);
            }
        }
        Chart.Text title = title(chart, groups);
        Chart.Legend legend = null;
        XEl lg = chart.child("c:legend");
        if (lg != null) {
            XEl pos = lg.child("c:legendPos");
            legend = new Chart.Legend(pos == null ? "r" : pos.attr("val", "r"), size(lg.child("c:txPr"), textSize),
                    layout(lg.child("c:layout")), ownFont(lg.child("c:txPr")));
        }
        XEl spPr = space.child("c:spPr");
        Color fill = spPr == null ? defaultFill : fill(spPr);
        Stroke border = spPr == null ? defaultBorder() : line(spPr);
        return new Chart(title, groups, categories, legend, values, cats, fill, border, font, textSize,
                color(space.child("c:txPr"), Color.BLACK), plotLayout, plotFill, plotBorder, date1904, secondary);
    }

    private Chart.Axis secondary;

    private static boolean vertical(XEl ax) {
        XEl pos = ax.child("c:axPos");
        String v = pos == null ? "l" : pos.attr("val", "l");
        return v.equals("l") || v.equals("r");
    }

    private static String axisId(XEl ax) {
        XEl id = ax.child("c:axId");
        return id == null ? "" : String.valueOf(id.val());
    }

    private static XEl valueAxisOf(XEl group, List<XEl> valAxes) {
        for (XEl id : group.children("c:axId")) {
            for (XEl ax : valAxes) {
                if (axisId(ax).equals(String.valueOf(id.val()))) {
                    return ax;
                }
            }
        }
        return null;
    }

    private boolean date1904;

    static Chart.Layout layout(XEl layout) {
        XEl m = layout == null ? null : layout.child("c:manualLayout");
        if (m == null) {
            return null;
        }
        Double x = decimal(m.child("c:x"));
        Double y = decimal(m.child("c:y"));
        Double w = decimal(m.child("c:w"));
        Double h = decimal(m.child("c:h"));
        if (x == null || y == null || w == null || h == null) {
            return null;
        }
        boolean inner = m.child("c:layoutTarget") != null && "inner".equals(m.child("c:layoutTarget").val());
        boolean xEdge = m.child("c:xMode") != null && "edge".equals(m.child("c:xMode").val());
        boolean yEdge = m.child("c:yMode") != null && "edge".equals(m.child("c:yMode").val());
        boolean wEdge = m.child("c:wMode") != null && "edge".equals(m.child("c:wMode").val());
        boolean hEdge = m.child("c:hMode") != null && "edge".equals(m.child("c:hMode").val());
        double ww = wEdge ? w - x : w;
        double hh = hEdge ? h - y : h;
        if (!(ww > 0.01 && hh > 0.01 && ww <= 1.5 && hh <= 1.5 && Math.abs(x) <= 1.5 && Math.abs(y) <= 1.5)) {
            return null;
        }
        return new Chart.Layout(inner, xEdge, yEdge, x.floatValue(), y.floatValue(), (float) ww, (float) hh);
    }

    private Chart.Text title(XEl chart, List<Chart.Group> groups) {
        XEl t = chart.child("c:title");
        if (t == null) {
            return null;
        }
        return titleText(t, 14, groups);
    }

    private Chart.Text titleText(XEl t, float defaultSize, List<Chart.Group> groups) {
        float size = size(t.child("c:txPr"), defaultSize);
        Color color = color(t.child("c:txPr"), Color.BLACK);
        boolean bold = bold(t.child("c:txPr"), false);
        XEl rich = t.path("c:tx", "c:rich");
        String text = null;
        if (rich != null) {
            StringBuilder sb = new StringBuilder();
            for (XEl p : rich.children("a:p")) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                XEl ppr = p.path("a:pPr", "a:defRPr");
                if (ppr != null) {
                    size = sz(ppr, size);
                    bold = Ooxml.flag(ppr.attr("b"), bold);
                    Color c = Colors.drawing(ppr.child("a:solidFill"), theme, null);
                    color = c == null ? color : c;
                }
                for (XEl r : p.kids) {
                    if (r.is("a:r") || r.is("a:fld")) {
                        XEl rpr = r.child("a:rPr");
                        if (rpr != null) {
                            size = sz(rpr, size);
                        }
                        XEl tt = r.child("a:t");
                        if (tt != null) {
                            sb.append(tt.text());
                        }
                    }
                }
            }
            text = sb.toString();
        } else {
            XEl ref = t.path("c:tx", "c:strRef");
            if (ref != null) {
                text = String.join(" ", strings(ref.child("c:strCache")));
            }
        }
        if ((text == null || text.isBlank()) && groups == null) {
            return null;
        }
        if (text == null || text.isBlank()) {
            int series = 0;
            String name = null;
            for (Chart.Group g : groups) {
                series += g.series().size();
                if (!g.series().isEmpty()) {
                    name = g.series().get(0).name();
                }
            }
            text = series == 1 && name != null ? name : "Chart Title";
        }
        return new Chart.Text(text, size, bold, color);
    }

    private Chart.Group group(XEl g, List<String> categories) {
        Chart.Kind kind = switch (g.name) {
            case "c:barChart", "c:bar3DChart" -> Chart.Kind.BAR;
            case "c:lineChart", "c:line3DChart", "c:stockChart", "c:radarChart" -> Chart.Kind.LINE;
            case "c:areaChart", "c:area3DChart" -> Chart.Kind.AREA;
            case "c:pieChart", "c:pie3DChart", "c:ofPieChart" -> Chart.Kind.PIE;
            case "c:doughnutChart" -> Chart.Kind.DOUGHNUT;
            case "c:scatterChart", "c:bubbleChart" -> Chart.Kind.SCATTER;
            default -> null;
        };
        if (kind == null) {
            return null;
        }
        boolean horizontal = kind == Chart.Kind.BAR && g.child("c:barDir") != null
                && "bar".equals(g.child("c:barDir").val());
        String grouping = g.child("c:grouping") == null ? "clustered" : g.child("c:grouping").attr("val", "clustered");
        boolean vary = g.child("c:varyColors") != null && Ooxml.flag(g.child("c:varyColors").val(), true);
        List<Chart.Series> series = new ArrayList<>();
        int index = 0;
        boolean autoMarkers = g.is("c:lineChart") && g.child("c:marker") != null
                && Ooxml.flag(g.child("c:marker").val(), true)
                || g.is("c:scatterChart") && g.child("c:scatterStyle") != null
                && String.valueOf(g.child("c:scatterStyle").val()).contains("arker");
        for (XEl s : g.children("c:ser")) {
            if (series.size() >= MAX_SERIES) {
                break;
            }
            XEl idx = s.child("c:idx");
            int colorIndex = idx == null ? index : Ooxml.integer(idx.val(), index);
            series.add(series(s, kind, colorIndex, categories, autoMarkers));
            index++;
        }
        float gap = number(g.child("c:gapWidth"), 150);
        boolean stacked = grouping.equals("stacked") || grouping.equals("percentStacked");
        float overlap = number(g.child("c:overlap"), stacked ? 100 : 0);
        float hole = number(g.child("c:holeSize"), 50);
        float first = number(g.child("c:firstSliceAng"), 0);
        return new Chart.Group(kind, horizontal, grouping, series, gap, overlap, hole, first, vary,
                labels(g.child("c:dLbls")));
    }

    private Chart.Series series(XEl s, Chart.Kind kind, int colorIndex, List<String> categories,
            boolean autoMarkers) {
        String name = null;
        XEl tx = s.child("c:tx");
        if (tx != null) {
            XEl ref = tx.child("c:strRef");
            if (ref != null) {
                name = String.join(" ", strings(ref.child("c:strCache")));
            } else if (tx.child("c:v") != null) {
                name = tx.child("c:v").text();
            }
        }
        XEl spPr = s.child("c:spPr");
        Color accent = accent(colorIndex);
        Color fill = accent;
        Stroke line = null;
        if (spPr != null) {
            Color f = fill(spPr);
            if (spPr.child("a:noFill") != null) {
                fill = null;
            } else if (f != null) {
                fill = f;
            }
            line = line(spPr);
        }
        if (kind == Chart.Kind.LINE || kind == Chart.Kind.SCATTER) {
            if (line == null && (spPr == null || spPr.path("a:ln", "a:noFill") == null)) {
                line = Stroke.solid(2.25f, accent);
            }
            fill = line == null ? fill : line.color();
        }
        boolean markers = false;
        XEl marker = s.path("c:marker", "c:symbol");
        String symbol = null;
        if (marker != null) {
            markers = !"none".equals(marker.val());
            symbol = marker.val();
        } else if (autoMarkers && (kind == Chart.Kind.LINE || kind == Chart.Kind.SCATTER)) {
            markers = true;
            symbol = AUTO_MARKERS[Math.floorMod(colorIndex, AUTO_MARKERS.length)];
        }
        Chart.Marker mk = null;
        if (markers) {
            XEl m = s.child("c:marker");
            float msize = m == null || m.child("c:size") == null ? 5
                    : Math.max(2, Math.min(72, Ooxml.integer(m.child("c:size").val(), 5)));
            Color mfill = fill;
            Color mline = fill;
            XEl mp = m == null ? null : m.child("c:spPr");
            if (mp != null) {
                Color f = fill(mp);
                mfill = mp.child("a:noFill") != null ? null : f == null ? mfill : f;
                Stroke ml = line(mp);
                mline = mp.path("a:ln", "a:noFill") != null ? null : ml == null ? mline : ml.color();
            }
            mk = new Chart.Marker(symbol == null || symbol.equals("auto") ? "circle" : symbol, msize, mfill, mline);
        }
        boolean smooth = s.child("c:smooth") != null && Ooxml.flag(s.child("c:smooth").val(), true);
        Chart.Labels own = s.child("c:dLbls") == null ? null : labels(s.child("c:dLbls"));
        Stroke outline = kind == Chart.Kind.BAR ? line : null;
        XEl val = s.child(kind == Chart.Kind.SCATTER ? "c:yVal" : "c:val");
        double[] values = numbers(val);
        double[] xs = kind == Chart.Kind.SCATTER ? numbers(s.child("c:xVal")) : null;
        if (categories.isEmpty()) {
            XEl cat = s.child("c:cat");
            if (cat != null) {
                categories.addAll(categoryStrings(cat));
            }
        }
        List<Chart.Point> points = new ArrayList<>();
        for (XEl dpt : s.children("c:dPt")) {
            int i = Ooxml.integer(dpt.child("c:idx") == null ? null : dpt.child("c:idx").val(), -1);
            if (i < 0 || i >= MAX_POINTS) {
                continue;
            }
            while (points.size() <= i) {
                points.add(null);
            }
            XEl p = dpt.child("c:spPr");
            points.set(i, new Chart.Point(p == null ? null : fill(p)));
        }
        return new Chart.Series(name, values, xs, fill, kind == Chart.Kind.BAR ? null : line, markers, points,
                smooth, mk, own, outline, spPr == null ? null : shadow(spPr.path("a:effectLst", "a:outerShdw")));
    }

    private Chart.Shadow shadow(XEl s) {
        if (s == null) {
            return null;
        }
        Color c = Colors.drawing(s, theme, null);
        if (c == null) {
            return null;
        }
        float dist = Ooxml.emu(s.attr("dist"), 0);
        double dir = Math.toRadians(Ooxml.integer(s.attr("dir"), 0) / 60000.0);
        float blur = Ooxml.emu(s.attr("blurRad"), 0);
        return new Chart.Shadow((float) (dist * Math.cos(dir)), (float) (dist * Math.sin(dir)), Math.min(20, blur), c);
    }

    private static final String[] AUTO_MARKERS = {"diamond", "square", "triangle", "x", "star", "circle", "plus",
        "dash"};

    private List<String> categoryStrings(XEl cat) {
        XEl str = cat.child("c:strRef");
        if (str != null) {
            return strings(str.child("c:strCache"));
        }
        XEl num = cat.child("c:numRef");
        if (num != null) {
            XEl cache = num.child("c:numCache");
            List<String> out = new ArrayList<>();
            String format = cache == null || cache.child("c:formatCode") == null ? "General"
                    : cache.child("c:formatCode").text();
            for (double d : points(cache)) {
                out.add(Double.isNaN(d) ? "" : ChartPainter.format(d, format, date1904));
            }
            return out;
        }
        XEl multi = cat.child("c:multiLvlStrRef");
        if (multi != null) {
            XEl cache = multi.child("c:multiLvlStrCache");
            if (cache != null && !cache.children("c:lvl").isEmpty()) {
                return strings(cache.children("c:lvl").get(0));
            }
        }
        XEl lit = cat.child("c:strLit");
        return lit == null ? List.of() : strings(lit);
    }

    private static List<String> strings(XEl cache) {
        if (cache == null) {
            return new ArrayList<>();
        }
        TreeMap<Integer, String> map = new TreeMap<>();
        int count = Math.min(MAX_POINTS, Ooxml.integer(cache.child("c:ptCount") == null ? null
                : cache.child("c:ptCount").val(), 0));
        for (XEl pt : cache.children("c:pt")) {
            int i = Ooxml.integer(pt.attr("idx"), -1);
            XEl v = pt.child("c:v");
            if (i >= 0 && i < MAX_POINTS && v != null) {
                map.put(i, v.text());
                count = Math.max(count, i + 1);
            }
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(map.getOrDefault(i, ""));
        }
        return out;
    }

    private static double[] numbers(XEl holder) {
        if (holder == null) {
            return new double[0];
        }
        XEl ref = holder.child("c:numRef");
        XEl cache = ref != null ? ref.child("c:numCache") : holder.child("c:numLit");
        return points(cache);
    }

    private static double[] points(XEl cache) {
        if (cache == null) {
            return new double[0];
        }
        int count = Math.min(MAX_POINTS, Ooxml.integer(cache.child("c:ptCount") == null ? null
                : cache.child("c:ptCount").val(), 0));
        TreeMap<Integer, Double> map = new TreeMap<>();
        for (XEl pt : cache.children("c:pt")) {
            int i = Ooxml.integer(pt.attr("idx"), -1);
            XEl v = pt.child("c:v");
            if (i < 0 || i >= MAX_POINTS || v == null) {
                continue;
            }
            try {
                double d = Double.parseDouble(v.text().strip());
                if (Double.isFinite(d)) {
                    map.put(i, d);
                    count = Math.max(count, i + 1);
                }
            } catch (NumberFormatException e) {
                continue;
            }
        }
        double[] out = new double[count];
        for (int i = 0; i < count; i++) {
            out[i] = map.getOrDefault(i, Double.NaN);
        }
        return out;
    }

    private Chart.Axis axis(XEl ax, float textSize, boolean midCat) {
        if (ax == null) {
            return null;
        }
        boolean deleted = ax.child("c:delete") != null && Ooxml.flag(ax.child("c:delete").val(), true);
        XEl grid = ax.child("c:majorGridlines");
        Color gridColor = AUTO_LINE;
        if (grid != null && grid.child("c:spPr") != null && grid.child("c:spPr").child("a:ln") != null) {
            Stroke s = line(grid.child("c:spPr"));
            gridColor = s == null ? null : s.color();
        }
        XEl spPr = ax.child("c:spPr");
        Color lineColor = AUTO_LINE;
        if (spPr != null && spPr.child("a:ln") != null) {
            Stroke s = line(spPr);
            lineColor = s == null ? null : s.color();
        }
        XEl scaling = ax.child("c:scaling");
        Double min = null;
        Double max = null;
        boolean reversed = false;
        if (scaling != null) {
            min = decimal(scaling.child("c:min"));
            max = decimal(scaling.child("c:max"));
            reversed = scaling.child("c:orientation") != null
                    && "maxMin".equals(scaling.child("c:orientation").val());
        }
        XEl fmt = ax.child("c:numFmt");
        String format = fmt == null ? "General" : fmt.attr("formatCode", "General");
        float gridWidth = 0.75f;
        if (grid != null && grid.child("c:spPr") != null && line(grid.child("c:spPr")) != null) {
            gridWidth = line(grid.child("c:spPr")).width();
        }
        Double unit = decimal(ax.child("c:majorUnit"));
        if (unit != null && unit <= 0) {
            unit = null;
        }
        XEl t = ax.child("c:title");
        Chart.Text title = t == null ? null : titleText(t, 10, null);
        Float rotation = null;
        XEl body = ax.path("c:txPr", "a:bodyPr");
        if (body != null) {
            String vert = body.attr("vert");
            Integer rot = Ooxml.integer(body.attr("rot"));
            if ("vert270".equals(vert)) {
                rotation = -90f;
            } else if ("vert".equals(vert) || "eaVert".equals(vert)) {
                rotation = 90f;
            } else if (rot != null && rot > -5400001 && rot < 5400001) {
                rotation = rot / 60000f;
            }
        }
        XEl skipEl = ax.child("c:tickLblSkip");
        int skip = skipEl == null ? 0 : Math.max(0, Ooxml.integer(skipEl.val(), 0));
        String lblPos = ax.child("c:tickLblPos") == null ? "nextTo" : ax.child("c:tickLblPos").attr("val", "nextTo");
        String tick = ax.child("c:majorTickMark") == null ? "cross" : ax.child("c:majorTickMark").attr("val", "cross");
        return new Chart.Axis(deleted, grid != null, gridColor, lineColor, min, max, format,
                size(ax.child("c:txPr"), textSize), reversed, unit, title, rotation, skip, ax.is("c:dateAx"),
                lblPos, midCat, gridWidth, tick, ownFont(ax.child("c:txPr")));
    }

    private Chart.Labels labels(XEl d) {
        if (d == null || d.child("c:delete") != null && Ooxml.flag(d.child("c:delete").val(), true)) {
            return new Chart.Labels(false, false, false);
        }
        String pos = d.child("c:dLblPos") == null ? null : d.child("c:dLblPos").val();
        String fmt = d.child("c:numFmt") == null ? null : d.child("c:numFmt").attr("formatCode");
        return new Chart.Labels(on(d, "c:showVal"), on(d, "c:showPercent"), on(d, "c:showCatName"),
                on(d, "c:showSerName"), pos, fmt);
    }

    private static boolean on(XEl d, String name) {
        XEl e = d.child(name);
        return e != null && Ooxml.flag(e.val(), true);
    }

    private static Double decimal(XEl e) {
        if (e == null || e.val() == null) {
            return null;
        }
        try {
            double d = Double.parseDouble(e.val());
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static float number(XEl e, float fallback) {
        return e == null ? fallback : Ooxml.integer(e.val(), Math.round(fallback));
    }

    private Color accent(int index) {
        int i = Math.floorMod(index, 6);
        Color c = theme.schemeColor("accent" + (i + 1));
        if (c == null) {
            c = TEXT;
        }
        int round = Math.floorMod(index, 18) / 6;
        if (round == 1) {
            return scale(c, 0.6f);
        } else if (round == 2) {
            return new Color(c.getRed() + (255 - c.getRed()) * 2 / 5, c.getGreen() + (255 - c.getGreen()) * 2 / 5,
                    c.getBlue() + (255 - c.getBlue()) * 2 / 5);
        }
        return c;
    }

    private static Color scale(Color c, float f) {
        return new Color(Math.round(c.getRed() * f), Math.round(c.getGreen() * f), Math.round(c.getBlue() * f));
    }

    private Color fill(XEl spPr) {
        XEl solid = spPr.child("a:solidFill");
        if (solid != null) {
            return Colors.drawing(solid, theme, null);
        }
        XEl grad = spPr.child("a:gradFill");
        if (grad != null && grad.child("a:gsLst") != null && !grad.child("a:gsLst").children("a:gs").isEmpty()) {
            return Colors.drawing(grad.child("a:gsLst").children("a:gs").get(0), theme, null);
        }
        return null;
    }

    private Stroke line(XEl spPr) {
        XEl ln = spPr.child("a:ln");
        if (ln == null || ln.child("a:noFill") != null) {
            return null;
        }
        Color c = Colors.drawing(ln.child("a:solidFill"), theme, null);
        if (c == null) {
            return null;
        }
        float w = ln.attr("w") != null ? Ooxml.emu(ln.attr("w"), 0.75f) : 0.75f;
        return Stroke.solid(Math.max(0.25f, Math.min(20, w)), c);
    }

    private static float size(XEl txPr, float fallback) {
        if (txPr == null) {
            return fallback;
        }
        for (XEl p : txPr.children("a:p")) {
            XEl d = p.path("a:pPr", "a:defRPr");
            if (d != null && d.attr("sz") != null) {
                return sz(d, fallback);
            }
        }
        return fallback;
    }

    private static float sz(XEl rpr, float fallback) {
        int v = Ooxml.integer(rpr.attr("sz"), -1);
        return v >= 100 && v <= 400000 ? v / 100f : fallback;
    }

    private static boolean bold(XEl txPr, boolean fallback) {
        if (txPr == null) {
            return fallback;
        }
        for (XEl p : txPr.children("a:p")) {
            XEl d = p.path("a:pPr", "a:defRPr");
            if (d != null && d.attr("b") != null) {
                return Ooxml.flag(d.attr("b"), fallback);
            }
        }
        return fallback;
    }

    private Color color(XEl txPr, Color fallback) {
        if (txPr == null) {
            return fallback;
        }
        for (XEl p : txPr.children("a:p")) {
            XEl d = p.path("a:pPr", "a:defRPr");
            if (d != null && d.child("a:solidFill") != null) {
                Color c = Colors.drawing(d.child("a:solidFill"), theme, null);
                return c == null ? fallback : c;
            }
        }
        return fallback;
    }

    private String font(XEl txPr) {
        String face = ownFont(txPr);
        return face == null ? theme.font("minorHAnsi") : face;
    }

    // The typeface an element's own text properties set, or null when it inherits the chart's
    private String ownFont(XEl txPr) {
        String face = null;
        if (txPr != null) {
            for (XEl p : txPr.children("a:p")) {
                XEl latin = p.path("a:pPr", "a:defRPr", "a:latin");
                if (latin != null) {
                    face = latin.attr("typeface");
                    break;
                }
            }
        }
        if (face == null || face.isBlank()) {
            return null;
        }
        if (face.equals("+mn-lt")) {
            return theme.font("minorHAnsi");
        }
        return face.equals("+mj-lt") ? theme.font("majorHAnsi") : face;
    }
}
