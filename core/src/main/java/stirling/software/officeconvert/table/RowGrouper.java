package stirling.software.officeconvert.table;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import stirling.software.officeconvert.table.PageContent.PdfWord;

final class RowGrouper {

    private static final float CONTINUATION_PITCH = 1.45f;

    private static final float CONTINUATION_EM = 1.5f;

    private static final float DENSE_ROW_EM = 1.6f;

    private static final Set<String> CONNECTORS =
            Set.of(
                    "and", "or", "of", "the", "to", "for", "in", "on", "with", "a", "an", "by",
                    "at", "from", "per", "&", "vs", "vs.", "und", "de", "la", "le", "et", "du",
                    "des", "y");

    private final List<CellLine> lines;
    private final ColumnProfile columns;
    private final boolean[] ruledBefore;

    private float tight;

    private boolean dense;

    private int[] rowOf;

    private RowGrouper(List<CellLine> lines, ColumnProfile columns, Separators separators) {
        this.lines = lines;
        this.columns = columns;
        this.ruledBefore = new boolean[lines.size()];
        for (int i = 1; i < lines.size(); i++) {
            ruledBefore[i] = separators.between(lines.get(i - 1).line(), lines.get(i).line());
        }
    }

    static List<LineRange> group(
            List<CellLine> lines, ColumnProfile columns, Separators separators) {
        RowGrouper grouper = new RowGrouper(lines, columns, separators);
        return grouper.isBanded(separators) ? grouper.bands() : grouper.byContinuation();
    }

    private boolean isBanded(Separators separators) {
        if (separators.count() < 3) {
            return false;
        }
        int bands = 1;
        for (boolean ruled : ruledBefore) {
            if (ruled) {
                bands++;
            }
        }
        return bands >= 3 && lines.size() <= bands * 3;
    }

    private List<LineRange> bands() {
        List<LineRange> rows = new ArrayList<>();
        int first = 0;
        for (int i = 1; i < lines.size(); i++) {
            if (ruledBefore[i]) {
                rows.add(new LineRange(first, i - 1));
                first = i;
            }
        }
        rows.add(new LineRange(first, lines.size() - 1));
        return rows;
    }

    private List<LineRange> byContinuation() {
        int n = lines.size();
        float minPitch = Float.MAX_VALUE;
        float fontSize = 0;
        for (int i = 0; i < n; i++) {
            fontSize = Math.max(fontSize, lines.get(i).line().fontSize());
            if (i > 0 && pitch(i) > 1f) {
                minPitch = Math.min(minPitch, pitch(i));
            }
        }
        tight = Math.max(CONTINUATION_PITCH * minPitch + 0.5f, CONTINUATION_EM * fontSize);

        int typicalFill = typicalFill();
        boolean[] anchor = new boolean[n];
        boolean anyAnchor = false;
        for (int i = 0; i < n; i++) {
            anchor[i] = !isFragment(i, typicalFill);
            anyAnchor |= anchor[i];
        }
        anchor[0] |= !anyAnchor;
        dense = anchorPitch(anchor) < DENSE_ROW_EM * fontSize;

        rowOf = new int[n];
        for (int i = 0; i < n; i++) {
            rowOf[i] = anchor[i] ? i : -1;
        }
        int i = 0;
        while (i < n) {
            if (anchor[i]) {
                i++;
                continue;
            }
            int runEnd = i;
            while (runEnd + 1 < n && !anchor[runEnd + 1]) {
                runEnd++;
            }
            attachRun(i, runEnd);
            i = runEnd + 1;
        }

        List<LineRange> rows = new ArrayList<>();
        int first = 0;
        for (int li = 1; li < n; li++) {
            if (rowOf[li] != rowOf[li - 1]) {
                rows.add(new LineRange(first, li - 1));
                first = li;
            }
        }
        rows.add(new LineRange(first, n - 1));
        return rows;
    }

    private void attachRun(int from, int to) {
        int above = from - 1;
        int k = from;
        while (k <= to && above >= 0 && !ruledBefore[k] && acceptsAbove(rowOf[above], k)) {
            rowOf[k] = rowOf[above];
            k++;
        }
        int ownFrom = k;
        int ownTo = to;
        if (to + 1 < lines.size()) {
            int below = to + 1;
            while (ownTo >= ownFrom && !ruledBefore[ownTo + 1] && acceptsBelow(below, ownTo)) {
                rowOf[ownTo] = below;
                ownTo--;
            }
        }
        for (int m = ownFrom; m <= ownTo; m++) {
            rowOf[m] = m;
        }
    }

    private float pitch(int i) {
        return lines.get(i).baseline() - lines.get(i - 1).baseline();
    }

    private int typicalFill() {
        List<Integer> fills = new ArrayList<>();
        for (CellLine line : lines) {
            if (line.filledCount() >= 2) {
                fills.add(line.filledCount());
            }
        }
        fills.sort(Integer::compare);
        return fills.isEmpty() ? 2 : fills.get(fills.size() / 2);
    }

    private boolean isFragment(int i, int typicalFill) {
        CellLine line = lines.get(i);
        int filled = 0;
        for (int c = 0; c < line.columns(); c++) {
            if (line.has(c)) {
                if (columns.numeric()[c]) {
                    return false;
                }
                filled++;
            }
        }
        if (filled == 0) {
            return true;
        }
        if (filled < typicalFill && (filled == 1 || filled * 2 <= typicalFill)) {
            return true;
        }
        if (i == 0 || pitch(i) > tight) {
            return false;
        }
        CellLine prev = lines.get(i - 1);
        for (int c = 0; c < line.columns(); c++) {
            if (line.has(c)
                    && (!CellText.wrapsInto(prev.cell(c), line.cell(c).getFirst(), wrap(c))
                            || startsNewItem(prev.cell(c), line.cell(c)))) {
                return false;
            }
        }
        return true;
    }

    private float wrap(int col) {
        return columns.wrapWidths()[col];
    }

    private float anchorPitch(boolean[] anchor) {
        List<Float> pitches = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (anchor[i] && anchor[i - 1]) {
                pitches.add(pitch(i));
            }
        }
        if (pitches.isEmpty()) {
            return Float.MAX_VALUE;
        }
        pitches.sort(Float::compare);
        return pitches.get(pitches.size() / 2);
    }

    private boolean acceptsAbove(int rowAnchor, int k) {
        if (pitch(k) > tight) {
            return false;
        }
        CellLine line = lines.get(k);
        for (int c = 0; c < line.columns(); c++) {
            if (!line.has(c)) {
                continue;
            }
            List<PdfWord> above = lastCellAbove(rowAnchor, k, c);
            if (above == null) {
                return false;
            }
            boolean wraps =
                    CellText.wrapsInto(above, line.cell(c).getFirst(), wrap(c))
                            && !startsNewItem(above, line.cell(c));
            if (!wraps && (dense || c == columns.key())) {
                return false;
            }
        }
        return true;
    }

    private List<PdfWord> lastCellAbove(int rowAnchor, int k, int c) {
        for (int li = k - 1; li >= 0 && rowOf[li] == rowAnchor; li--) {
            if (lines.get(li).has(c)) {
                return lines.get(li).cell(c);
            }
        }
        return null;
    }

    private boolean acceptsBelow(int a, int k) {
        if (pitch(k + 1) > tight) {
            return false;
        }
        CellLine line = lines.get(k);
        CellLine anchor = lines.get(a);
        for (int c = 0; c < line.columns(); c++) {
            if (line.has(c)
                    && anchor.has(c)
                    && !CellText.wrapsInto(line.cell(c), anchor.cell(c).getFirst(), wrap(c))) {
                return false;
            }
        }
        return true;
    }

    static boolean startsNewItem(List<PdfWord> above, List<PdfWord> next) {
        if (above.isEmpty() || next.isEmpty()) {
            return false;
        }
        String aboveText = TextLine.joinWords(above);
        String nextText = TextLine.joinWords(next);
        if (aboveText.equals(aboveText.toUpperCase(Locale.ROOT))
                && nextText.equals(nextText.toUpperCase(Locale.ROOT))) {
            return false;
        }
        char start = next.getFirst().text().charAt(0);
        if (!Character.isUpperCase(start) && !Character.isDigit(start)) {
            return false;
        }
        String last = above.getLast().text();
        char end = last.charAt(last.length() - 1);
        if (end == ',' || end == '-' || end == '/' || end == '(' || end == '&') {
            return false;
        }
        return !CONNECTORS.contains(last.toLowerCase(Locale.ROOT));
    }

    static void joinRuledHeader(List<LineRange> rows, List<CellLine> lines, Separators separators) {
        int headerEnd = -1;
        for (int r = 1; r < rows.size() && r <= 4; r++) {
            if (separators.between(
                    lines.get(rows.get(r - 1).last()).line(),
                    lines.get(rows.get(r).first()).line())) {
                headerEnd = r;
                break;
            }
        }
        if (headerEnd < 2) {
            return;
        }
        for (int r = headerEnd - 1; r >= 1; r--) {
            Set<Integer> above = filledColumns(rows.get(r - 1), lines);
            Set<Integer> below = filledColumns(rows.get(r), lines);
            TextLine upper = lines.get(rows.get(r - 1).last()).line();
            TextLine lower = lines.get(rows.get(r).first()).line();
            boolean tight = lower.baseline() - upper.baseline() <= 1.4f * upper.fontSize();
            if (tight
                    && above.containsAll(below)
                    && below.size() < above.size()
                    && !hasNumber(rows.get(r), lines)) {
                rows.set(r - 1, rows.get(r - 1).join(rows.remove(r)));
            }
        }
    }

    static Set<Integer> filledColumns(LineRange row, List<CellLine> lines) {
        Set<Integer> filled = new HashSet<>();
        for (int li = row.first(); li <= row.last(); li++) {
            CellLine line = lines.get(li);
            for (int c = 0; c < line.columns(); c++) {
                if (line.has(c)) {
                    filled.add(c);
                }
            }
        }
        return filled;
    }

    private static boolean hasNumber(LineRange row, List<CellLine> lines) {
        for (int li = row.first(); li <= row.last(); li++) {
            for (List<PdfWord> cell : lines.get(li).cells()) {
                if (!cell.isEmpty() && NumericValue.parse(TextLine.joinWords(cell)).isPresent()) {
                    return true;
                }
            }
        }
        return false;
    }
}
