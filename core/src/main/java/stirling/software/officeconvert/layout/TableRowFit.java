package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import stirling.software.officeconvert.table.PageContent.PdfWord;
import stirling.software.officeconvert.table.TableDetection.FoundCell;
import stirling.software.officeconvert.table.TableDetection;

final class TableRowFit {

    private static final float MIN_EDGE_GAP = 0.8f;

    private TableRowFit() {}

    static boolean fits(TableDetection.Found t, int row, List<PdfWord> words) {
        float top = Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        for (FoundCell c : t.cells()) {
            if (c.row() == row) {
                top = Math.min(top, c.top());
                bottom = Math.max(bottom, c.bottom());
            }
        }
        List<PdfWord> in = new ArrayList<>();
        for (PdfWord w : words) {
            float cx = (w.x() + w.right()) / 2f;
            if (w.baseline() > top && w.baseline() <= bottom + 1 && cx >= t.left() && cx <= t.right()) {
                in.add(w);
            }
        }
        in.sort(Comparator.comparingDouble(PdfWord::x));
        float[] edges = t.colEdges();
        Set<Integer> columns = new HashSet<>();
        for (PdfWord w : in) {
            if (straddles(w, edges)) {
                return false;
            }
            columns.add(column((w.x() + w.right()) / 2f, edges));
        }
        for (int i = 1; i < in.size(); i++) {
            PdfWord a = in.get(i - 1);
            PdfWord b = in.get(i);
            boolean acrossEdge = column((a.x() + a.right()) / 2f, edges) != column((b.x() + b.right()) / 2f, edges);
            if (acrossEdge && b.x() - a.right() < MIN_EDGE_GAP * (a.bottom() - a.top())) {
                return false;
            }
        }
        return columns.size() >= 2;
    }

    private static boolean straddles(PdfWord w, float[] edges) {
        for (int i = 1; i < edges.length - 1; i++) {
            if (w.x() < edges[i] - 1 && w.right() > edges[i] + 1) {
                return true;
            }
        }
        return false;
    }

    private static int column(float x, float[] edges) {
        int col = 0;
        for (int i = 1; i < edges.length - 1; i++) {
            if (x >= edges[i]) {
                col = i;
            }
        }
        return col;
    }
}
