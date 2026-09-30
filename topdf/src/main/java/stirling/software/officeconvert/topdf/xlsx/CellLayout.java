package stirling.software.officeconvert.topdf.xlsx;

import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.topdf.font.Clusters;

final class CellLayout {

    static final double PAD = 1.92;

    static final int EDGE_PX = 4;

    // Excel draws #### and heading labels 4 printer pixels right of the middle
    static final double CENTRED_SHIFT = EDGE_PX * PrintMetrics.PX;

    // Wrapped lines may run 2 printer pixels into the right inset
    static final double WRAP_SLACK = 2 * PrintMetrics.PX;

    record Line(List<TextRun> runs, double width, boolean last) {}

    private CellLayout() {}

    // Excel insets cell text by 4 printer pixels plus a quarter of the printer digit width of its first run's font
    static double pad(Typesetter t, CellText text, CellFormat format) {
        FontSpec font = text == null || text.runs().isEmpty() ? format.font() : text.runs().get(0).font();
        return (EDGE_PX + Math.ceil(t.measure(font).printerDigit(font.size()) / 4.0)) * PrintMetrics.PX;
    }

    // General text starts on its own side: right when it reads right to left, which a mirrored sheet turns round
    static CellFormat.HAlign horizontal(CellFormat format, CellText text, boolean sheetRtl) {
        CellFormat.HAlign h = format.hAlign();
        if (h != CellFormat.HAlign.GENERAL) {
            return h;
        }
        if (text == null) {
            return CellFormat.HAlign.LEFT;
        }
        return switch (text.kind()) {
            case NUMBER -> CellFormat.HAlign.RIGHT;
            case BOOLEAN, ERROR -> CellFormat.HAlign.CENTER;
            default -> rightToLeft(format, text, sheetRtl) != sheetRtl ? CellFormat.HAlign.RIGHT
                    : CellFormat.HAlign.LEFT;
        };
    }

    // The cell's reading order: set, else its first strong character, else the sheet's
    static boolean rightToLeft(CellFormat format, CellText text, boolean sheetRtl) {
        if (format.readingOrder() == 1 || format.readingOrder() == 2) {
            return format.readingOrder() == 2;
        }
        String s = text == null ? "" : text.plain();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            byte d = Character.getDirectionality(cp);
            if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
                return false;
            }
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return sheetRtl;
    }

    interface Measure {
        double width(String text, FontSpec font);
    }

    static List<Line> wrap(Typesetter t, List<TextRun> runs, double width, double scale) {
        return wrap(runs, width, (s, f) -> t.width(s, f, f.drawSize() * scale));
    }

    static List<Line> wrap(List<TextRun> runs, double width, Measure t) {
        List<Line> lines = new ArrayList<>();
        List<TextRun> current = new ArrayList<>();
        double lineWidth = 0;
        StringBuilder word = new StringBuilder();
        List<TextRun> wordRuns = new ArrayList<>();
        List<Object[]> chars = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (TextRun r : runs) {
            String s = r.text();
            text.append(s);
            for (int i = 0; i < s.length(); i++) {
                chars.add(new Object[] {s.charAt(i), r.font()});
            }
        }
        String all = text.toString();
        boolean[] words = Clusters.dictionaryBreaks(all);
        int i = 0;
        int n = chars.size();
        while (i < n) {
            char c = (char) chars.get(i)[0];
            if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && (char) chars.get(i + 1)[0] == '\n') {
                    i++;
                }
                lines.add(new Line(trim(current), measure(t, trim(current)), true));
                current = new ArrayList<>();
                lineWidth = 0;
                i++;
                continue;
            }
            int start = i;
            while (i < n && (char) chars.get(i)[0] != ' ' && (char) chars.get(i)[0] != '\n'
                    && (char) chars.get(i)[0] != '\r' && (i == start || !breaksBefore(all, words, start, i))) {
                i++;
            }
            while (i < n && (char) chars.get(i)[0] == ' ') {
                i++;
            }
            wordRuns.clear();
            word.setLength(0);
            FontSpec wf = null;
            for (int k = start; k < i; k++) {
                FontSpec f = (FontSpec) chars.get(k)[1];
                if (wf != null && !f.equals(wf)) {
                    wordRuns.add(new TextRun(word.toString(), wf));
                    word.setLength(0);
                }
                wf = f;
                word.append((char) chars.get(k)[0]);
            }
            if (wf != null) {
                wordRuns.add(new TextRun(word.toString(), wf));
            }
            List<TextRun> trimmedWord = trim(wordRuns);
            double visible = measure(t, trimmedWord);
            double full = measure(t, wordRuns);
            if (!current.isEmpty() && lineWidth + visible > width + 0.01) {
                List<TextRun> done = trim(current);
                lines.add(new Line(done, measure(t, done), false));
                current = new ArrayList<>();
                lineWidth = 0;
            }
            if (current.isEmpty() && visible > width + 0.01 && width > 0) {
                List<List<TextRun>> pieces = breakWord(t, wordRuns, width);
                for (int p = 0; p < pieces.size() - 1; p++) {
                    lines.add(new Line(pieces.get(p), measure(t, pieces.get(p)), false));
                }
                current = new ArrayList<>(pieces.get(pieces.size() - 1));
                lineWidth = measure(t, current);
                continue;
            }
            append(current, wordRuns);
            lineWidth += full;
        }
        List<TextRun> done = trim(current);
        lines.add(new Line(done, measure(t, done), true));
        return lines;
    }

    // Excel also breaks after a hyphen inside a word, even before a digit
    // Also where a dictionary ends a Thai word or a zero width space stands, never inside a character cluster
    private static boolean breaksBefore(String all, boolean[] words, int start, int i) {
        if (Clusters.joined(all, i)) {
            return false;
        }
        char prev = all.charAt(i - 1);
        return prev == '-' && i - 1 > start || prev == '\u200B' || words[i]
                || MissingGlyphs.breakBetween(Character.codePointBefore(all, i), all.codePointAt(i));
    }

    private static List<List<TextRun>> breakWord(Measure t, List<TextRun> runs, double width) {
        List<List<TextRun>> out = new ArrayList<>();
        List<TextRun> line = new ArrayList<>();
        double w = 0;
        for (TextRun r : runs) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < r.text().length(); ) {
                int end = i + Character.charCount(r.text().codePointAt(i));
                while (end < r.text().length() && Clusters.joined(r.text(), end)) {
                    end += Character.charCount(r.text().codePointAt(end));
                }
                String ch = r.text().substring(i, end);
                double cw = t.width(ch, r.font());
                if (w + cw > width && (w > 0 || b.length() > 0)) {
                    if (b.length() > 0) {
                        line.add(new TextRun(b.toString(), r.font()));
                        b.setLength(0);
                    }
                    out.add(line);
                    line = new ArrayList<>();
                    w = 0;
                }
                b.append(ch);
                w += cw;
                i = end;
            }
            if (b.length() > 0) {
                line.add(new TextRun(b.toString(), r.font()));
            }
        }
        out.add(line);
        return out;
    }

    private static void append(List<TextRun> line, List<TextRun> add) {
        for (TextRun r : add) {
            if (!line.isEmpty() && line.get(line.size() - 1).font().equals(r.font())) {
                TextRun last = line.remove(line.size() - 1);
                line.add(new TextRun(last.text() + r.text(), r.font()));
            } else {
                line.add(r);
            }
        }
    }

    static List<TextRun> trim(List<TextRun> runs) {
        List<TextRun> out = new ArrayList<>(runs);
        while (!out.isEmpty()) {
            TextRun last = out.get(out.size() - 1);
            String s = last.text();
            int end = s.length();
            while (end > 0 && s.charAt(end - 1) == ' ') {
                end--;
            }
            if (end == s.length()) {
                break;
            }
            out.remove(out.size() - 1);
            if (end > 0) {
                out.add(new TextRun(s.substring(0, end), last.font()));
                break;
            }
        }
        return out;
    }

    static double measure(Measure t, List<TextRun> runs) {
        double w = 0;
        for (TextRun r : runs) {
            w += t.width(r.text(), r.font());
        }
        return w;
    }
}
