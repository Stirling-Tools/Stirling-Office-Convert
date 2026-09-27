package stirling.software.officeconvert.table;

import java.util.List;

import stirling.software.officeconvert.table.PageContent.PdfWord;

final class CellText {

    private static final float MIN_SPACE_EM = 0.2f;

    private static final float FIT_SLACK_EM = 0.15f;

    private CellText() {}

    static String build(List<PdfWord> words, float wrapWidth) {
        if (words.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        TextLine prev = null;
        for (TextLine line : TextLine.group(words)) {
            if (prev != null) {
                sb.append(separator(prev, line, wrapWidth));
            }
            sb.append(line.text());
            prev = line;
        }
        return sb.toString().strip();
    }

    private static String separator(TextLine prev, TextLine next, float wrapWidth) {
        PdfWord first = next.words().getFirst();
        boolean listItem = next.words().size() > 1 && ListMarkers.isMarker(first.text());
        if (listItem || !wrapsInto(prev.words(), first, wrapWidth)) {
            return "\n";
        }
        String last = prev.words().getLast().text();
        return last.endsWith("-") && last.length() > 1 ? "" : " ";
    }

    static boolean wrapsInto(List<PdfWord> above, PdfWord next, float wrapWidth) {
        if (above.isEmpty() || ListMarkers.isLeader(above.getLast().text())) {
            return false;
        }
        PdfWord last = above.getLast();
        float width = last.right() - above.getFirst().x();
        float space = Math.max(last.spaceWidth(), last.fontSize() * MIN_SPACE_EM);
        float slack = Math.max(1f, next.fontSize() * FIT_SLACK_EM);
        return width + space + next.width() > wrapWidth - slack;
    }
}
