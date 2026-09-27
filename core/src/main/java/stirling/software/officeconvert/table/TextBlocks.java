package stirling.software.officeconvert.table;

import java.util.ArrayList;
import java.util.List;

final class TextBlocks {

    private static final float BREAK_EM = 2.2f;

    private static final float MIN_BREAK = 12f;

    private static final float NARROW_SHARE = 0.4f;

    private static final int PROSE_WORDS = 8;

    record Block(int start, int end, int trailing) {}

    private TextBlocks() {}

    static List<Block> find(List<ChunkedLine> lines) {
        List<Block> blocks = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            if (!lines.get(i).tabular()) {
                i++;
                continue;
            }
            int start = i;
            int end = blockEnd(lines, start);
            int typical = medianChunks(lines, start, end);
            if (end - start >= 2
                    && outsizedGap(lines, start + 1, start + 2)
                    && lines.get(start).chunks().size() * 2 <= typical) {
                start++;
            }
            if (end - start >= 2
                    && outsizedGap(lines, end, end - 1)
                    && lines.get(end).chunks().size() * 2 <= typical) {
                end--;
            }
            blocks.add(new Block(start, end, trailingLines(lines, start, end)));
            i = end + 1;
        }
        return blocks;
    }

    private static int blockEnd(List<ChunkedLine> lines, int start) {
        int end = start;
        float left = lines.get(start).line().x();
        float right = lines.get(start).line().right();
        for (int k = start + 1; k < lines.size(); k++) {
            TextLine prev = lines.get(k - 1).line();
            TextLine line = lines.get(k).line();
            if (line.top() - prev.bottom() > Math.max(BREAK_EM * prev.fontSize(), MIN_BREAK)) {
                break;
            }
            if (lines.get(k).tabular()) {
                end = k;
                left = Math.min(left, line.x());
                right = Math.max(right, line.right());
                continue;
            }
            float width = line.right() - line.x();
            if (line.x() <= left + 2f
                    && width > (right - left) * 0.5f
                    && line.words().size() >= PROSE_WORDS) {
                break;
            }
            int reach = width < (right - left) * NARROW_SHARE ? 3 : 2;
            if (!tabularWithin(lines, k + 1, k + reach)) {
                break;
            }
        }
        return end;
    }

    private static boolean tabularWithin(List<ChunkedLine> lines, int from, int to) {
        for (int k = from; k < lines.size() && k <= to; k++) {
            if (lines.get(k).tabular()) {
                return true;
            }
        }
        return false;
    }

    private static int medianChunks(List<ChunkedLine> lines, int from, int to) {
        List<Integer> counts = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            counts.add(lines.get(i).chunks().size());
        }
        counts.sort(Integer::compare);
        return counts.get(counts.size() / 2);
    }

    private static boolean outsizedGap(List<ChunkedLine> lines, int k, int other) {
        TextLine line = lines.get(k).line();
        float gap = line.top() - lines.get(k - 1).line().bottom();
        float otherGap = lines.get(other).line().top() - lines.get(other - 1).line().bottom();
        return gap > Math.max(2f * otherGap, 0) + 2f + line.fontSize() * 0.5f;
    }

    private static int trailingLines(List<ChunkedLine> lines, int start, int end) {
        float left = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        for (int i = start; i <= end; i++) {
            left = Math.min(left, lines.get(i).line().x());
            right = Math.max(right, lines.get(i).line().right());
        }
        int n = 0;
        for (int k = end + 1; k < lines.size() && n < 2; k++) {
            TextLine prev = lines.get(k - 1).line();
            TextLine line = lines.get(k).line();
            boolean tight = line.baseline() - prev.baseline() <= 1.5f * prev.fontSize();
            boolean narrow = line.right() - line.x() < (right - left) * 0.5f;
            boolean inside = line.x() >= left - 1f && line.right() <= right + 1f;
            if (lines.get(k).tabular() || !tight || !narrow || !inside) {
                break;
            }
            n++;
        }
        return n;
    }
}
