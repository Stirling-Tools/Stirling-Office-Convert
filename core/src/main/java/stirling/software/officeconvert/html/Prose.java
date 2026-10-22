package stirling.software.officeconvert.html;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Table;

final class Prose {

    private static final Pattern SENTENCE = Pattern.compile("[.!?]\\s+\\p{Lu}");
    private static final Pattern ENDED = Pattern.compile("[.!?]$");

    private Prose() {}

    static Paragraph of(Table t) {
        if (t.rows.size() < 2 || t.columnWidths.size() < 2) {
            return null;
        }
        Paragraph first = null;
        List<String> rows = new ArrayList<>();
        int words = 0;
        for (Table.Row row : t.rows) {
            StringBuilder text = new StringBuilder();
            for (Table.Cell c : row.cells) {
                if (c.shading >= 0 || c.top.visible() || c.bottom.visible() || c.left.visible() || c.right.visible()
                        || c.paragraphs.size() > 1 || c.vMerge != 0) {
                    return null;
                }
                for (Paragraph p : c.paragraphs) {
                    if (p.list != null) {
                        return null;
                    }
                    for (Inline in : p.inlines) {
                        if (in instanceof Inline.Text tx) {
                            text.append(tx.text());
                        } else if (in instanceof Inline.Tab) {
                            text.append(' ');
                        } else {
                            return null;
                        }
                    }
                    if (first == null && !p.inlines.isEmpty()) {
                        first = p;
                    }
                }
                text.append(' ');
            }
            String r = text.toString().strip();
            if (!r.isEmpty()) {
                rows.add(r);
                words += r.split("\\s+").length;
            }
        }
        if (first == null || rows.size() < 2 || words < 3 * rows.size()) {
            return null;
        }
        int continued = 0;
        for (int i = 1; i < rows.size(); i++) {
            if (Character.isLowerCase(rows.get(i).codePointAt(0))) {
                continued++;
            }
        }
        if (continued * 2 < rows.size() - 1 || !SENTENCE.matcher(String.join(" ", rows)).find()) {
            return null;
        }
        for (int i = 0; i + 1 < rows.size(); i++) {
            if (ENDED.matcher(rows.get(i)).find() && Character.isLowerCase(rows.get(i + 1).codePointAt(0))) {
                return null;
            }
        }
        Paragraph out = new Paragraph();
        out.style = first.style;
        out.align = Paragraph.Align.JUSTIFY;
        out.lineRule = first.lineRule;
        out.lineHeight = first.lineHeight;
        out.markStyle = first.markStyle;
        out.indentLeft = t.indent;
        out.pageBreakBefore = t.pageBreakBefore;
        out.bidi = t.rightToLeft;
        for (Table.Row row : t.rows) {
            for (Table.Cell c : row.cells) {
                for (Paragraph p : c.paragraphs) {
                    if (out.bookmark == null) {
                        out.bookmark = p.bookmark;
                    }
                    Inline.Text last = null;
                    for (Inline in : p.inlines) {
                        if (in instanceof Inline.Text tx && !tx.text().isBlank()) {
                            out.inlines.add(tx);
                            last = tx;
                        } else if (in instanceof Inline.Text tx && last != null) {
                            out.inlines.add(tx);
                        } else if (in instanceof Inline.Tab && last != null) {
                            out.inlines.add(new Inline.Text(" ", last.style(), null, -1));
                        }
                    }
                    if (last != null && !last.text().endsWith(" ")) {
                        out.inlines.add(new Inline.Text(" ", last.style(), null, -1));
                    }
                }
            }
        }
        return out;
    }
}
