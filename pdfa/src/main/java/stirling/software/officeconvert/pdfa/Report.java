package stirling.software.officeconvert.pdfa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

final class Report {

    private static final int MAX_WARNINGS = 200;

    private final Map<String, Integer> warnings = new LinkedHashMap<>();

    private final TreeSet<Integer> flattened = new TreeSet<>();

    private final TreeSet<String> substituted = new TreeSet<>();

    void warn(String message) {
        if (warnings.size() < MAX_WARNINGS || warnings.containsKey(message)) {
            warnings.merge(message, 1, Integer::sum);
        }
    }

    void flattened(int pageIndex) {
        flattened.add(pageIndex + 1);
    }

    void substituted(String description) {
        substituted.add(description);
    }

    List<String> warnings() {
        List<String> out = new ArrayList<>();
        warnings.forEach((m, n) -> out.add(n == 1 ? m : m + " (" + n + " times)"));
        if (!flattened.isEmpty()) {
            out.add("Drew the transparent parts of " + (flattened.size() == 1 ? "page " : "pages ") + pages()
                    + " as pictures, which PDF/A-1 needs");
        }
        for (String s : substituted) {
            out.add("Embedded " + s);
        }
        return out;
    }

    List<Integer> flattenedPages() {
        return List.copyOf(flattened);
    }

    List<String> substitutedFonts() {
        return List.copyOf(substituted);
    }

    private String pages() {
        StringBuilder b = new StringBuilder();
        int start = -1;
        int prev = -1;
        for (int p : flattened) {
            if (p == prev + 1) {
                prev = p;
                continue;
            }
            append(b, start, prev);
            start = p;
            prev = p;
        }
        append(b, start, prev);
        return b.toString();
    }

    private static void append(StringBuilder b, int start, int end) {
        if (start < 0) {
            return;
        }
        if (!b.isEmpty()) {
            b.append(", ");
        }
        b.append(start == end ? String.valueOf(start) : start + "-" + end);
    }
}
