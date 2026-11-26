package stirling.software.officeconvert.extract;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** The bounded sample retained for cross-page document statistics. */
public final class PageSamples {

    private static final int SAMPLE_ALL_BELOW = 40;

    private PageSamples() {}

    /** Contiguous, inclusive ranges of zero-based page indexes. */
    public static List<int[]> runs(int first, int last) {
        int count = last - first + 1;
        TreeSet<Integer> pages = new TreeSet<>();
        if (count <= SAMPLE_ALL_BELOW) {
            for (int i = first; i <= last; i++) {
                pages.add(i);
            }
        } else {
            for (int i = 0; i < 20; i++) {
                pages.add(first + i);
            }
            for (int i = 0; i < 5; i++) {
                pages.add(last - i);
            }
            for (int k = 1; k <= 15; k++) {
                int page = first + (int) ((long) count * k / 16);
                pages.add(page);
                pages.add(Math.min(last, page + 1));
            }
        }
        List<int[]> runs = new ArrayList<>();
        int start = -1;
        int prev = -2;
        for (int p : pages) {
            if (p != prev + 1) {
                if (start >= 0) {
                    runs.add(new int[] {start, prev});
                }
                start = p;
            }
            prev = p;
        }
        if (start >= 0) {
            runs.add(new int[] {start, prev});
        }
        return runs;
    }
}
