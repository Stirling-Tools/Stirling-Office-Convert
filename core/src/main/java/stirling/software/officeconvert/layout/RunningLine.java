package stirling.software.officeconvert.layout;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RunningLine {

    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    public final String signature;
    public final boolean top;
    public final float baseline;
    final Map<Integer, Line> occurrences = new HashMap<>();
    int buried;
    public int[] pageOffsets = new int[0];
    public boolean[] totalPages = new boolean[0];

    RunningLine(String signature, boolean top, float baseline) {
        this.signature = signature;
        this.top = top;
        this.baseline = baseline;
    }

    public Line sample() {
        return occurrences.get(samplePage());
    }

    public int samplePage() {
        int bestPage = Integer.MAX_VALUE;
        int actual = 0;
        for (Integer k : occurrences.keySet()) {
            int p = k == 0 ? Integer.MAX_VALUE - 1 : k;
            if (p < bestPage) {
                bestPage = p;
                actual = k;
            }
        }
        return actual;
    }

    public boolean onPage(int page) {
        return occurrences.containsKey(page);
    }

    public Set<Integer> pages() {
        return occurrences.keySet();
    }

    void resolveNumbers(int pageCount) {
        int runs = countRuns(sample().text());
        pageOffsets = new int[runs];
        totalPages = new boolean[runs];
        for (int k = 0; k < runs; k++) {
            Integer offset = null;
            boolean consistent = true;
            boolean allTotal = true;
            int seen = 0;
            for (Map.Entry<Integer, Line> e : occurrences.entrySet()) {
                Integer v = runValue(e.getValue().text(), k);
                if (v == null) {
                    consistent = false;
                    allTotal = false;
                    break;
                }
                seen++;
                int off = v - e.getKey();
                if (offset == null) {
                    offset = off;
                } else if (offset != off) {
                    consistent = false;
                }
                if (v != pageCount) {
                    allTotal = false;
                }
            }
            pageOffsets[k] = consistent && seen >= 2 && offset != null ? offset : Integer.MIN_VALUE;
            totalPages[k] = allTotal && seen >= 2;
        }
    }

    private static int countRuns(String text) {
        Matcher m = DIGITS.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static Integer runValue(String text, int k) {
        Matcher m = DIGITS.matcher(text);
        int i = 0;
        while (m.find()) {
            if (i++ == k) {
                try {
                    return Integer.parseInt(m.group());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
