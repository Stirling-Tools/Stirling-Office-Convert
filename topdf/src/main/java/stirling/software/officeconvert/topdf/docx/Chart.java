package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;
import java.util.List;

import stirling.software.officeconvert.topdf.pdf.Stroke;

record Chart(Text title, List<Group> groups, List<String> categories, Legend legend, Axis values, Axis categoryAxis,
        Color fill, Stroke border, String font, float textSize, Color textColor, Layout plotLayout, Color plotFill,
        Stroke plotBorder, boolean date1904, Axis secondary) {

    Chart(Text title, List<Group> groups, List<String> categories, Legend legend, Axis values, Axis categoryAxis,
            Color fill, Stroke border, String font, float textSize, Color textColor) {
        this(title, groups, categories, legend, values, categoryAxis, fill, border, font, textSize, textColor, null,
                null, null, false, null);
    }

    enum Kind {
        BAR,
        LINE,
        AREA,
        PIE,
        DOUGHNUT,
        SCATTER
    }

    record Text(String text, float size, boolean bold, Color color) {}

    record Point(Color fill) {}

    // A marker: symbol name (circle, square, diamond, triangle, x, star, plus, dash, dot, none), size in points
    record Marker(String symbol, float size, Color fill, Color line) {}

    record Series(String name, double[] values, double[] xs, Color fill, Stroke line, boolean markers,
            List<Point> points, boolean smooth, Marker marker, Labels labels, Stroke outline, Shadow shadow) {

        Series(String name, double[] values, double[] xs, Color fill, Stroke line, boolean markers,
                List<Point> points) {
            this(name, values, xs, fill, line, markers, points, false, null, null, null, null);
        }
    }

    // An outer shadow: offset in points, blur radius in points and a colour carrying its alpha
    record Shadow(float dx, float dy, float blur, Color color) {}

    record Labels(boolean value, boolean percent, boolean category, boolean series, String position, String format) {

        Labels(boolean value, boolean percent, boolean category) {
            this(value, percent, category, false, null, null);
        }

        boolean any() {
            return value || percent || category || series;
        }
    }

    record Group(Kind kind, boolean horizontal, String grouping, List<Series> series, float gapWidth, float overlap,
            float holeSize, float firstSliceAngle, boolean varyColors, Labels labels, boolean secondary) {

        Group(Kind kind, boolean horizontal, String grouping, List<Series> series, float gapWidth, float overlap,
                float holeSize, float firstSliceAngle, boolean varyColors, Labels labels) {
            this(kind, horizontal, grouping, series, gapWidth, overlap, holeSize, firstSliceAngle, varyColors, labels,
                    false);
        }

        Group onSecondary() {
            return new Group(kind, horizontal, grouping, series, gapWidth, overlap, holeSize, firstSliceAngle,
                    varyColors, labels, true);
        }
    }

    record Legend(String position, float size, Layout layout, String font) {

        Legend(String position, float size) {
            this(position, size, null, null);
        }
    }

    // A manual layout in fractions of the chart: x and y are edges or offsets (factor), w and h are sizes
    record Layout(boolean inner, boolean xEdge, boolean yEdge, float x, float y, float w, float h) {}

    record Axis(boolean deleted, boolean gridlines, Color gridColor, Color lineColor, Double min, Double max,
            String format, float size, boolean reversed, Double majorUnit, Text title, Float rotation, int skip,
            boolean date, String labelPosition, boolean midCategory, float gridWidth, String majorTick, String font) {

        Axis(boolean deleted, boolean gridlines, Color gridColor, Color lineColor, Double min, Double max,
                String format, float size, boolean reversed) {
            this(deleted, gridlines, gridColor, lineColor, min, max, format, size, reversed, null, null, null, 0,
                    false, "nextTo", false, 0.75f, "none", null);
        }

        boolean labelsShown() {
            return !deleted && !"none".equals(labelPosition);
        }
    }
}
