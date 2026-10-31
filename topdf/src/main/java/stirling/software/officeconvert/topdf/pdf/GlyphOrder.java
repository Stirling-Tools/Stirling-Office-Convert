package stirling.software.officeconvert.topdf.pdf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import stirling.software.officeconvert.topdf.font.GlyphRun;

final class GlyphOrder {

    static final int SILENT = 1;

    static final int SPOKEN = 2;

    private GlyphOrder() {}

    // Glyphs in reading order, right-to-left runs from their right end as Word writes them unless horizontally
    // scaled Arabic (MuPDF measures a backward step in unscaled advances and splits such words; Hebrew does not
    // split, and drawn left to right MuPDF reads its words backwards); a group draws the glyph that carries its
    // text first and marks the others as saying nothing (SILENT opens, SPOKEN closes)
    static int[][] readingOrder(GlyphRun run, int[] groups, boolean logical) {
        int n = run.size();
        List<int[]> segments = new ArrayList<>();
        for (int i = 0; i < n; ) {
            int end = groups[i] > 0 ? groups[i] : i + 1;
            segments.add(new int[] {i, end});
            i = end;
        }
        if (run.rightToLeft() && logical) {
            Collections.reverse(segments);
            segments.sort(Comparator.comparingInt(s -> firstCharacter(run, s)));
        }
        int[] order = new int[n];
        int[] marks = new int[n];
        int k = 0;
        for (int[] s : segments) {
            if (s[1] - s[0] == 1) {
                order[k++] = s[0];
                continue;
            }
            int carrier = run.carrier(s[0], s[1]);
            marks[k] = SILENT;
            order[k++] = carrier;
            for (int i = s[0]; i < s[1]; i++) {
                if (i != carrier) {
                    order[k++] = i;
                }
            }
            marks[k - 1] = SPOKEN;
        }
        return new int[][] {order, marks};
    }

    static int leadingBlanks(GlyphRun run, int[] order, int[] marks) {
        int n = 0;
        while (n < order.length && marks[n] == 0 && run.glyph(order[n]) > 0) {
            String t = run.clusterText(order[n]);
            if (t == null || t.isEmpty() || !t.isBlank()) {
                break;
            }
            n++;
        }
        return n < order.length ? n : 0;
    }

    static int[] visualGroups(GlyphRun run) {
        int[] groups = run.groups();
        int[] out = groups.clone();
        int n = run.size();
        for (int i = 0; i < n; ) {
            if (groups[i] > 0) {
                i = groups[i];
                continue;
            }
            int j = i;
            while (j < n && groups[j] == 0 && marks(run.clusterText(j))) {
                j++;
            }
            if (j > i && j < n && groups[j] == 0 && !marks(run.clusterText(j))) {
                out[i] = j + 1;
                i = j + 1;
            } else {
                i = Math.max(j, i + 1);
            }
        }
        return out;
    }

    private static boolean marks(String text) {
        return text != null && TextOrder.marks(text);
    }
    static boolean arabic(String text) {
        return text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.ARABIC);
    }

    private static int firstCharacter(GlyphRun run, int[] segment) {
        int first = Integer.MAX_VALUE;
        for (int i = segment[0]; i < segment[1]; i++) {
            first = Math.min(first, run.cluster(i));
        }
        return first;
    }
}
