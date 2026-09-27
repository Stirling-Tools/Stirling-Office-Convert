package stirling.software.officeconvert.layout;

import java.util.Locale;

import stirling.software.officeconvert.table.TableDetection;

public final class LayoutDump {

    private LayoutDump() {}

    public static String dump(PageLayout layout) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "=== page %d (%.0fx%.0f dir %d)%n", layout.page().index() + 1,
                layout.page().width(), layout.page().height(), layout.page().direction()));
        for (PageLayout.Band band : layout.bands()) {
            sb.append(String.format(Locale.ROOT, " band %.1f..%.1f cols=%d%n", band.top(), band.bottom(), band.columns().size()));
            for (PageLayout.Column col : band.columns()) {
                sb.append(String.format(Locale.ROOT, "  col %.1f..%.1f%n", col.left(), col.right()));
                for (PageLayout.Item it : col.items()) {
                    sb.append(String.format(Locale.ROOT, "   [%6.1f %6.1f %6.1f %6.1f] ", it.x(), it.top(), it.right(), it.bottom()));
                    switch (it) {
                        case PageLayout.ParaItem p -> sb.append(String.format(Locale.ROOT, "PARA %s %s l=%.1f f=%.1f r=%.1f n=%d | %s",
                                p.para().role, p.para().align, p.para().left, p.para().first, p.para().right,
                                p.para().lines.size(), clip(p.para().text())));
                        case PageLayout.TableItem t -> {
                            TableDetection.Found f = t.table();
                            sb.append(String.format(Locale.ROOT, "TABLE %dx%d ruled=%s", f.rows(), f.cols(), f.ruled()));
                            for (TableDetection.FoundCell c : f.cells()) {
                                sb.append(String.format(Locale.ROOT, "%n      (%d,%d %dx%d) %s", c.row(), c.col(), c.rowSpan(), c.colSpan(), clip(c.text())));
                            }
                        }
                        case PageLayout.ImageItem i -> sb.append("IMAGE bg=" + i.background());
                        case PageLayout.FigureItem f -> sb.append("FIGURE");
                        case PageLayout.FloatItem f -> sb.append("FLOAT gap=" + f.gap());
                        case PageLayout.PictureRow r -> sb.append("PICTURE ROW x" + r.pictures().size());
                        case PageLayout.TextBoxItem t -> sb.append("TEXTBOX paras=" + t.paras().size());
                    }
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String clip(String s) {
        s = s.replace('\n', '/');
        return s.length() > 90 ? s.substring(0, 90) + "..." : s;
    }
}
