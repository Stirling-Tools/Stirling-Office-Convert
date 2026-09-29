package stirling.software.officeconvert.topdf.xlsx;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntPredicate;

import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCol;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCols;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTSheetFormatPr;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorksheet;

final class Columns {

    static final int MAX = 16384;

    private record Span(int last, double chars, boolean hidden, int style) {}

    private final TreeMap<Integer, Span> spans = new TreeMap<>();

    private final double defaultChars;

    private final double defaultWidth;

    private final PrintMetrics metrics;

    Columns(CTWorksheet ws, PrintMetrics metrics) {
        this.metrics = metrics;
        CTSheetFormatPr f = ws.isSetSheetFormatPr() ? ws.getSheetFormatPr() : null;
        double chars;
        if (f != null && f.isSetDefaultColWidth() && f.getDefaultColWidth() > 0) {
            chars = f.getDefaultColWidth();
        } else {
            chars = metrics.defaultColumnChars(f != null && f.isSetBaseColWidth() ? f.getBaseColWidth() : 8);
        }
        defaultWidth = metrics.columnPoints(chars);
        defaultChars = chars;
        for (CTCols cols : ws.getColsArray()) {
            for (CTCol c : cols.getColArray()) {
                int min = (int) Math.max(1, Math.min(MAX, c.getMin())) - 1;
                int max = (int) Math.max(1, Math.min(MAX, c.getMax())) - 1;
                if (max < min) {
                    continue;
                }
                double ch = c.isSetWidth() ? c.getWidth() : chars;
                boolean hidden = c.isSetHidden() && c.getHidden() || c.isSetWidth() && c.getWidth() <= 0;
                int style = c.isSetStyle() ? (int) c.getStyle() : -1;
                spans.put(min, new Span(max, ch, hidden, style));
            }
        }
    }

    double width(int col) {
        Span s = span(col);
        if (s == null) {
            return defaultWidth;
        }
        return s.hidden ? 0 : metrics.columnPoints(s.chars);
    }

    double chars(int col) {
        Span s = span(col);
        return s == null ? defaultChars : s.chars;
    }

    int style(int col) {
        Span s = span(col);
        return s == null ? -1 : s.style;
    }

    boolean anyStyle(IntPredicate test) {
        for (Span s : spans.values()) {
            if (s.style >= 0 && test.test(s.style)) {
                return true;
            }
        }
        return false;
    }

    private Span span(int col) {
        Map.Entry<Integer, Span> e = spans.floorEntry(col);
        if (e == null || e.getValue().last < col) {
            return null;
        }
        return e.getValue();
    }
}
