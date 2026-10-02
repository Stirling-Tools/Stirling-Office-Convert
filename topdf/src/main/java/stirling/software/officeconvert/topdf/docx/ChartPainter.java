package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontMetrics;
import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.Stroke;
import stirling.software.officeconvert.topdf.pdf.TextStyle;

final class ChartPainter {

    private static final float PAD_TOP = 7.55f;

    private static final float PAD_LEFT = 6.52f;

    private static final float PAD_RIGHT = 11f;

    private static final float PAD_BOTTOM = 9.41f;

    // Below axis labels that end the chart, and above a plot area that has no title over it
    private static final float PAD_BELOW_LABELS = 6.6f;

    private static final float PAD_NO_TITLE = 11.15f;

    private static final float TITLE_GAP = 12.4f;

    private static final float LEGEND_GAP = 8.96f;

    private static final float PIE_LEGEND_GAP = 13.45f;

    // From the plot area to the keys of a legend set beside it
    private static final float SIDE_LEGEND_GAP = 15.8f;

    private static final float CATEGORY_GAP = 5.64f;

    private static final float VALUE_GAP = 8.29f;

    private static final float AXIS_TITLE_GAP = 6f;

    private static final float KEY_GAP = 2.14f;

    private static final float ENTRY_GAP = 8.9f;

    private static final float TICK = 3.75f;

    private static final float SIN45 = (float) Math.sqrt(0.5);

    private final Chart chart;

    private final Fonts fonts;

    private final Theme theme;

    private final List<Op> ops;

    private ChartPainter(Chart chart, Fonts fonts, Theme theme, List<Op> ops) {
        this.chart = chart;
        this.fonts = fonts;
        this.theme = theme;
        this.ops = ops;
    }

    static void paint(Chart chart, float x, float y, float w, float h, List<Op> ops, Fonts fonts, Theme theme) {
        if (w <= 1 || h <= 1) {
            return;
        }
        new ChartPainter(chart, fonts, theme, ops).paint(x, y, w, h);
    }

    private FontFace face(boolean bold, String own) {
        String family = own != null ? own : chart.font() == null ? "Calibri" : chart.font();
        return fonts.face(family, bold, false);
    }

    private TextStyle style(float size, boolean bold, Color color) {
        return style(size, bold, color, null);
    }

    private TextStyle style(float size, boolean bold, Color color, String font) {
        return TextStyle.of(face(bold, font), size).color(color == null ? ChartReader.TEXT : color);
    }

    // Width as drawn: characters the chart font lacks are measured in the face that shows them
    private float width(TextStyle s, String text) {
        FontFace face = s.face();
        float w = 0;
        int start = 0;
        FontFace current = null;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            FontFace f = face.covers(cp) || Character.isWhitespace(cp) ? face : fonts.fallback(cp, face);
            if (current != null && !f.equals(current)) {
                w += s.face(current).width(text.substring(start, i));
                start = i;
            }
            current = f;
            i += Character.charCount(cp);
        }
        return current == null ? 0 : w + s.face(current).width(text.substring(start));
    }

    private static float ascent(TextStyle s) {
        FontMetrics m = s.face().metrics();
        return m.winAscent() * s.size() / m.unitsPerEm();
    }

    private static float descent(TextStyle s) {
        FontMetrics m = s.face().metrics();
        return m.winDescent() * s.size() / m.unitsPerEm();
    }

    private static float lineHeight(TextStyle s) {
        return ascent(s) + descent(s);
    }

    // Office spaces automatic value gridlines at least about the axis label size plus 7 pt apart
    private static float gridGap(TextStyle s) {
        return s.size() + 7;
    }

    private void paint(float x, float y, float w, float h) {
        if (chart.fill() != null || chart.border() != null) {
            ops.add(new Op.Rect(x, y, w, h, chart.fill() == null ? null : Fill.solid(chart.fill()), chart.border()));
        }
        float top = y + PAD_TOP;
        float bottom = y + h - PAD_BOTTOM;
        float left = x + PAD_LEFT;
        float right = x + w - PAD_RIGHT;
        boolean round = !chart.groups().isEmpty() && (chart.groups().get(0).kind() == Chart.Kind.PIE
                || chart.groups().get(0).kind() == Chart.Kind.DOUGHNUT);
        Chart.Text title = chart.title();
        if (title != null) {
            TextStyle ts = style(title.size(), title.bold(), title.color());
            for (String line : title.text().split("\n")) {
                float baseline = top + ascent(ts);
                center(line, x + w / 2, baseline, ts);
                top += ascent(ts) + descent(ts);
            }
            top += TITLE_GAP;
        } else {
            top = y + (round ? PAD_TOP * 2 : PAD_NO_TITLE);
        }
        List<Entry> entries = entries(round);
        Chart.Legend legend = chart.legend();
        if (legend != null && !entries.isEmpty()) {
            TextStyle ls = style(legend.size(), false, chart.textColor(), legend.font());
            float gap = round ? PIE_LEGEND_GAP : LEGEND_GAP;
            Chart.Layout lay = legend.layout();
            if (lay != null && lay.xEdge() && lay.yEdge()) {
                float lx = x + lay.x() * w;
                float ly = y + lay.y() * h;
                float lw = lay.w() * w;
                float lh = lay.h() * h;
                if (legend.position().equals("t") || legend.position().equals("b")) {
                    legendRow(entries, lx + lw / 2, ly + lh / 2 + (ascent(ls) - descent(ls)) / 2, ls);
                } else {
                    legendColumn(fitting(entries, ls, lw, lh), lx, false, ly + lh / 2, ls, lw);
                }
                switch (legend.position()) {
                    case "t" -> top = Math.max(top, Math.min(ly + lh + gap, top + h / 2));
                    case "l" -> left = Math.max(left, Math.min(lx + lw + gap, left + w / 2));
                    case "r", "tr" -> right = Math.min(right, Math.max(lx - gap, right - w / 2));
                    default -> bottom = Math.min(bottom, Math.max(ly - gap, bottom - h / 2));
                }
            } else {
                switch (legend.position()) {
                    case "t" -> {
                        legendRow(entries, x + w / 2, top + ascent(ls), ls);
                        top += ascent(ls) + descent(ls) + gap;
                    }
                    case "l" -> left += legendColumn(entries, left, false, (top + bottom) / 2, ls, w * 0.3f)
                            + (round ? gap : SIDE_LEGEND_GAP);
                    case "r", "tr" -> right -= legendColumn(entries, right, true, (top + bottom) / 2, ls, w * 0.3f)
                            + (round ? gap : SIDE_LEGEND_GAP);
                    default -> {
                        legendRow(entries, x + w / 2, bottom - descent(ls), ls);
                        bottom -= ascent(ls) + descent(ls) + gap;
                    }
                }
            }
        }
        if (!round && (legend == null || entries.isEmpty() || !legendBelow(legend))) {
            bottom = y + h - PAD_BELOW_LABELS;
        }
        if (chart.groups().isEmpty() || right - left < 4 || bottom - top < 4) {
            return;
        }
        Chart.Layout pl = chart.plotLayout();
        Rectangle2D.Float inner = null;
        if (pl != null) {
            float px = pl.xEdge() ? x + pl.x() * w : left + pl.x() * w;
            float py = pl.yEdge() ? y + pl.y() * h : top + pl.y() * h;
            float pw = pl.w() * w;
            float ph = pl.h() * h;
            if (pw > 4 && ph > 4) {
                if (pl.inner()) {
                    inner = new Rectangle2D.Float(px, py, pw, ph);
                } else {
                    left = px;
                    top = py;
                    right = px + pw;
                    bottom = py + ph;
                }
            }
        }
        if (round) {
            if (inner != null) {
                pie(chart.groups().get(0), (float) inner.getCenterX(), inner.x, inner.y, inner.x + inner.width,
                        inner.y + inner.height);
            } else {
                pie(chart.groups().get(0), (left + right) / 2, left, top, right, bottom);
            }
        } else {
            axes(left, top, right, bottom, inner);
        }
    }

    private static boolean legendBelow(Chart.Legend legend) {
        String p = legend.position();
        return !p.equals("t") && !p.equals("l") && !p.equals("r") && !p.equals("tr");
    }

    private record Entry(String text, Color color, boolean line, Chart.Marker marker, float width) {

        Entry(String text, Color color, boolean line, Chart.Marker marker) {
            this(text, color, line, marker, 2.25f);
        }
    }

    private List<Entry> entries(boolean round) {
        List<Entry> out = new ArrayList<>();
        if (round) {
            Chart.Group g = chart.groups().get(0);
            if (g.series().isEmpty()) {
                return out;
            }
            Chart.Series s = g.series().get(0);
            for (int i = 0; i < s.values().length; i++) {
                String name = i < chart.categories().size() ? chart.categories().get(i) : Integer.toString(i + 1);
                out.add(new Entry(name, pointColor(g, s, i), false, null));
            }
            return out;
        }
        for (Chart.Group g : chart.groups()) {
            for (int i = 0; i < g.series().size(); i++) {
                Chart.Series s = g.series().get(i);
                String name = s.name() == null ? "Series" + (i + 1) : s.name();
                boolean line = g.kind() == Chart.Kind.LINE || g.kind() == Chart.Kind.SCATTER;
                out.add(new Entry(name, line && s.line() == null ? null : s.fill(), line, line ? s.marker() : null,
                        s.line() == null ? 2.25f : Math.min(6, s.line().width())));
            }
        }
        return out;
    }

    private void legendRow(List<Entry> entries, float center, float baseline, TextStyle ls) {
        float key = ls.size() * 0.55f;
        float total = 0;
        for (int i = 0; i < entries.size(); i++) {
            total += keyWidth(entries.get(i), key) + KEY_GAP + width(ls, entries.get(i).text())
                    + (i + 1 < entries.size() ? ENTRY_GAP : 0);
        }
        float at = center - total / 2;
        for (Entry e : entries) {
            key(e, at, baseline - ls.size() * 0.28f, key);
            at += keyWidth(e, key) + KEY_GAP;
            ops.add(new Op.Text(at, baseline, e.text(), ls));
            at += width(ls, e.text()) + ENTRY_GAP;
        }
    }

    // A line series shows a short stretch of its line in the legend, about four square keys long
    private static float keyWidth(Entry e, float key) {
        return e.line() ? key * 3.5f : key;
    }

    // A legend sized by hand shows only the entries that fit in its box, as Office's does
    private List<Entry> fitting(List<Entry> entries, TextStyle ls, float maxWidth, float height) {
        float key = ls.size() * 0.55f;
        float keyW = 0;
        for (Entry e : entries) {
            keyW = Math.max(keyW, keyWidth(e, key));
        }
        float room = Math.max(ls.size() * 4, maxWidth - keyW - KEY_GAP);
        float lh = lineHeight(ls);
        float used = 0;
        for (int i = 0; i < entries.size(); i++) {
            used += wrap(entries.get(i).text(), ls, room).size() * lh + (i > 0 ? 2 : 0);
            if (i > 0 && used > height + 0.5f) {
                return entries.subList(0, i);
            }
        }
        return entries;
    }

    // Entries stacked in a column; text wider than the room left wraps at spaces, as Office's legend does
    private float legendColumn(List<Entry> entries, float edge, boolean atRight, float middle, TextStyle ls,
            float maxWidth) {
        float key = ls.size() * 0.55f;
        float keyW = 0;
        for (Entry e : entries) {
            keyW = Math.max(keyW, keyWidth(e, key));
        }
        float room = Math.max(ls.size() * 4, maxWidth - keyW - KEY_GAP);
        List<List<String>> texts = new ArrayList<>();
        float widest = 0;
        int lines = 0;
        for (Entry e : entries) {
            List<String> l = wrap(e.text(), ls, room);
            texts.add(l);
            lines += l.size();
            for (String t : l) {
                widest = Math.max(widest, keyW + KEY_GAP + width(ls, t));
            }
        }
        float lh = lineHeight(ls);
        float pitch = lh + 2;
        float total = lines * lh + (entries.size() - 1) * 2;
        float y = middle - total / 2 + ascent(ls);
        float x0 = atRight ? edge - widest : edge;
        for (int i = 0; i < entries.size(); i++) {
            List<String> l = texts.get(i);
            key(entries.get(i), x0, y - ls.size() * 0.28f, key);
            for (int k = 0; k < l.size(); k++) {
                ops.add(new Op.Text(x0 + keyW + KEY_GAP, y, l.get(k), ls));
                y += k + 1 < l.size() ? lh : pitch;
            }
        }
        return widest;
    }

    // Office wraps chart text at spaces and after the hyphen of a hyphenated word, never before a digit
    private List<String> wrap(String text, TextStyle s, float room) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n")) {
            if (width(s, para) <= room || para.indexOf(' ') < 0 && para.indexOf('-') <= 0) {
                out.add(para);
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String piece : para.split("(?= )|(?<=[^ -]-)(?!\\d)")) {
                if (line.length() > 0 && width(s, line + piece) > room) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                line.append(line.length() == 0 ? piece.stripLeading() : piece);
            }
            out.add(line.toString());
        }
        return out;
    }

    private void key(Entry e, float x, float cy, float size) {
        if (e.line()) {
            if (e.color() != null) {
                ops.add(new Op.Line(x, cy, x + size * 4, cy, Stroke.solid(e.width(), e.color())));
            }
            if (e.marker() != null) {
                marker(e.marker(), x + size * 2, cy);
            }
        } else if (e.color() != null) {
            ops.add(new Op.Rect(x, cy - size / 2, size, size, Fill.solid(e.color()), null));
        }
    }

    private void center(String text, float cx, float baseline, TextStyle s) {
        ops.add(new Op.Text(cx - width(s, text) / 2, baseline, text, s));
    }

    private Color pointColor(Chart.Group g, Chart.Series s, int i) {
        if (i < s.points().size() && s.points().get(i) != null && s.points().get(i).fill() != null) {
            return s.points().get(i).fill();
        }
        if (g.varyColors() || g.kind() == Chart.Kind.PIE || g.kind() == Chart.Kind.DOUGHNUT) {
            Color c = theme.schemeColor("accent" + (Math.floorMod(i, 6) + 1));
            if (c != null) {
                return c;
            }
        }
        return s.fill();
    }

    private void pie(Chart.Group g, float cx, float left, float top, float right, float bottom) {
        if (g.series().isEmpty()) {
            return;
        }
        Chart.Series s = g.series().get(0);
        double total = 0;
        for (double v : s.values()) {
            if (!Double.isNaN(v) && v > 0) {
                total += v;
            }
        }
        if (total <= 0) {
            return;
        }
        float d = Math.min(right - left, bottom - top);
        float cy = (top + bottom) / 2;
        float r = d / 2;
        double angle = 90 - g.firstSliceAngle();
        Stroke edge = s.outline() != null ? s.outline() : Stroke.solid(1.5f, Color.WHITE);
        TextStyle ls = style(chart.textSize(), false, chart.textColor());
        Chart.Labels labels = s.labels() != null ? s.labels() : g.labels();
        for (int i = 0; i < s.values().length; i++) {
            double v = s.values()[i];
            if (Double.isNaN(v) || v <= 0) {
                continue;
            }
            double extent = -360 * v / total;
            Arc2D.Double arc = new Arc2D.Double(cx - r, cy - r, d, d, angle, extent, Arc2D.PIE);
            java.awt.Shape shape = arc;
            if (g.kind() == Chart.Kind.DOUGHNUT) {
                Path2D.Double ring = new Path2D.Double(Path2D.WIND_EVEN_ODD);
                ring.append(arc, false);
                float hole = r * Math.max(10, Math.min(90, g.holeSize())) / 100f;
                ring.append(new Ellipse2D.Double(cx - hole, cy - hole, hole * 2, hole * 2), false);
                shape = ring;
            }
            Color c = pointColor(g, s, i);
            ops.add(new Op.Path(shape, c == null ? null : Fill.solid(c), edge));
            if (labels.any()) {
                double mid = Math.toRadians(angle + extent / 2);
                boolean outside = "outEnd".equals(labels.position()) || "bestFit".equals(labels.position())
                        && v / total < 0.05;
                float k = outside ? 1.12f : 0.65f;
                float lx = (float) (cx + Math.cos(mid) * r * k);
                float ly = (float) (cy - Math.sin(mid) * r * k);
                String text = label(labels, s, v, v / total, i, "; ");
                if (outside) {
                    float tw = width(ls, text);
                    float tx = Math.cos(mid) >= 0 ? lx : lx - tw;
                    ops.add(new Op.Text(tx, ly + ascent(ls) / 3, text, ls));
                } else {
                    center(text, lx, ly + ascent(ls) / 3, ls);
                }
            }
            angle += extent;
        }
    }

    private String label(Chart.Labels labels, Chart.Series s, double v, double share, int i, String separator) {
        List<String> parts = new ArrayList<>();
        if (labels.series() && s.name() != null) {
            parts.add(s.name());
        }
        if (labels.category() && i < chart.categories().size()) {
            parts.add(chart.categories().get(i));
        }
        if (labels.value()) {
            String f = labels.format() != null ? labels.format()
                    : chart.values() == null ? "General" : chart.values().format();
            parts.add(format(v, f, chart.date1904()));
        }
        if (labels.percent()) {
            parts.add(Math.round(share * 100) + "%");
        }
        return String.join(separator, parts);
    }

    private record Scale(double min, double max, double unit) {

        // A set maximum that is no multiple of the unit ends the axis between gridlines
        int steps() {
            return (int) Math.max(1, Math.min(1000, Math.floor((max - min) / unit + 1e-6)));
        }

        float fraction(int step) {
            return max > min ? (float) Math.min(1, step * unit / (max - min)) : 0;
        }
    }

    // How category labels are set: rotation in degrees (negative reads upwards), every skip-th label, wrapped lines
    private record Labels(float rotation, int skip, List<List<String>> lines, float extent) {}

    private void axes(float left, float top, float right, float bottom, Rectangle2D.Float inner) {
        Chart.Axis va = chart.values();
        Chart.Axis ca = chart.categoryAxis();
        boolean horizontal = chart.groups().get(0).horizontal();
        String format = va == null ? "General" : va.format();
        TextStyle vs = style(va == null ? chart.textSize() : va.size(), false, chart.textColor(),
                va == null ? null : va.font());
        TextStyle cs = style(ca == null ? chart.textSize() : ca.size(), false, chart.textColor(),
                ca == null ? null : ca.font());
        boolean showValues = va == null || va.labelsShown();
        boolean showCats = ca == null || ca.labelsShown();
        boolean midCat = ca != null && ca.midCategory();
        int n = categoryCount();
        Chart.Text vTitle = va == null || va.deleted() ? null : va.title();
        Chart.Text cTitle = ca == null || ca.deleted() ? null : ca.title();
        float plotLeft;
        float plotTop = top;
        float plotW;
        float plotH;
        Scale sc;
        Labels cl = null;
        List<String> ticks;
        Chart.Axis va2 = chart.secondary();
        boolean second = !horizontal && va2 != null && chart.groups().stream().anyMatch(Chart.Group::secondary);
        TextStyle vs2 = style(va2 == null ? chart.textSize() : va2.size(), false, chart.textColor(),
                va2 == null ? null : va2.font());
        boolean showSecond = second && va2.labelsShown();
        String format2 = va2 == null ? "General" : va2.format();
        Scale sc2 = null;
        double[] xr = scatter() ? xRange() : null;
        Scale xs = null;
        if (!horizontal) {
            if (vTitle != null && inner == null) {
                left += lineHeight(titleStyle(vTitle)) + AXIS_TITLE_GAP;
            }
            if (cTitle != null && inner == null) {
                bottom -= lineHeight(titleStyle(cTitle)) + AXIS_TITLE_GAP;
            }
            float estimate = inner != null ? inner.height
                    : bottom - top - (showCats ? lineHeight(cs) + CATEGORY_GAP : 0);
            sc = scale(va, estimate, gridGap(vs));
            ticks = ticks(sc, format);
            plotLeft = inner != null ? inner.x : left + (showValues ? widest(ticks, vs) + VALUE_GAP : 0);
            plotW = inner != null ? inner.width : right - plotLeft;
            if (second) {
                sc2 = scale(va2, estimate, gridGap(vs2), range(true));
                if (inner == null && showSecond) {
                    plotW -= widest(ticks(sc2, format2), vs2) + VALUE_GAP;
                }
            }
            if (xr != null && plotW > 4) {
                xs = scaleAcross(ca, plotW, cs, ca == null ? "General" : ca.format(), xr);
                cl = new Labels(0, 1, List.of(), showCats ? lineHeight(cs) : 0);
            } else if (showCats && n > 0 && plotW > 4) {
                cl = categoryLabels(n, midCat ? plotW / Math.max(1, n - 1) : plotW / n, cs, ca,
                        (bottom - top) * 0.45f);
            }
            if (inner != null) {
                plotTop = inner.y;
                plotH = inner.height;
            } else {
                float reserve = cl == null ? 0 : cl.extent() + CATEGORY_GAP;
                plotH = bottom - top - reserve;
                sc = scale(va, plotH, gridGap(vs));
                if (cl != null && crossesInside(sc, ca)) {
                    // labels hang from the axis where it crosses at zero, so only what reaches past the plot counts
                    for (int pass = 0; pass < 2; pass++) {
                        float below = (float) ((0 - sc.min()) / (sc.max() - sc.min())) * plotH;
                        plotH = bottom - top - Math.max(0, reserve - below);
                        sc = scale(va, plotH, gridGap(vs));
                    }
                }
                ticks = ticks(sc, format);
                float again = left + (showValues ? widest(ticks, vs) + VALUE_GAP : 0);
                if (second) {
                    sc2 = scale(va2, plotH, gridGap(vs2), range(true));
                }
                if (Math.abs(again - plotLeft) > 0.5f) {
                    plotLeft = again;
                    plotW = right - plotLeft - (showSecond ? widest(ticks(sc2, format2), vs2) + VALUE_GAP : 0);
                }
            }
            if (second && inner != null) {
                sc2 = scale(va2, plotH, gridGap(vs2), range(true));
            }
        } else {
            if (cTitle != null && inner == null) {
                left += lineHeight(titleStyle(cTitle)) + AXIS_TITLE_GAP;
            }
            if (vTitle != null && inner == null) {
                bottom -= lineHeight(titleStyle(vTitle)) + AXIS_TITLE_GAP;
            }
            float cw = 0;
            if (showCats) {
                for (int i = 0; i < n; i++) {
                    cw = Math.max(cw, width(cs, category(i)));
                }
            }
            plotLeft = inner != null ? inner.x : left + (showCats ? cw + VALUE_GAP : 0);
            plotW = inner != null ? inner.width : right - plotLeft;
            plotTop = inner != null ? inner.y : top;
            plotH = inner != null ? inner.height : bottom - top - (showValues ? lineHeight(vs) + CATEGORY_GAP : 0);
            sc = scaleAcross(va, plotW, vs, format);
            ticks = ticks(sc, format);
        }
        if (plotW < 4 || plotH < 4) {
            return;
        }
        float plotBottom = plotTop + plotH;
        if (chart.plotFill() != null || chart.plotBorder() != null) {
            ops.add(new Op.Rect(plotLeft, plotTop, plotW, plotH,
                    chart.plotFill() == null ? null : Fill.solid(chart.plotFill()), chart.plotBorder()));
        }
        int steps = sc.steps();
        Color gridColor = va == null ? ChartReader.AUTO_LINE : va.gridColor();
        boolean grid = va == null || va.gridlines();
        float gridWidth = va == null ? 0.75f : va.gridWidth();
        for (int i = 0; i <= steps; i++) {
            float f = sc.fraction(i);
            if (!horizontal) {
                float gy = plotBottom - f * plotH;
                if (grid && gridColor != null) {
                    ops.add(new Op.Line(plotLeft, gy, plotLeft + plotW, gy, Stroke.solid(gridWidth, gridColor)));
                }
                if (showValues) {
                    String t = ticks.get(i);
                    ops.add(new Op.Text(plotLeft - VALUE_GAP - width(vs, t), gy + (ascent(vs) - descent(vs)) / 2, t,
                            vs));
                }
            } else {
                float gx = plotLeft + f * plotW;
                if (grid && gridColor != null) {
                    ops.add(new Op.Line(gx, plotTop, gx, plotBottom, Stroke.solid(gridWidth, gridColor)));
                }
                if (showValues) {
                    center(ticks.get(i), gx, plotBottom + CATEGORY_GAP + ascent(vs), vs);
                }
            }
        }
        if (sc2 != null && showSecond) {
            List<String> t2 = ticks(sc2, format2);
            for (int i = 0; i <= sc2.steps(); i++) {
                float gy = plotBottom - sc2.fraction(i) * plotH;
                ops.add(new Op.Text(plotLeft + plotW + VALUE_GAP, gy + (ascent(vs2) - descent(vs2)) / 2, t2.get(i),
                        vs2));
            }
        }
        if (xs != null) {
            List<String> tx = ticks(xs, ca == null ? "General" : ca.format());
            boolean xGrid = ca != null && ca.gridlines() && ca.gridColor() != null;
            for (int i = 0; i <= xs.steps(); i++) {
                float gx = plotLeft + xs.fraction(i) * plotW;
                if (xGrid) {
                    ops.add(new Op.Line(gx, plotTop, gx, plotBottom, Stroke.solid(ca.gridWidth(), ca.gridColor())));
                }
                if (showCats) {
                    center(tx.get(i), gx, plotBottom + CATEGORY_GAP + ascent(cs), cs);
                }
            }
        }
        Color valueLine = va == null || va.deleted() ? null : va.lineColor();
        if (valueLine != null) {
            if (!horizontal) {
                ops.add(new Op.Line(plotLeft, plotTop, plotLeft, plotBottom, Stroke.solid(0.75f, valueLine)));
            } else {
                ops.add(new Op.Line(plotLeft, plotBottom, plotLeft + plotW, plotBottom,
                        Stroke.solid(0.75f, valueLine)));
            }
        }
        float zero = (float) ((0 - sc.min()) / (sc.max() - sc.min()));
        zero = Math.max(0, Math.min(1, zero));
        Color axisColor = ca == null ? ChartReader.AUTO_LINE : ca.deleted() ? null : ca.lineColor();
        boolean ticksOut = ca != null && ("out".equals(ca.majorTick()) || "cross".equals(ca.majorTick()));
        if (axisColor != null) {
            Stroke as = Stroke.solid(0.75f, axisColor);
            if (!horizontal) {
                float zy = plotBottom - zero * plotH;
                ops.add(new Op.Line(plotLeft, zy, plotLeft + plotW, zy, as));
                if (ticksOut && n > 0 && xs == null) {
                    int marks = midCat ? n - 1 : n;
                    int every = ca.date() && cl != null ? cl.skip() : 1;
                    for (int i = 0; i <= marks; i += every) {
                        float tx = plotLeft + i * plotW / Math.max(1, marks);
                        ops.add(new Op.Line(tx, zy, tx, zy + TICK, as));
                    }
                }
            } else {
                float zx = plotLeft + zero * plotW;
                ops.add(new Op.Line(zx, plotTop, zx, plotBottom, as));
                if (ticksOut && n > 0) {
                    for (int i = 0; i <= n; i++) {
                        float ty = plotTop + i * plotH / n;
                        ops.add(new Op.Line(zx - TICK, ty, zx, ty, as));
                    }
                }
            }
        }
        if (vTitle != null) {
            TextStyle ts = titleStyle(vTitle);
            if (!horizontal) {
                float cx = left - AXIS_TITLE_GAP - lineHeight(ts) / 2;
                if (inner != null) {
                    cx = plotLeft - (showValues ? widest(ticks, vs) + VALUE_GAP : 0) - AXIS_TITLE_GAP
                            - lineHeight(ts) / 2;
                }
                rotated(vTitle.text(), cx, plotTop + plotH / 2, -90, ts, 0);
            } else {
                float by = (inner != null ? plotBottom + lineHeight(vs) + CATEGORY_GAP : bottom) + AXIS_TITLE_GAP;
                center(vTitle.text(), plotLeft + plotW / 2, by + ascent(ts), ts);
            }
        }
        if (cTitle != null) {
            TextStyle ts = titleStyle(cTitle);
            if (!horizontal) {
                float by = inner != null ? plotBottom + CATEGORY_GAP + (cl == null ? 0 : cl.extent()) : bottom;
                center(cTitle.text(), plotLeft + plotW / 2, by + AXIS_TITLE_GAP + ascent(ts), ts);
            } else {
                float cx = (inner != null ? plotLeft - VALUE_GAP : left) - AXIS_TITLE_GAP - lineHeight(ts) / 2;
                rotated(cTitle.text(), cx, plotTop + plotH / 2, -90, ts, 0);
            }
        }
        if (n <= 0) {
            return;
        }
        boolean reversed = ca != null && ca.reversed();
        if (showCats && xs == null) {
            if (!horizontal && cl != null) {
                float axisY = crossesInside(sc, ca) ? plotBottom - zero * plotH : plotBottom;
                drawCategories(cl, n, plotLeft, plotW, axisY + CATEGORY_GAP, reversed, midCat, cs);
            } else if (horizontal) {
                for (int i = 0; i < n; i++) {
                    int slot = reversed ? n - 1 - i : i;
                    float cy = plotBottom - (slot + 0.5f) * plotH / n;
                    String t = category(i);
                    ops.add(new Op.Text(plotLeft - VALUE_GAP - width(cs, t), cy + (ascent(cs) - descent(cs)) / 2, t,
                            cs));
                }
            }
        }
        Plot plot = new Plot(plotLeft, plotTop, plotW, plotH, sc, horizontal, reversed, n, midCat, xs);
        for (Chart.Group g : chart.groups()) {
            Plot p = g.secondary() && sc2 != null ? plot.with(sc2) : plot;
            switch (g.kind()) {
                case BAR -> bars(g, p);
                case LINE, SCATTER -> lines(g, p, false);
                case AREA -> lines(g, p, true);
                default -> {
                }
            }
        }
    }

    // Category labels sit next to the category axis, which crosses the value axis at zero inside the plot
    private static boolean crossesInside(Scale sc, Chart.Axis ca) {
        return sc.min() < 0 && sc.max() > 0 && (ca == null || "nextTo".equals(ca.labelPosition()));
    }

    private boolean scatter() {
        for (Chart.Group g : chart.groups()) {
            if (g.kind() != Chart.Kind.SCATTER) {
                return false;
            }
        }
        return !chart.groups().isEmpty();
    }

    private TextStyle titleStyle(Chart.Text t) {
        return style(t.size(), t.bold(), t.color());
    }

    private float widest(List<String> ticks, TextStyle s) {
        float w = 0;
        for (String t : ticks) {
            w = Math.max(w, width(s, t));
        }
        return w;
    }

    private List<String> ticks(Scale sc, String format) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i <= sc.steps(); i++) {
            double v = sc.min() + i * sc.unit();
            if (Math.abs(v) < sc.unit() * 1e-9) {
                v = 0;
            }
            out.add(format(v, format, chart.date1904()));
        }
        return out;
    }

    // Text rotated about its middle at (cx, cy), in degrees clockwise
    private void rotated(String text, float cx, float cy, float degrees, TextStyle s, float along) {
        float w = width(s, text);
        AffineTransform t = AffineTransform.getRotateInstance(Math.toRadians(degrees), cx, cy);
        ops.add(new Op.Group(0, 0, t, null,
                List.of(new Op.Text(cx - w / 2 + along, cy + (ascent(s) - descent(s)) / 2, text, s))));
    }

    // Office keeps category labels level while they fit, wraps text labels at spaces, then turns them 45 or 90
    // degrees and skips labels until they no longer overlap
    private Labels categoryLabels(int n, float band, TextStyle cs, Chart.Axis ca, float maxExtent) {
        float lh = lineHeight(cs);
        List<String> texts = new ArrayList<>();
        float maxW = 0;
        float maxWord = 0;
        for (int i = 0; i < n; i++) {
            String t = category(i);
            texts.add(t);
            maxW = Math.max(maxW, width(cs, t));
            for (String word : t.split("[ \n]|(?<=[^ -]-)(?!\\d)")) {
                maxWord = Math.max(maxWord, width(cs, word));
            }
        }
        Float explicit = ca == null ? null : ca.rotation();
        int skip = ca != null && ca.skip() > 0 ? ca.skip() : 0;
        boolean date = ca != null && ca.date();
        float rotation;
        float room = band;
        if (explicit != null) {
            rotation = explicit;
        } else if (maxW <= room * Math.max(1, skip)) {
            rotation = 0;
        } else if (!date && maxWord <= room) {
            rotation = 0;
        } else if (lh / SIN45 <= band * Math.max(1, skip) || skip > 0 && lh / SIN45 <= band * skip) {
            rotation = -45;
        } else {
            rotation = -90;
        }
        if (skip == 0) {
            if (rotation == 0) {
                skip = 1;
                if (maxWord > room || date && maxW > room) {
                    skip = (int) Math.ceil((date ? maxW : maxWord) / Math.max(0.1f, room));
                }
            } else {
                float need = Math.abs(rotation) >= 60 ? lh * 1.05f : Math.abs(rotation) >= 30 ? lh / SIN45 : maxW;
                skip = Math.max(1, (int) Math.ceil(need / Math.max(0.1f, band)));
            }
            if (date) {
                skip = niceSkip(skip);
            }
        }
        List<List<String>> lines = new ArrayList<>();
        float extent = 0;
        double rad = Math.toRadians(Math.abs(rotation));
        for (int i = 0; i < n; i++) {
            List<String> l = rotation == 0 ? wrap(texts.get(i), cs, Math.max(room * Math.max(1, skip), 1))
                    : List.of(texts.get(i));
            lines.add(l);
            if (i % skip != 0) {
                continue;
            }
            if (rotation == 0) {
                extent = Math.max(extent, l.size() * lh);
            } else {
                float w = width(cs, texts.get(i));
                extent = Math.max(extent, (float) (w * Math.sin(rad) + lh * Math.cos(rad)));
            }
        }
        return new Labels(rotation, skip, lines, Math.min(extent, Math.max(lh, maxExtent)));
    }

    private static int niceSkip(int skip) {
        for (int k : new int[] {1, 2, 3, 4, 6, 12, 24, 36, 48, 60, 120, 240, 360, 600, 1200}) {
            if (k >= skip) {
                return k;
            }
        }
        return skip;
    }

    private void drawCategories(Labels cl, int n, float plotLeft, float plotW, float top, boolean reversed,
            boolean midCat, TextStyle cs) {
        float lh = lineHeight(cs);
        for (int i = 0; i < n; i += cl.skip()) {
            int slot = reversed ? n - 1 - i : i;
            float cx = midCat ? plotLeft + slot * plotW / Math.max(1, n - 1) : plotLeft + (slot + 0.5f) * plotW / n;
            List<String> l = cl.lines().get(i);
            if (cl.rotation() == 0) {
                float baseline = top + ascent(cs);
                for (String t : l) {
                    center(t, cx, baseline, cs);
                    baseline += lh;
                }
                continue;
            }
            String t = l.get(0);
            float w = width(cs, t);
            float r = cl.rotation();
            AffineTransform tr = AffineTransform.getRotateInstance(Math.toRadians(r), cx, top);
            float mid = (ascent(cs) - descent(cs)) / 2;
            float x = r < 0 ? cx - w : cx;
            ops.add(new Op.Group(0, 0, tr, null, List.of(new Op.Text(x, top + mid, t, cs))));
        }
    }

    private record Plot(float x, float y, float w, float h, Scale scale, boolean horizontal, boolean reversed,
            int count, boolean midCat, Scale xScale) {

        Plot with(Scale s) {
            return new Plot(x, y, w, h, s, horizontal, reversed, count, midCat, xScale);
        }

        float xPos(double v) {
            double f = (v - xScale.min()) / (xScale.max() - xScale.min());
            return (float) (x + Math.max(0, Math.min(1, f)) * w);
        }

        float valuePos(double v) {
            double f = (v - scale.min()) / (scale.max() - scale.min());
            f = Math.max(0, Math.min(1, f));
            return horizontal ? (float) (x + f * w) : (float) (y + h - f * h);
        }

        float band() {
            return (horizontal ? h : w) / count;
        }

        float bandStart(int i) {
            int slot = reversed ? count - 1 - i : i;
            return horizontal ? y + h - (slot + 1) * band() : x + slot * band();
        }

        float center(int i) {
            if (midCat && count > 1) {
                int slot = reversed ? count - 1 - i : i;
                float step = (horizontal ? h : w) / (count - 1);
                return horizontal ? y + h - slot * step : x + slot * step;
            }
            return bandStart(i) + band() / 2;
        }
    }

    private void bars(Chart.Group g, Plot p) {
        int ns = g.series().size();
        if (ns == 0) {
            return;
        }
        boolean stacked = g.grouping().equals("stacked") || g.grouping().equals("percentStacked");
        boolean percent = g.grouping().equals("percentStacked");
        float gap = Math.max(0, Math.min(500, g.gapWidth())) / 100f;
        float overlap = stacked ? 1 : Math.max(-1, Math.min(1, g.overlap() / 100f));
        int slots = stacked ? 1 : ns;
        float bw = p.band() / (slots - (slots - 1) * overlap + gap);
        double[] pos = new double[p.count()];
        double[] neg = new double[p.count()];
        double[] sums = new double[p.count()];
        if (percent) {
            for (Chart.Series s : g.series()) {
                for (int i = 0; i < Math.min(p.count(), s.values().length); i++) {
                    if (!Double.isNaN(s.values()[i])) {
                        sums[i] += Math.abs(s.values()[i]);
                    }
                }
            }
        }
        TextStyle ls = style(chart.textSize(), false, chart.textColor());
        for (int k = 0; k < ns; k++) {
            Chart.Series s = g.series().get(k);
            Chart.Labels labels = s.labels() != null ? s.labels() : g.labels();
            for (int i = 0; i < Math.min(p.count(), s.values().length); i++) {
                double v = s.values()[i];
                if (Double.isNaN(v)) {
                    continue;
                }
                if (percent) {
                    v = sums[i] == 0 ? 0 : v / sums[i];
                }
                double base = 0;
                if (stacked) {
                    base = v >= 0 ? pos[i] : neg[i];
                    if (v >= 0) {
                        pos[i] += v;
                    } else {
                        neg[i] += v;
                    }
                }
                float start = p.bandStart(i) + bw * gap / 2 + (stacked ? 0 : k * bw * (1 - overlap));
                float a = p.valuePos(base);
                float b = p.valuePos(base + v);
                Color c = g.varyColors() && ns == 1 ? pointColor(g, s, i) : s.points().size() > i
                        && s.points().get(i) != null && s.points().get(i).fill() != null ? s.points().get(i).fill()
                        : s.fill();
                if (c == null && s.outline() == null) {
                    continue;
                }
                Fill fill = c == null ? null : Fill.solid(c);
                if (p.horizontal()) {
                    ops.add(new Op.Rect(Math.min(a, b), start, Math.abs(b - a), bw, fill, s.outline()));
                } else {
                    ops.add(new Op.Rect(start, Math.min(a, b), bw, Math.abs(b - a), fill, s.outline()));
                }
                if (labels.any()) {
                    String t = label(labels, s, s.values()[i], 0, i, ", ");
                    boolean inside = stacked || "ctr".equals(labels.position()) || "inEnd".equals(labels.position())
                            || "inBase".equals(labels.position());
                    float tw = width(ls, t);
                    if (p.horizontal()) {
                        float tx = inside ? (a + b) / 2 - tw / 2 : Math.max(a, b) + 3;
                        ops.add(new Op.Text(tx, start + bw / 2 + (ascent(ls) - descent(ls)) / 2, t, ls));
                    } else if (inside) {
                        center(t, start + bw / 2, (a + b) / 2 + (ascent(ls) - descent(ls)) / 2, ls);
                    } else {
                        center(t, start + bw / 2, Math.min(a, b) - 3 - descent(ls), ls);
                    }
                }
            }
        }
    }

    private void lines(Chart.Group g, Plot p, boolean area) {
        boolean stacked = g.grouping().equals("stacked") || g.grouping().equals("percentStacked");
        double[] acc = new double[p.count()];
        TextStyle ls = style(chart.textSize(), false, chart.textColor());
        for (Chart.Series s : g.series()) {
            int count = Math.min(p.count(), s.values().length);
            List<float[]> pts = new ArrayList<>();
            List<List<float[]>> runs = new ArrayList<>();
            List<float[]> run = new ArrayList<>();
            List<Integer> index = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                double v = s.values()[i];
                if (Double.isNaN(v)) {
                    if (!run.isEmpty()) {
                        runs.add(run);
                        run = new ArrayList<>();
                    }
                    continue;
                }
                if (stacked) {
                    acc[i] += v;
                    v = acc[i];
                }
                float px;
                if (g.kind() == Chart.Kind.SCATTER && s.xs() != null && i < s.xs().length && !Double.isNaN(s.xs()[i])) {
                    px = p.xScale() != null ? p.xPos(s.xs()[i]) : scatterX(s.xs(), i, p);
                } else {
                    px = p.center(i);
                }
                float py = p.valuePos(v);
                if (p.horizontal()) {
                    float t = px;
                    px = py;
                    py = t;
                }
                float[] pt = {px, py};
                run.add(pt);
                pts.add(pt);
                index.add(i);
            }
            if (!run.isEmpty()) {
                runs.add(run);
            }
            if (pts.isEmpty()) {
                continue;
            }
            if (area && s.fill() != null && pts.size() > 1) {
                Path2D.Float fill = new Path2D.Float();
                float base = p.valuePos(0);
                fill.moveTo(pts.get(0)[0], base);
                for (float[] pt : pts) {
                    fill.lineTo(pt[0], pt[1]);
                }
                fill.lineTo(pts.get(pts.size() - 1)[0], base);
                fill.closePath();
                ops.add(new Op.Path(fill, Fill.solid(s.fill()), null));
                continue;
            }
            if (s.line() != null) {
                Path2D.Float path = new Path2D.Float();
                for (List<float[]> r : runs) {
                    trace(path, r, s.smooth());
                }
                Chart.Shadow sh = s.shadow();
                if (sh != null) {
                    // a soft shadow approximated by a wider, fainter copy of the line under it
                    Color c = new Color(sh.color().getRed(), sh.color().getGreen(), sh.color().getBlue(),
                            Math.round(sh.color().getAlpha() * 0.6f));
                    Stroke st = s.line().width(s.line().width() + sh.blur() * 0.5f).color(c).cap(Stroke.Cap.ROUND)
                            .join(Stroke.Join.ROUND);
                    ops.add(new Op.Group(sh.dx(), sh.dy(), null, null, List.of(new Op.Path(path, null, st))));
                }
                ops.add(new Op.Path(path, null, s.line().cap(Stroke.Cap.ROUND).join(Stroke.Join.ROUND)));
            }
            if (s.markers()) {
                Chart.Marker m = s.marker() != null ? s.marker()
                        : new Chart.Marker("circle", 5, s.fill(), s.fill());
                for (float[] pt : pts) {
                    marker(m, pt[0], pt[1]);
                }
            }
            Chart.Labels labels = s.labels() != null ? s.labels() : g.labels();
            if (labels.any()) {
                for (int k = 0; k < pts.size(); k++) {
                    int i = index.get(k);
                    String t = label(labels, s, s.values()[i], 0, i, ", ");
                    float[] pt = pts.get(k);
                    float tw = width(ls, t);
                    float mid = (ascent(ls) - descent(ls)) / 2;
                    String pos = labels.position() == null ? "r" : labels.position();
                    switch (pos) {
                        case "t" -> center(t, pt[0], pt[1] - 4 - descent(ls), ls);
                        case "b" -> center(t, pt[0], pt[1] + 4 + ascent(ls), ls);
                        case "ctr" -> center(t, pt[0], pt[1] + mid, ls);
                        case "l" -> ops.add(new Op.Text(pt[0] - 4 - tw, pt[1] + mid, t, ls));
                        default -> ops.add(new Op.Text(pt[0] + 4, pt[1] + mid, t, ls));
                    }
                }
            }
        }
    }

    // Smoothed lines pass through every point on Bezier curves with Catmull-Rom tangents, as Office draws them
    private static void trace(Path2D.Float path, List<float[]> pts, boolean smooth) {
        path.moveTo(pts.get(0)[0], pts.get(0)[1]);
        for (int i = 1; i < pts.size(); i++) {
            float[] b = pts.get(i);
            if (!smooth || pts.size() < 3) {
                path.lineTo(b[0], b[1]);
                continue;
            }
            float[] a = pts.get(i - 1);
            float[] before = pts.get(Math.max(0, i - 2));
            float[] after = pts.get(Math.min(pts.size() - 1, i + 1));
            path.curveTo(a[0] + (b[0] - before[0]) / 6, a[1] + (b[1] - before[1]) / 6, b[0] - (after[0] - a[0]) / 6,
                    b[1] - (after[1] - a[1]) / 6, b[0], b[1]);
        }
    }

    private void marker(Chart.Marker m, float cx, float cy) {
        float r = m.size() / 2;
        Fill fill = m.fill() == null ? null : Fill.solid(m.fill());
        Stroke line = m.line() == null ? null : Stroke.solid(0.75f, m.line());
        if (fill == null && line == null) {
            return;
        }
        java.awt.Shape shape;
        switch (m.symbol()) {
            case "none" -> {
                return;
            }
            case "square" -> shape = new Rectangle2D.Float(cx - r, cy - r, 2 * r, 2 * r);
            case "diamond" -> shape = polygon(cx, cy - r, cx + r, cy, cx, cy + r, cx - r, cy);
            case "triangle" -> shape = polygon(cx, cy - r, cx + r, cy + r, cx - r, cy + r);
            case "dash" -> shape = new Rectangle2D.Float(cx - r, cy - r / 4, 2 * r, r / 2);
            case "dot" -> shape = new Rectangle2D.Float(cx - r / 2, cy - r / 4, r, r / 2);
            case "x", "star", "plus" -> {
                Path2D.Float p = new Path2D.Float();
                if (!m.symbol().equals("plus")) {
                    p.moveTo(cx - r, cy - r);
                    p.lineTo(cx + r, cy + r);
                    p.moveTo(cx - r, cy + r);
                    p.lineTo(cx + r, cy - r);
                }
                if (!m.symbol().equals("x")) {
                    p.moveTo(cx, cy - r);
                    p.lineTo(cx, cy + r);
                    p.moveTo(cx - r, cy);
                    p.lineTo(cx + r, cy);
                }
                Color c = m.line() != null ? m.line() : m.fill();
                ops.add(new Op.Path(p, null, Stroke.solid(0.75f, c)));
                return;
            }
            default -> shape = new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r);
        }
        ops.add(new Op.Path(shape, fill, line));
    }

    private static Path2D.Float polygon(float... xy) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) {
            p.lineTo(xy[i], xy[i + 1]);
        }
        p.closePath();
        return p;
    }

    private static float scatterX(double[] xs, int i, Plot p) {
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (double x : xs) {
            if (!Double.isNaN(x)) {
                lo = Math.min(lo, x);
                hi = Math.max(hi, x);
            }
        }
        double f = hi > lo ? (xs[i] - lo) / (hi - lo) : 0.5;
        return (float) (p.x() + f * p.w());
    }

    private int categoryCount() {
        int n = chart.categories().size();
        for (Chart.Group g : chart.groups()) {
            for (Chart.Series s : g.series()) {
                n = Math.max(n, s.values().length);
            }
        }
        return Math.min(n, 4096);
    }

    // Office numbers the categories only when the chart has no category labels at all
    private String category(int i) {
        if (i < chart.categories().size()) {
            return chart.categories().get(i);
        }
        return chart.categories().isEmpty() ? Integer.toString(i + 1) : "";
    }

    private double[] range(boolean secondary) {
        double lo = 0;
        double hi = 0;
        double dataLo = Double.POSITIVE_INFINITY;
        double dataHi = Double.NEGATIVE_INFINITY;
        boolean any = false;
        boolean stackedAny = false;
        for (Chart.Group g : chart.groups()) {
            if (g.secondary() != secondary) {
                continue;
            }
            boolean percent = g.grouping().equals("percentStacked");
            boolean stacked = g.grouping().equals("stacked");
            int n = 0;
            for (Chart.Series s : g.series()) {
                n = Math.max(n, s.values().length);
            }
            if (percent) {
                hi = Math.max(hi, 1);
                any = true;
                stackedAny = true;
                continue;
            }
            double[] pos = new double[n];
            double[] neg = new double[n];
            for (Chart.Series s : g.series()) {
                for (int i = 0; i < s.values().length; i++) {
                    double v = s.values()[i];
                    if (Double.isNaN(v)) {
                        continue;
                    }
                    any = true;
                    if (stacked) {
                        stackedAny = true;
                        if (v >= 0) {
                            pos[i] += v;
                        } else {
                            neg[i] += v;
                        }
                    } else {
                        dataLo = Math.min(dataLo, v);
                        dataHi = Math.max(dataHi, v);
                        lo = Math.min(lo, v);
                        hi = Math.max(hi, v);
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                hi = Math.max(hi, pos[i]);
                lo = Math.min(lo, neg[i]);
            }
        }
        if (!any || hi - lo <= 0) {
            return new double[] {lo, lo + 1};
        }
        return stackedAny ? new double[] {lo, hi} : zeroRule(dataLo, dataHi, lo, hi);
    }

    // Excel leaves zero out when the values sit in a band narrower than a sixth of their size
    private static double[] zeroRule(double dataLo, double dataHi, double lo, double hi) {
        if (dataLo > 0 && (dataHi - dataLo) < dataHi / 6) {
            return new double[] {dataLo - (dataHi - dataLo) / 2, dataHi};
        }
        if (dataHi < 0 && (dataHi - dataLo) < -dataLo / 6) {
            return new double[] {dataLo, dataHi + (dataHi - dataLo) / 2};
        }
        return new double[] {lo, hi};
    }

    private double[] xRange() {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (Chart.Group g : chart.groups()) {
            for (Chart.Series s : g.series()) {
                if (s.xs() == null) {
                    continue;
                }
                for (double x : s.xs()) {
                    if (!Double.isNaN(x)) {
                        lo = Math.min(lo, x);
                        hi = Math.max(hi, x);
                    }
                }
            }
        }
        if (lo > hi) {
            return null;
        }
        if (hi - lo <= 0) {
            return new double[] {Math.min(0, lo), Math.max(hi, lo + 1)};
        }
        return zeroRule(lo, hi, Math.min(0, lo), Math.max(0, hi));
    }

    private Scale fit(Chart.Axis va, double unit, double[] r) {
        double range = r[1] - r[0];
        double top = r[1] > 0 ? r[1] + 0.05 * range : r[1];
        double bottom = r[0] < 0 ? r[0] - 0.05 * range : r[0];
        double max = va != null && va.max() != null ? va.max() : Math.ceil(top / unit - 1e-9) * unit;
        double min = va != null && va.min() != null ? va.min() : Math.floor(bottom / unit + 1e-9) * unit;
        if (max <= min) {
            max = min + unit;
        }
        return new Scale(min, max, unit);
    }

    private List<Double> units(Chart.Axis va, double[] r) {
        List<Double> out = new ArrayList<>();
        if (va != null && va.majorUnit() != null) {
            out.add(va.majorUnit());
            return out;
        }
        double lo = va != null && va.min() != null ? va.min() : r[0];
        double hi = va != null && va.max() != null ? va.max() : r[1];
        double span = Math.max(Math.abs(hi - lo), 1e-9);
        double magnitude = Math.pow(10, Math.floor(Math.log10(span)) - 2);
        for (int k = 0; k < 6; k++) {
            for (double m : new double[] {1, 2, 5}) {
                out.add(m * magnitude);
            }
            magnitude *= 10;
        }
        return out;
    }

    // The smallest round major unit whose gridlines stay at least the given gap apart
    private Scale scale(Chart.Axis va, float length, float spacing) {
        return scale(va, length, spacing, range(false));
    }

    private Scale scale(Chart.Axis va, float length, float spacing, double[] r) {
        Scale last = null;
        for (double unit : units(va, r)) {
            Scale sc = fit(va, unit, r);
            last = sc;
            if ((sc.max() - sc.min()) / unit > 1000) {
                continue;
            }
            if (length / ((sc.max() - sc.min()) / unit) >= spacing) {
                return sc;
            }
        }
        return last == null ? new Scale(0, 1, 1) : last;
    }

    // Across the page the labels must fit side by side with room to spare
    private Scale scaleAcross(Chart.Axis va, float length, TextStyle vs, String format) {
        return scaleAcross(va, length, vs, format, range(false));
    }

    private Scale scaleAcross(Chart.Axis va, float length, TextStyle vs, String format, double[] r) {
        Scale last = null;
        for (double unit : units(va, r)) {
            Scale sc = fit(va, unit, r);
            last = sc;
            if ((sc.max() - sc.min()) / unit > 1000) {
                continue;
            }
            float need = Math.max(widest(ticks(sc, format), vs) * 1.5f, lineHeight(vs) * 2.5f);
            if (length / ((sc.max() - sc.min()) / unit) >= need) {
                return sc;
            }
        }
        return last == null ? new Scale(0, 1, 1) : last;
    }

    static String format(double v, String code) {
        return ChartFormat.format(v, code, false);
    }

    static String format(double v, String code, boolean date1904) {
        return ChartFormat.format(v, code, date1904);
    }
}
