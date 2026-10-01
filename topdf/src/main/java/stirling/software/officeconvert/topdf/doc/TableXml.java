package stirling.software.officeconvert.topdf.doc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

final class TableXml {

    private static final String[] SIDES = {"top", "left", "bottom", "right"};

    private final Story story;

    private TableXml(Story story) {
        this.story = story;
    }

    private record Row(List<int[]> cells, Tap tap, int[] edges) {}

    static void write(Story story, List<Story.Par> ps, int from, int to, int level, StringBuilder out)
            throws IOException {
        new TableXml(story).table(ps, from, to, level, out);
    }

    private void table(List<Story.Par> ps, int from, int to, int level, StringBuilder out) throws IOException {
        List<Row> rows = rows(ps, from, to, level);
        if (rows.isEmpty()) {
            return;
        }
        TreeSet<Integer> grid = new TreeSet<>();
        for (Row r : rows) {
            for (int e : r.edges) {
                grid.add(e);
            }
        }
        List<Integer> cols = new ArrayList<>(grid);
        Tap first = rows.get(0).tap;
        int[] pad = margins(first);
        out.append("<w:tbl><w:tblPr>");
        floating(first, out);
        out.append("<w:tblW w:w=\"0\" w:type=\"auto\"/>");
        if (first.jc == 1) {
            out.append("<w:jc w:val=\"center\"/>");
        } else if (first.jc == 2) {
            out.append("<w:jc w:val=\"right\"/>");
        }
        out.append("<w:tblInd w:w=\"").append(cols.get(0) + pad[1]).append("\" w:type=\"dxa\"/>");
        if (first.bidi) {
            out.append("<w:bidiVisual/>");
        }
        out.append("<w:tblLayout w:type=\"fixed\"/><w:tblCellMar>");
        for (int k = 0; k < 4; k++) {
            out.append("<w:").append(SIDES[k]).append(" w:w=\"").append(pad[k]).append("\" w:type=\"dxa\"/>");
        }
        out.append("</w:tblCellMar><w:tblLook w:val=\"0000\"/></w:tblPr><w:tblGrid>");
        for (int i = 1; i < cols.size(); i++) {
            out.append("<w:gridCol w:w=\"").append(cols.get(i) - cols.get(i - 1)).append("\"/>");
        }
        out.append("</w:tblGrid>");
        for (Row r : rows) {
            row(ps, r, cols, level, pad, out);
        }
        out.append("</w:tbl>");
    }

    private List<Row> rows(List<Story.Par> ps, int from, int to, int level) {
        List<Row> rows = new ArrayList<>();
        Tap last = null;
        int k = from;
        while (k < to) {
            int r = k;
            while (r < to && !(ps.get(r).level() == level && ps.get(r).rowEnd())) {
                r++;
            }
            List<int[]> cells = new ArrayList<>();
            int c = k;
            for (int m = k; m < r; m++) {
                Story.Par p = ps.get(m);
                if (p.level() == level && p.cellEnd()) {
                    cells.add(new int[] {c, m + 1});
                    c = m + 1;
                }
            }
            if (c < r) {
                cells.add(new int[] {c, r});
            }
            Tap tap = r < to ? Tap.read(story.direct(ps.get(r))) : last != null ? last : new Tap();
            last = tap;
            if (!cells.isEmpty()) {
                rows.add(new Row(cells, tap, edges(tap, cells.size())));
            }
            k = r < to ? r + 1 : to;
        }
        return rows;
    }

    private static int[] edges(Tap tap, int count) {
        int n = Math.max(count, tap.cells);
        int[] e = new int[n + 1];
        int prev = 0;
        for (int i = 0; i <= n; i++) {
            if (i < tap.centers.length) {
                e[i] = tap.centers[i];
            } else {
                e[i] = (i == 0 ? 0 : e[i - 1]) + 1440;
            }
            if (i > 0 && e[i] <= prev) {
                e[i] = prev + 1;
            }
            prev = e[i];
        }
        return e;
    }

    private static int[] margins(Tap t) {
        int[] m = {0, t.gapHalf, 0, t.gapHalf};
        if (t.padding != null) {
            for (int k = 0; k < 4; k++) {
                if (t.padding[k] >= 0) {
                    m[k] = t.padding[k];
                }
            }
        }
        return m;
    }

    private void row(List<Story.Par> ps, Row r, List<Integer> cols, int level, int[] table, StringBuilder out)
            throws IOException {
        int[] rowPad = margins(r.tap);
        Tap t = r.tap;
        int before = cols.indexOf(r.edges[0]);
        int n = r.edges.length - 1;
        int after = cols.size() - 1 - cols.indexOf(r.edges[n]);
        out.append("<w:tr><w:trPr>");
        if (before > 0) {
            out.append("<w:gridBefore w:val=\"").append(before).append("\"/><w:wBefore w:w=\"")
                    .append(r.edges[0] - cols.get(0)).append("\" w:type=\"dxa\"/>");
        }
        if (after > 0) {
            out.append("<w:gridAfter w:val=\"").append(after).append("\"/><w:wAfter w:w=\"")
                    .append(cols.get(cols.size() - 1) - r.edges[n]).append("\" w:type=\"dxa\"/>");
        }
        if (t.cantSplit) {
            out.append("<w:cantSplit/>");
        }
        if (t.height != 0) {
            out.append("<w:trHeight w:val=\"").append(Math.abs(t.height)).append("\" w:hRule=\"")
                    .append(t.height < 0 ? "exact" : "atLeast").append("\"/>");
        }
        if (t.header) {
            out.append("<w:tblHeader/>");
        }
        out.append("</w:trPr>");
        int i = 0;
        while (i < r.cells.size()) {
            Tap.Cell tc = i < t.tc.length ? t.tc[i] : null;
            int end = i + 1;
            if (tc != null && tc.firstMerged()) {
                while (end < r.cells.size() && end < t.tc.length && t.tc[end].merged() && !t.tc[end].firstMerged()) {
                    end++;
                }
            }
            int left = r.edges[i];
            int right = r.edges[Math.min(end, n)];
            int span = cols.indexOf(right) - cols.indexOf(left);
            int[] pad = tc != null && tc.padding != null ? merge(rowPad, tc.padding) : rowPad;
            cell(ps, r.cells.get(i), tc, right - left, span, Arrays.equals(pad, table) ? null : pad, level,
                    out);
            i = end;
        }
        out.append("</w:tr>");
    }

    private static int[] merge(int[] row, int[] cell) {
        int[] m = row.clone();
        for (int k = 0; k < 4; k++) {
            if (cell[k] >= 0) {
                m[k] = cell[k];
            }
        }
        return m;
    }

    private void cell(List<Story.Par> ps, int[] range, Tap.Cell tc, int width, int span, int[] pad, int level,
            StringBuilder out) throws IOException {
        out.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(width).append("\" w:type=\"dxa\"/>");
        if (span > 1) {
            out.append("<w:gridSpan w:val=\"").append(span).append("\"/>");
        }
        if (tc != null && tc.vertMerge) {
            out.append(tc.vertRestart ? "<w:vMerge w:val=\"restart\"/>" : "<w:vMerge/>");
        }
        if (tc != null) {
            out.append("<w:tcBorders>");
            for (int k = 0; k < 4; k++) {
                BorderXml.Line l = tc.borders[k];
                if (l == null || l.none()) {
                    out.append("<w:").append(SIDES[k]).append(" w:val=\"nil\"/>");
                } else {
                    BorderXml.side(out, SIDES[k], l);
                }
            }
            out.append("</w:tcBorders>");
            if (tc.shading != null) {
                out.append(tc.shading);
            }
            if (pad != null) {
                out.append("<w:tcMar>");
                for (int k = 0; k < 4; k++) {
                    out.append("<w:").append(SIDES[k]).append(" w:w=\"").append(pad[k]).append("\" w:type=\"dxa\"/>");
                }
                out.append("</w:tcMar>");
            }
            if (tc.vertical()) {
                out.append("<w:textDirection w:val=\"").append(tc.backward() ? "btLr" : "tbRl").append("\"/>");
            }
            if (tc.vertAlign == 1) {
                out.append("<w:vAlign w:val=\"center\"/>");
            } else if (tc.vertAlign == 2) {
                out.append("<w:vAlign w:val=\"bottom\"/>");
            }
        }
        out.append("</w:tcPr>");
        int mark = out.length();
        story.blocks(ps, range[0], range[1], level, out);
        if (out.length() == mark || out.lastIndexOf("</w:tbl>") == out.length() - 8) {
            out.append("<w:p/>");
        }
        out.append("</w:tc>");
    }

    private static void floating(Tap t, StringBuilder out) {
        Tap.Floating f = t.floating;
        if (f == null) {
            return;
        }
        out.append("<w:tblpPr w:leftFromText=\"").append(Math.max(0, f.left)).append("\" w:rightFromText=\"")
                .append(Math.max(0, f.right)).append("\" w:topFromText=\"").append(Math.max(0, f.top))
                .append("\" w:bottomFromText=\"").append(Math.max(0, f.bottom)).append("\" w:vertAnchor=\"")
                .append(switch (f.pcVert) {
                    case 0 -> "margin";
                    case 1 -> "page";
                    default -> "text";
                }).append("\" w:horzAnchor=\"").append(switch (f.pcHorz) {
                    case 1 -> "margin";
                    case 2 -> "page";
                    default -> "text";
                }).append('"');
        String xAlign = switch (f.dxaAbs) {
            case -4 -> "center";
            case -8 -> "right";
            case -12 -> "inside";
            case -16 -> "outside";
            default -> null;
        };
        if (xAlign != null) {
            out.append(" w:tblpXSpec=\"").append(xAlign).append('"');
        } else {
            out.append(" w:tblpX=\"").append(f.dxaAbs).append('"');
        }
        String yAlign = switch (f.dyaAbs) {
            case -4 -> "top";
            case -8 -> "center";
            case -12 -> "bottom";
            case -16 -> "inside";
            case -20 -> "outside";
            default -> null;
        };
        if (yAlign != null) {
            out.append(" w:tblpYSpec=\"").append(yAlign).append('"');
        } else {
            out.append(" w:tblpY=\"").append(f.dyaAbs).append('"');
        }
        out.append("/>");
    }
}
