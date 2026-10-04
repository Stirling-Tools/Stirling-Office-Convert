package stirling.software.officeconvert.topdf.xls;

import java.util.List;
import java.util.Map;

record BiffChart(Text title, List<Group> groups, Legend legend, Axis[] values, Axis[] categories, Integer plotFill,
        Integer areaFill, Integer areaLine, float textSize, String font) {

    enum Kind {
        BAR,
        LINE,
        AREA,
        PIE,
        SCATTER,
        RADAR
    }

    record Text(String text, float size, boolean bold) {}

    record Group(Kind kind, int axisGroup, boolean horizontal, boolean stacked, boolean percent, int overlap, int gap,
            int firstSlice, int donut, boolean vary, List<Series> series) {}

    record Series(String name, List<String> categories, double[] values, double[] xs, Integer fill, Integer line,
            float lineWidth, String marker, Integer markerFill, Map<Integer, Integer> points, boolean smooth) {}

    record Legend(String position, double[] box) {}

    record Axis(Double min, Double max, Double major, boolean gridlines, Text title, String format, boolean reversed,
            boolean log, boolean deleted) {}
}
