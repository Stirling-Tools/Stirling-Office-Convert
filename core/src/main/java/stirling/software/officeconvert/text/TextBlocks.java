package stirling.software.officeconvert.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Table;
import stirling.software.officeconvert.sink.ListLabels;

final class TextBlocks {

    private static final float GAP = 3f;

    private static final Pattern PAGE_ONLY = Pattern.compile("(?i)[\\s\\p{Punct}]*(page|pg\\.?|p\\.)?[\\s\\p{Punct}]*(of)?[\\s\\p{Punct}]*");

    private final Map<Integer, String> markers = new HashMap<>();
    private boolean first = true;
    private boolean gap;
    private boolean lastList;

    void paragraph(StringBuilder sb, Paragraph p, List<Integer> refs) {
        List<Inline> before = new ArrayList<>();
        List<Inline> after = new ArrayList<>();
        for (Inline in : p.inlines) {
            if (isFloat(in)) {
                (below(in, p) ? after : before).add(in);
            }
        }
        floats(sb, before, refs);
        String text = text(p, refs);
        boolean heading = !"Normal".equals(p.style) && !"ListParagraph".equals(p.style);
        if (text.isBlank()) {
            if (p.lineRule != Paragraph.LineRule.AUTO && p.lineHeight >= 2 * GAP || p.pageBreakBefore) {
                gap = true;
            }
        } else {
            boolean list = p.list != null;
            boolean blank = p.pageBreakBefore || heading || p.spaceBefore >= GAP && !(list && lastList);
            if (list) {
                String indent = "  ".repeat(Math.max(0, Math.min(8, p.list.level())));
                text = indent + '\0' + "L" + p.list.numId() + ":" + p.list.level() + '\0' + " " + text;
            }
            line(sb, text, blank);
            gap = heading;
            lastList = list;
        }
        floats(sb, after, refs);
    }

    private static boolean below(Inline in, Paragraph p) {
        if (Float.isNaN(p.sourceBottom)) {
            return false;
        }
        if (in instanceof Inline.TextBox t) {
            return t.y() >= p.sourceBottom;
        }
        return in instanceof Inline.Image im && !im.picture().fromParagraph && im.picture().y >= p.sourceBottom;
    }

    private static boolean isFloat(Inline in) {
        return in instanceof Inline.TextBox || in instanceof Inline.Image || in instanceof Inline.Shape;
    }

    private void floats(StringBuilder sb, List<Inline> inlines, List<Integer> refs) {
        for (Inline in : inlines) {
            if (in instanceof Inline.Image im && !im.picture().description.isBlank()) {
                for (String l : im.picture().description.lines().toList()) {
                    line(sb, l, false);
                }
                gap = true;
            }
            if (in instanceof Inline.TextBox box) {
                for (Paragraph bp : box.paragraphs()) {
                    String t = text(bp, refs);
                    if (!t.isBlank()) {
                        line(sb, t, !lastList || bp.spaceBefore >= GAP);
                        lastList = false;
                    }
                }
                gap = true;
            }
        }
    }

    void table(StringBuilder sb, Table t, List<Integer> refs) {
        gap = true;
        for (Table.Row row : t.rows) {
            StringBuilder r = new StringBuilder();
            int col = 0;
            for (Table.Cell cell : row.cells) {
                if (col > 0) {
                    r.append('\t');
                }
                if (cell.vMerge != 2) {
                    List<String> parts = new ArrayList<>();
                    for (Paragraph p : cell.paragraphs) {
                        String s = text(p, refs).replace('\n', ' ').replace('\t', ' ').strip();
                        if (!s.isEmpty()) {
                            parts.add(s);
                        }
                    }
                    r.append(String.join(" ", parts));
                }
                r.append("\t".repeat(Math.max(0, cell.gridSpan - 1)));
                col += Math.max(1, cell.gridSpan);
            }
            String s = r.toString().stripTrailing();
            if (!s.isEmpty()) {
                line(sb, s, false);
            }
        }
        gap = true;
        lastList = false;
    }

    void note(StringBuilder sb, String marker, List<Paragraph> paras) {
        List<String> lines = new ArrayList<>();
        for (Paragraph p : paras) {
            String s = text(p, new ArrayList<>());
            if (!s.isBlank()) {
                lines.add(s);
            }
        }
        if (!lines.isEmpty()) {
            line(sb, "[" + marker + "] " + String.join("\n", lines), false);
        }
    }

    void separate() {
        gap = true;
    }

    void running(StringBuilder sb, Paragraph p) {
        boolean numbered = p.inlines.stream().anyMatch(in -> in instanceof Inline.PageNumber);
        String s = text(p, new ArrayList<>());
        if (s.isBlank() || numbered && PAGE_ONLY.matcher(s).matches()) {
            return;
        }
        sb.append(s).append('\n');
    }

    String marker(int id) {
        return markers.getOrDefault(id, Integer.toString(id));
    }

    private void line(StringBuilder sb, String text, boolean blank) {
        if (!first && (gap || blank)) {
            sb.append('\n');
        }
        sb.append(text).append('\n');
        first = false;
        gap = false;
    }

    private String text(Paragraph p, List<Integer> refs) {
        boolean join = p.align == Paragraph.Align.JUSTIFY;
        StringBuilder sb = new StringBuilder();
        for (Inline in : p.inlines) {
            switch (in) {
                case Inline.Text t -> clean(sb, t.text());
                case Inline.Tab tab -> sb.append('\t');
                case Inline.Break br -> sb.append(!join ? "\n" : !sb.isEmpty() && sb.charAt(sb.length() - 1) == '-' ? "" : " ");
                case Inline.FootnoteRef ref -> {
                    markers.put(ref.id(), ref.marker());
                    refs.add(ref.id());
                    sb.append('[').append(ref.marker()).append(']');
                }
                default -> { }
            }
        }
        return sb.toString().stripTrailing();
    }

    private static void clean(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\u00AD' && c != '\0' && c != '\r' && c != '\uFFFE' && c != '\uFFFF') {
                sb.append(c);
            }
        }
    }

    private static final String BULLET = String.valueOf((char) 0x2022);

    static String label(ListLabels labels, String key) {
        if (!key.startsWith("L")) {
            return "";
        }
        int colon = key.indexOf(':');
        try {
            String label = labels.next(Integer.parseInt(key.substring(1, colon)), Integer.parseInt(key.substring(colon + 1)));
            return !label.isEmpty() && label.chars().allMatch(c -> c >= 0xE000 && c <= 0xF8FF) ? BULLET : label;
        } catch (RuntimeException e) {
            return "";
        }
    }
}
