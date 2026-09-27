package stirling.software.officeconvert.sheet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import stirling.software.officeconvert.layout.PageLayout;
import stirling.software.officeconvert.layout.ParaDraft;
import stirling.software.officeconvert.table.TableDetection;

final class TableSplit {

    private static final float HEADING_SIZE = 1.25f;

    private TableSplit() {}

    static List<Object> parts(PageLayout.TableItem item, boolean dropHyphens) {
        TableDetection.Found t = item.table();
        if (item.rowEdges().length != t.rows() + 1) {
            return List.of(item);
        }
        int n = t.cells().size();
        float[] size = new float[n];
        List<Float> sizes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            CellText ct = CellText.of(item.cellParas().get(i), dropHyphens);
            size[i] = ct.text().isEmpty() ? 0 : ct.size();
            if (size[i] > 0) {
                sizes.add(size[i]);
            }
        }
        if (sizes.size() < 4 || t.rows() < 5) {
            return List.of(item);
        }
        float[] sorted = new float[sizes.size()];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = sizes.get(i);
        }
        Arrays.sort(sorted);
        float usual = sorted[sorted.length / 2];
        List<Object> out = new ArrayList<>();
        int from = 0;
        for (int r = 2; r < t.rows() - 2; r++) {
            int only = soleCell(t, r, size);
            if (only >= 0 && size[only] >= HEADING_SIZE * usual && !crosses(t, r)) {
                out.add(slice(item, from, r));
                out.addAll(item.cellParas().get(only));
                from = r + 1;
            }
        }
        if (from == 0) {
            return List.of(item);
        }
        out.add(slice(item, from, t.rows()));
        return out;
    }

    private static int soleCell(TableDetection.Found t, int r, float[] size) {
        int only = -1;
        for (int i = 0; i < t.cells().size(); i++) {
            if (t.cells().get(i).row() == r && size[i] > 0) {
                if (only >= 0) {
                    return -1;
                }
                only = i;
            }
        }
        return only;
    }

    private static boolean crosses(TableDetection.Found t, int r) {
        for (TableDetection.FoundCell c : t.cells()) {
            if (c.row() < r && c.row() + c.rowSpan() > r || c.row() == r && c.rowSpan() > 1) {
                return true;
            }
        }
        return false;
    }

    private static PageLayout.TableItem slice(PageLayout.TableItem item, int from, int to) {
        TableDetection.Found t = item.table();
        List<TableDetection.FoundCell> cells = new ArrayList<>();
        List<List<ParaDraft>> paras = new ArrayList<>();
        for (int i = 0; i < t.cells().size(); i++) {
            TableDetection.FoundCell c = t.cells().get(i);
            if (c.row() >= from && c.row() < to) {
                cells.add(new TableDetection.FoundCell(c.row() - from, c.col(), Math.min(c.rowSpan(), to - c.row()),
                        c.colSpan(), c.left(), c.top(), c.right(), c.bottom(), c.borderTop(), c.borderBottom(),
                        c.borderLeft(), c.borderRight(), c.fill(), c.hAlign(), c.vAlign(), c.text()));
                paras.add(item.cellParas().get(i));
            }
        }
        float[] edges = Arrays.copyOfRange(item.rowEdges(), from, to + 1);
        int header = from == 0 ? Math.min(t.headerRows(), to) : 0;
        TableDetection.Found part = new TableDetection.Found(to - from, t.cols(), t.colEdges(), t.ruled(), header,
                t.padding(), cells, t.left(), edges[0], t.right(), edges[edges.length - 1]);
        return new PageLayout.TableItem(part, paras, edges, item.pad());
    }
}
