package stirling.software.officeconvert.topdf.xlsx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import org.apache.poi.ss.util.CellRangeAddress;

// A centred interval tree over rows; merges crossing one row never overlap, so each node is searched by column
final class MergeIndex {

    private static final Comparator<CellRangeAddress> BY_COLUMN = Comparator
            .comparingInt(CellRangeAddress::getFirstColumn).thenComparingInt(CellRangeAddress::getFirstRow);

    private record Node(int center, CellRangeAddress[] byColumn, Node left, Node right) {}

    private final Node root;

    MergeIndex(List<CellRangeAddress> merges) {
        this.root = build(merges);
    }

    private static Node build(List<CellRangeAddress> list) {
        if (list.isEmpty()) {
            return null;
        }
        int[] points = new int[list.size() * 2];
        for (int i = 0; i < list.size(); i++) {
            points[2 * i] = list.get(i).getFirstRow();
            points[2 * i + 1] = list.get(i).getLastRow();
        }
        Arrays.sort(points);
        int center = points[points.length / 2];
        List<CellRangeAddress> here = new ArrayList<>();
        List<CellRangeAddress> left = new ArrayList<>();
        List<CellRangeAddress> right = new ArrayList<>();
        for (CellRangeAddress m : list) {
            if (m.getLastRow() < center) {
                left.add(m);
            } else if (m.getFirstRow() > center) {
                right.add(m);
            } else {
                here.add(m);
            }
        }
        CellRangeAddress[] sorted = here.toArray(new CellRangeAddress[0]);
        Arrays.sort(sorted, BY_COLUMN);
        return new Node(center, sorted, build(left), build(right));
    }

    CellRangeAddress covering(int row, int col) {
        Node n = root;
        while (n != null) {
            CellRangeAddress[] a = n.byColumn;
            int at = floor(a, col);
            for (int i = at; i >= 0 && i > at - 4; i--) {
                if (a[i].isInRange(row, col)) {
                    return a[i];
                }
            }
            if (row == n.center) {
                return null;
            }
            n = row < n.center ? n.left : n.right;
        }
        return null;
    }

    void intersecting(int r0, int r1, int c0, int c1, Consumer<CellRangeAddress> out) {
        visit(root, r0, r1, c0, c1, out);
    }

    private static void visit(Node n, int r0, int r1, int c0, int c1, Consumer<CellRangeAddress> out) {
        while (n != null) {
            CellRangeAddress[] a = n.byColumn;
            for (int i = Math.max(0, floor(a, c0) - 3); i < a.length; i++) {
                CellRangeAddress m = a[i];
                if (m.getFirstColumn() > c1) {
                    break;
                }
                if (m.getLastColumn() >= c0 && m.getFirstRow() <= r1 && m.getLastRow() >= r0) {
                    out.accept(m);
                }
            }
            if (r1 < n.center) {
                n = n.left;
            } else if (r0 > n.center) {
                n = n.right;
            } else {
                visit(n.left, r0, r1, c0, c1, out);
                n = n.right;
            }
        }
    }

    private static int floor(CellRangeAddress[] a, int col) {
        int lo = 0;
        int hi = a.length - 1;
        int best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid].getFirstColumn() <= col) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }
}
