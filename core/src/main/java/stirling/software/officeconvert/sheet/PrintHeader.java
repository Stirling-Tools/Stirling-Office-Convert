package stirling.software.officeconvert.sheet;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import stirling.software.officeconvert.layout.RunningLine;

final class PrintHeader {

    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    private PrintHeader() {}

    static String of(List<RunningLine> lines, int page, int pageCount, List<RunningLine> skip) {
        StringBuilder sb = new StringBuilder();
        for (RunningLine r : lines) {
            if (!r.onPage(page) || r.sample() == null || skip.contains(r)) {
                continue;
            }
            String text = numbered(r, page, pageCount);
            if (!text.isBlank()) {
                sb.append(sb.isEmpty() ? "" : "\n").append(text.strip());
            }
        }
        return sb.toString();
    }

    private static String numbered(RunningLine r, int page, int pageCount) {
        Matcher m = DIGITS.matcher(r.sample().text());
        StringBuilder sb = new StringBuilder();
        int k = 0;
        while (m.find()) {
            String value = m.group();
            if (k < r.pageOffsets.length && r.pageOffsets[k] != Integer.MIN_VALUE) {
                value = Integer.toString(page + r.pageOffsets[k]);
            } else if (k < r.totalPages.length && r.totalPages[k]) {
                value = Integer.toString(pageCount);
            }
            m.appendReplacement(sb, value);
            k++;
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
