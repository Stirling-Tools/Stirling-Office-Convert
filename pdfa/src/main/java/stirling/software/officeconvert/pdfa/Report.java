package stirling.software.officeconvert.pdfa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;

final class Report {

    private static final int MAX_WARNINGS = 200;

    // File-named warnings share at most this many slots, so fixed messages always have room.
    private static final int MAX_NAMED = 100;

    private static final int MAX_PER_KIND = 20;

    private static final int MAX_NAME = 40;

    private static final int MAX_MESSAGE = 300;

    private final Map<String, Integer> warnings = new LinkedHashMap<>();

    private final Map<String, Integer> kinds = new HashMap<>();

    private final Set<String> unverified = new LinkedHashSet<>();

    private int named;

    private long omitted;

    private final TreeSet<Integer> flattened = new TreeSet<>();

    private final TreeSet<String> substituted = new TreeSet<>();

    private final Map<COSDictionary, COSBase> actions = new IdentityHashMap<>();

    Map<COSDictionary, COSBase> actions() {
        return actions;
    }

    void warn(String message) {
        String m = clip(message, MAX_MESSAGE);
        if (warnings.containsKey(m)) {
            warnings.merge(m, 1, Integer::sum);
        } else if (warnings.size() < MAX_WARNINGS) {
            warnings.put(m, 1);
        } else {
            omitted++;
        }
    }

    /** A warning naming something from the file: {@code template} holds one {@code %s} for the clipped name. */
    void warn(String template, String name) {
        String m = clip(template.replace("%s", clip(name, MAX_NAME)), MAX_MESSAGE);
        if (warnings.containsKey(m)) {
            warnings.merge(m, 1, Integer::sum);
            return;
        }
        int kind = kinds.getOrDefault(template, 0);
        if (named >= MAX_NAMED || kind >= MAX_PER_KIND || warnings.size() >= MAX_WARNINGS) {
            omitted++;
            return;
        }
        kinds.put(template, kind + 1);
        named++;
        warnings.put(m, 1);
    }

    /** A rule the output may still break: warned, and listed in {@link PdfToPdfA.Result#unverified()}. */
    void unverified(String message) {
        warn(message);
        unverified.add(message);
    }

    List<String> unverified() {
        return List.copyOf(unverified);
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
        if (omitted > 0) {
            out.add(omitted + " more " + (omitted == 1 ? "warning" : "warnings") + " omitted");
        }
        return out;
    }

    static String clip(String s, int max) {
        String c = s == null ? "" : s.length() > max ? s.substring(0, max - 3) + "..." : s;
        StringBuilder b = new StringBuilder(c.length());
        c.chars().forEach(ch -> b.append(Character.isISOControl(ch) ? '?' : (char) ch));
        return b.toString();
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
