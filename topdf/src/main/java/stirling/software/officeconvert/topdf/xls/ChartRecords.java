package stirling.software.officeconvert.topdf.xls;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.poi.hssf.record.LabelRecord;
import org.apache.poi.hssf.record.NumberRecord;
import org.apache.poi.hssf.record.Record;
import org.apache.poi.hssf.record.chart.LegendRecord;
import org.apache.poi.hssf.record.chart.LinkedDataRecord;
import org.apache.poi.hssf.record.chart.SeriesRecord;
import org.apache.poi.hssf.record.chart.SeriesTextRecord;
import org.apache.poi.hssf.record.chart.ValueRangeRecord;

final class ChartRecords {

    static final int MAX_SERIES = 255;

    static final int MAX_POINTS = 4000;

    private static final int CHART = 0x1002, SERIES = 0x1003, DATA_FORMAT = 0x1006, LINE_FORMAT = 0x1007,
            MARKER_FORMAT = 0x1009, AREA_FORMAT = 0x100A, SERIES_TEXT = 0x100D, CHART_FORMAT = 0x1014,
            LEGEND = 0x1015, BAR = 0x1017, LINE = 0x1018, PIE = 0x1019, AREA = 0x101A, SCATTER = 0x101B,
            AXIS = 0x101D, TICK = 0x101E, VALUE_RANGE = 0x101F, CAT_SER_RANGE = 0x1020, AXIS_LINE = 0x1021,
            DEFAULT_TEXT = 0x1024, TEXT = 0x1025, FONTX = 0x1026, OBJECT_LINK = 0x1027, FRAME = 0x1032,
            BEGIN = 0x1033, END = 0x1034, PLOT_AREA = 0x1035, RADAR = 0x103E, RADAR_AREA = 0x1040,
            AXIS_PARENT = 0x1041, SERIES_GROUP = 0x1045, IFMT = 0x104E, SERFMT = 0x105D, BOP_POP = 0x1061,
            SERIES_INDEX = 0x1065;

    interface Fonts {
        BiffChart.Text font(int index, String text);

        String face(int index);

        String format(int index);

        String number(double value, int xf, int fallbackFormat);
    }

    private final Fonts fonts;

    private final Deque<Integer> stack = new ArrayDeque<>();

    private final List<SeriesBuilder> series = new ArrayList<>();

    private final Map<Integer, GroupBuilder> groups = new LinkedHashMap<>();

    private final AxisBuilder[][] axes = new AxisBuilder[2][2];

    private int last;

    private int axisGroup;

    private int axisType = -1;

    private int axisLine = -1;

    private int dataPoint = Integer.MIN_VALUE;

    private int dataSeries;

    private int cache;

    private int frame;

    private boolean plotNext;

    private boolean defaultText;

    private TextBuilder text;

    private BiffChart.Text title;

    private LegendRecord legend;

    private Integer plotFill;

    private Integer areaFill;

    private Integer areaLine;

    private BiffChart.Text base;

    ChartRecords(Fonts fonts) {
        this.fonts = fonts;
    }

    void add(Record r) {
        int sid = r.getSid();
        if (sid == BEGIN) {
            stack.push(last);
            return;
        }
        if (sid == END) {
            Integer top = stack.poll();
            if (top != null && top == TEXT && text != null && !stack.contains(TEXT)) {
                finishText();
            }
            if (top != null && top == FRAME) {
                frame = 0;
            }
            if (top != null && top == AXIS) {
                axisType = -1;
            }
            if (top != null && top == DATA_FORMAT) {
                dataPoint = Integer.MIN_VALUE;
            }
            return;
        }
        last = sid;
        byte[] d = data(r, sid);
        int top = stack.isEmpty() ? 0 : stack.peek();
        switch (sid) {
            case SERIES -> {
                if (series.size() < MAX_SERIES && r instanceof SeriesRecord s) {
                    series.add(new SeriesBuilder(s.getNumCategories(), s.getNumValues()));
                }
            }
            case SERIES_TEXT -> {
                if (r instanceof SeriesTextRecord t) {
                    seriesText(t, top);
                }
            }
            case 0x1051 -> {
                if (r instanceof LinkedDataRecord l) {
                    linked(l);
                }
            }
            case SERIES_GROUP -> {
                if (top == SERIES && !series.isEmpty()) {
                    series.get(series.size() - 1).group = u16(d, 0);
                }
            }
            case DATA_FORMAT -> {
                dataPoint = (short) u16(d, 0);
                dataSeries = u16(d, 4);
            }
            case LINE_FORMAT -> lineFormat(d, top);
            case AREA_FORMAT -> areaFormat(d, top);
            case MARKER_FORMAT -> marker(d);
            case SERFMT -> {
                if (inSeriesFormat()) {
                    current().smooth = (u16(d, 0) & 1) != 0;
                }
            }
            case AXIS_PARENT -> axisGroup = Math.min(1, u16(d, 0));
            case CHART_FORMAT -> {
                GroupBuilder g = new GroupBuilder(axisGroup, d.length >= 18 && (u16(d, 16) & 1) != 0);
                groups.put(d.length >= 20 ? u16(d, 18) : groups.size(), g);
            }
            case BAR, LINE, PIE, AREA, SCATTER, RADAR, RADAR_AREA, BOP_POP -> kind(sid, d);
            case AXIS -> {
                axisType = u16(d, 0);
                axisLine = -1;
                if (axisType <= 1) {
                    axis();
                }
            }
            case VALUE_RANGE -> {
                if (r instanceof ValueRangeRecord v) {
                    valueRange(v);
                }
            }
            case CAT_SER_RANGE -> {
                if (axis() != null) {
                    axis().reversed = (u16(d, 6) & 4) != 0;
                }
            }
            case TICK -> {
                if (axis() != null && d.length > 2) {
                    axis().labels = (d[2] & 0xFF) != 0;
                }
            }
            case AXIS_LINE -> axisLine = u16(d, 0);
            case IFMT -> {
                if (axis() != null) {
                    axis().format = u16(d, 0);
                }
            }
            case LEGEND -> {
                if (legend == null && r instanceof LegendRecord l) {
                    legend = l;
                }
            }
            case DEFAULT_TEXT -> defaultText = true;
            case TEXT -> {
                if (!stack.contains(TEXT)) {
                    text = new TextBuilder(defaultText);
                    defaultText = false;
                }
            }
            case FONTX -> {
                if (text != null) {
                    text.font = u16(d, 0);
                }
            }
            case OBJECT_LINK -> {
                if (text != null) {
                    text.link = u16(d, 0);
                }
            }
            case PLOT_AREA -> plotNext = true;
            case FRAME -> {
                frame = plotNext ? 2 : top == CHART ? 1 : 3;
                plotNext = false;
            }
            case SERIES_INDEX -> cache = u16(d, 0);
            default -> cached(r);
        }
    }

    private void seriesText(SeriesTextRecord r, int top) {
        if (text != null && stack.contains(TEXT)) {
            text.text = r.getText();
        } else if (top == SERIES && !series.isEmpty()) {
            series.get(series.size() - 1).name = r.getText();
        }
    }

    private void linked(LinkedDataRecord r) {
        if (stack.peek() != null && stack.peek() == SERIES && !series.isEmpty() && r.getLinkType() == 1) {
            series.get(series.size() - 1).format = r.getIndexNumberFmtRecord();
        } else if (stack.peek() != null && stack.peek() == SERIES && !series.isEmpty() && r.getLinkType() == 2) {
            series.get(series.size() - 1).categoryFormat = r.getIndexNumberFmtRecord();
        }
    }

    private boolean inSeriesFormat() {
        return dataPoint != Integer.MIN_VALUE && stack.contains(SERIES) && !series.isEmpty();
    }

    private SeriesBuilder current() {
        return series.get(series.size() - 1);
    }

    private void lineFormat(byte[] d, int top) {
        if (d.length < 10) {
            return;
        }
        Integer rgb = u16(d, 4) == 5 ? null : rgb(d, 0);
        boolean auto = (u16(d, 8) & 1) != 0;
        if (inSeriesFormat() && top == DATA_FORMAT && dataPoint == -1) {
            SeriesBuilder s = current();
            s.line = rgb;
            s.lineSet = true;
            s.lineAuto = auto && rgb != null;
            s.lineWidth = switch ((short) u16(d, 6)) {
                case -1 -> 0.5f;
                case 1 -> 1.5f;
                case 2 -> 2.25f;
                default -> 0.75f;
            };
            s.iss = dataSeries;
        } else if (axisType >= 0 && top == AXIS && axisLine == 1 && axis() != null) {
            axis().gridlines = rgb != null;
        } else if (frame == 1 && top == FRAME) {
            areaLine = rgb;
        }
    }

    private void areaFormat(byte[] d, int top) {
        if (d.length < 12) {
            return;
        }
        Integer rgb = u16(d, 8) == 0 ? null : rgb(d, 0);
        boolean auto = (u16(d, 10) & 1) != 0;
        if (inSeriesFormat() && top == DATA_FORMAT) {
            SeriesBuilder s = current();
            if (dataPoint == -1) {
                s.fill = rgb;
                s.fillSet = true;
                s.fillAuto = auto && rgb != null;
                s.iss = dataSeries;
            } else if (dataPoint >= 0 && dataPoint < MAX_POINTS && rgb != null && !auto) {
                s.points.put(dataPoint, rgb);
            }
        } else if (top == FRAME && frame == 2) {
            plotFill = rgb;
        } else if (top == FRAME && frame == 1) {
            areaFill = rgb;
        }
    }

    private void marker(byte[] d) {
        if (!inSeriesFormat() || dataPoint != -1 || d.length < 12) {
            return;
        }
        SeriesBuilder s = current();
        boolean auto = (u16(d, 10) & 1) != 0;
        s.markerAuto = auto;
        s.marker = auto ? null : symbol(u16(d, 8));
        s.markerFill = (u16(d, 10) & 0x10) != 0 ? null : rgb(d, 4);
        s.markerSize = d.length >= 20 ? Math.max(2, Math.min(72, (int) (u32(d, 16) / 20))) : 5;
    }

    static String symbol(int imk) {
        return switch (imk) {
            case 1 -> "square";
            case 2 -> "diamond";
            case 3 -> "triangle";
            case 4 -> "x";
            case 5 -> "star";
            case 6, 7 -> "dash";
            case 8 -> "circle";
            case 9 -> "plus";
            default -> "none";
        };
    }

    private void kind(int sid, byte[] d) {
        GroupBuilder g = groups.isEmpty() ? null : new ArrayList<>(groups.values()).get(groups.size() - 1);
        if (g == null || g.kind != null) {
            return;
        }
        switch (sid) {
            case BAR -> {
                g.kind = BiffChart.Kind.BAR;
                g.overlap = (short) u16(d, 0);
                g.gap = u16(d, 2);
                int f = u16(d, 4);
                g.horizontal = (f & 1) != 0;
                g.stacked = (f & 2) != 0;
                g.percent = (f & 4) != 0;
            }
            case LINE, AREA -> {
                g.kind = sid == LINE ? BiffChart.Kind.LINE : BiffChart.Kind.AREA;
                int f = u16(d, 0);
                g.stacked = (f & 1) != 0;
                g.percent = (f & 2) != 0;
            }
            case PIE, BOP_POP -> {
                g.kind = BiffChart.Kind.PIE;
                g.firstSlice = sid == PIE ? u16(d, 0) : 0;
                g.donut = sid == PIE ? u16(d, 2) : 0;
            }
            case SCATTER -> g.kind = BiffChart.Kind.SCATTER;
            default -> g.kind = sid == RADAR_AREA ? BiffChart.Kind.AREA : BiffChart.Kind.RADAR;
        }
    }

    private AxisBuilder axis() {
        if (axisType < 0 || axisType > 1) {
            return null;
        }
        AxisBuilder a = axes[axisGroup][axisType];
        if (a == null) {
            a = new AxisBuilder();
            axes[axisGroup][axisType] = a;
        }
        return a;
    }

    private void valueRange(ValueRangeRecord v) {
        AxisBuilder a = axis();
        if (a == null) {
            return;
        }
        a.min = v.isAutomaticMinimum() ? null : finite(v.getMinimumAxisValue());
        a.max = v.isAutomaticMaximum() ? null : finite(v.getMaximumAxisValue());
        a.major = v.isAutomaticMajor() || v.getMajorIncrement() <= 0 ? null : finite(v.getMajorIncrement());
        a.reversed = v.isValuesInReverse();
        a.log = v.isLogarithmicScale();
        a.maxCross = v.isCrossCategoryAxisAtMaximum();
    }

    private static Double finite(double v) {
        return Double.isFinite(v) ? v : null;
    }

    private void finishText() {
        TextBuilder t = text;
        text = null;
        if (t.base && t.font >= 0 && base == null) {
            base = fonts.font(t.font, fonts.face(t.font));
            return;
        }
        if (t.text == null || t.text.isBlank()) {
            return;
        }
        BiffChart.Text value = fonts.font(t.font, t.text);
        switch (t.link) {
            case 1 -> title = value;
            case 2, 3 -> {
                int type = t.link == 2 ? 1 : 0;
                AxisBuilder a = axes[axisGroup][type];
                if (a == null) {
                    a = new AxisBuilder();
                    axes[axisGroup][type] = a;
                }
                a.title = value;
            }
            default -> {
            }
        }
    }

    private void cached(Record r) {
        int row;
        int col;
        Object value;
        if (r instanceof NumberRecord n) {
            row = n.getRow();
            col = n.getColumn();
            value = n;
        } else if (r instanceof LabelRecord l) {
            row = l.getRow();
            col = l.getColumn();
            value = l.getValue();
        } else {
            return;
        }
        if (col >= series.size() || row < 0 || row >= MAX_POINTS || (cache != 1 && cache != 2)) {
            return;
        }
        SeriesBuilder s = series.get(col);
        if (cache == 1) {
            if (value instanceof NumberRecord n && Double.isFinite(n.getValue())) {
                s.values.put(row, n.getValue());
            }
        } else if (value instanceof NumberRecord n) {
            s.xs.put(row, n.getValue());
            s.categories.put(row, fonts.number(n.getValue(), n.getXFIndex(), s.categoryFormat));
        } else {
            s.categories.put(row, (String) value);
        }
    }

    BiffChart build(List<Integer> autoFills, List<Integer> autoLines) {
        Map<Integer, List<BiffChart.Series>> bySeries = new HashMap<>();
        for (int i = 0; i < series.size(); i++) {
            SeriesBuilder s = series.get(i);
            GroupBuilder g = groups.get(s.group);
            boolean lines = g != null && (g.kind == BiffChart.Kind.LINE || g.kind == BiffChart.Kind.SCATTER
                    || g.kind == BiffChart.Kind.RADAR);
            bySeries.computeIfAbsent(s.group, k -> new ArrayList<>()).add(s.build(i, lines, autoFills, autoLines));
        }
        List<BiffChart.Group> out = new ArrayList<>();
        for (Map.Entry<Integer, GroupBuilder> e : groups.entrySet()) {
            GroupBuilder g = e.getValue();
            List<BiffChart.Series> list = bySeries.get(e.getKey());
            if (g.kind == null || list == null || list.isEmpty()) {
                continue;
            }
            out.add(new BiffChart.Group(g.kind, g.axisGroup, g.horizontal, g.stacked, g.percent, g.overlap, g.gap,
                    g.firstSlice, g.donut, g.vary, list));
        }
        if (out.isEmpty()) {
            return null;
        }
        BiffChart.Axis[] values = new BiffChart.Axis[2];
        BiffChart.Axis[] cats = new BiffChart.Axis[2];
        for (int g = 0; g < 2; g++) {
            values[g] = axes[g][1] == null ? null : axes[g][1].build(valueFormat(g));
            cats[g] = axes[g][0] == null ? null : axes[g][0].build(null);
        }
        float size = base == null ? 10 : base.size();
        return new BiffChart(title, out, legend(), values, cats, plotFill, areaFill, areaLine, size,
                base == null ? null : base.text());
    }

    private String valueFormat(int group) {
        AxisBuilder a = axes[group][1];
        if (a != null && a.format >= 0) {
            return fonts.format(a.format);
        }
        for (Map.Entry<Integer, GroupBuilder> e : groups.entrySet()) {
            if (e.getValue().axisGroup != group) {
                continue;
            }
            for (SeriesBuilder s : series) {
                if (s.group == e.getKey() && s.format > 0) {
                    return fonts.format(s.format);
                }
            }
        }
        return null;
    }

    private BiffChart.Legend legend() {
        if (legend == null) {
            return null;
        }
        String pos = switch (legend.getType()) {
            case 0 -> "b";
            case 1 -> "tr";
            case 2 -> "t";
            case 4 -> "l";
            default -> "r";
        };
        double[] box = null;
        if (legend.getType() == 7 && legend.getXSize() > 0 && legend.getYSize() > 0) {
            box = new double[] {legend.getXAxisUpperLeft() / 4000.0, legend.getYAxisUpperLeft() / 4000.0,
                legend.getXSize() / 4000.0, legend.getYSize() / 4000.0};
        }
        return new BiffChart.Legend(pos, box);
    }

    private static byte[] data(Record r, int sid) {
        if (sid == SERIES || sid == SERIES_TEXT || sid == 0x1051 || sid == LEGEND || sid == VALUE_RANGE
                || r instanceof NumberRecord || r instanceof LabelRecord) {
            return new byte[0];
        }
        byte[] all = r.serialize();
        byte[] d = new byte[Math.max(0, all.length - 4)];
        System.arraycopy(all, 4, d, 0, d.length);
        return d;
    }

    static int u16(byte[] d, int at) {
        return at + 1 < d.length ? (d[at] & 0xFF) | (d[at + 1] & 0xFF) << 8 : 0;
    }

    private static long u32(byte[] d, int at) {
        return (long) u16(d, at) | (long) u16(d, at + 2) << 16;
    }

    private static int rgb(byte[] d, int at) {
        return (d[at] & 0xFF) << 16 | (d[at + 1] & 0xFF) << 8 | d[at + 2] & 0xFF;
    }

    private static final class TextBuilder {
        final boolean base;

        int font = -1;

        int link;

        String text;

        TextBuilder(boolean base) {
            this.base = base;
        }
    }

    private static final class GroupBuilder {
        final int axisGroup;

        final boolean vary;

        BiffChart.Kind kind;

        boolean horizontal;

        boolean stacked;

        boolean percent;

        int overlap;

        int gap = 150;

        int firstSlice;

        int donut;

        GroupBuilder(int axisGroup, boolean vary) {
            this.axisGroup = axisGroup;
            this.vary = vary;
        }
    }

    private static final class AxisBuilder {
        Double min;

        Double max;

        Double major;

        boolean gridlines;

        boolean reversed;

        boolean log;

        boolean maxCross;

        boolean labels = true;

        int format = -1;

        BiffChart.Text title;

        BiffChart.Axis build(String numberFormat) {
            return new BiffChart.Axis(min, max, major, gridlines, title, numberFormat, reversed, log, !labels);
        }
    }

    private static final class SeriesBuilder {
        final int categoryCount;

        final int valueCount;

        final TreeMap<Integer, Double> values = new TreeMap<>();

        final TreeMap<Integer, Double> xs = new TreeMap<>();

        final TreeMap<Integer, String> categories = new TreeMap<>();

        final Map<Integer, Integer> points = new TreeMap<>();

        String name;

        int group;

        int format;

        int categoryFormat;

        int iss = -1;

        Integer fill;

        boolean fillSet;

        boolean fillAuto;

        Integer line;

        boolean lineSet;

        boolean lineAuto;

        float lineWidth = 0.75f;

        boolean markerAuto = true;

        String marker;

        Integer markerFill;

        int markerSize = 5;

        boolean smooth;

        SeriesBuilder(int categories, int values) {
            this.categoryCount = Math.max(0, Math.min(MAX_POINTS, categories));
            this.valueCount = Math.max(0, Math.min(MAX_POINTS, values));
        }

        BiffChart.Series build(int index, boolean lines, List<Integer> autoFills, List<Integer> autoLines) {
            int n = Math.max(valueCount, values.isEmpty() ? 0 : values.lastKey() + 1);
            double[] v = new double[n];
            double[] x = xs.isEmpty() ? null : new double[n];
            for (int i = 0; i < n; i++) {
                v[i] = values.getOrDefault(i, Double.NaN);
                if (x != null) {
                    x[i] = xs.getOrDefault(i, (double) (i + 1));
                }
            }
            int c = Math.max(categoryCount, categories.isEmpty() ? 0 : categories.lastKey() + 1);
            List<String> cats = new ArrayList<>(c);
            for (int i = 0; i < c; i++) {
                cats.add(categories.getOrDefault(i, ""));
            }
            int auto = iss >= 0 ? iss : index;
            Integer f = !fillSet || fillAuto ? autoFills.get(auto % autoFills.size()) : fill;
            Integer l = lineSet && !lineAuto ? line
                    : lineSet && line == null ? null : lines ? autoLines.get(auto % autoLines.size()) : 0;
            String symbol = markerAuto ? AUTO_MARKERS[auto % AUTO_MARKERS.length] : marker;
            Integer mfill = markerAuto ? l : markerFill;
            return new BiffChart.Series(name == null ? "Series" + (index + 1) : name, cats, v, x, f, l,
                    lineWidth, symbol, mfill, Map.copyOf(points), smooth);
        }
    }

    private static final String[] AUTO_MARKERS = {"diamond", "square", "triangle", "x", "star", "circle", "plus"};
}
