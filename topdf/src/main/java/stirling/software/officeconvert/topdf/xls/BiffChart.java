package stirling.software.officeconvert.topdf.xls;

import java.util.List;
import java.util.Map;

record BiffChart(Text title, List<Group> groups, Legend legend, Axis[] values, Axis[] categories, Integer plotFill,
        Integer areaFill, Integer areaLine, float textSize, String font, int dataTable, boolean plotVisibleOnly) {

    enum Kind {
        BAR,
        LINE,
        AREA,
        PIE,
        SCATTER,
        RADAR,
        FILLED_RADAR
    }

    record Text(String text, float size, boolean bold) {}

    record Group(Kind kind, int axisGroup, boolean horizontal, boolean stacked, boolean percent, int overlap, int gap,
            int firstSlice, int donut, boolean vary, List<Series> series, boolean threeD, Stock stock) {}

    record Stock(boolean hiLow, Integer hiLowColor, float hiLowWidth, boolean bars, Integer up, Integer down, Integer edge, int gap) {}

    record Series(String name, List<String> categories, double[] values, double[] xs, Integer fill, Integer line,
            float lineWidth, String marker, Integer markerFill, Map<Integer, Integer> points, boolean smooth,
            Labels labels, Numbers categoryNumbers, List<ErrorBar> errors, List<Trend> trends) {}

    record ErrorBar(boolean x, boolean plus, int source, double value, double[] values, boolean cap, Integer line,
            float width) {}

    record Trend(String type, int order, double forward, double backward, Integer line, float width) {}

    record Numbers(String format, double[] values) {}

    record Labels(int flags, String position, float size, boolean bold, Integer color, String format,
            Map<Integer, Integer> points) {}

    record Legend(String position, double[] box, int[] fill, int[] line, Text font) {}

    record Axis(Double min, Double max, Double major, boolean gridlines, Integer gridColor, Text title, String format,
            boolean reversed, boolean log, boolean deleted, Integer line, Text font) {}
}
