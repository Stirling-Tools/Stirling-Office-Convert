package stirling.software.officeconvert.topdf.doc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.hwpf.model.PAPX;
import org.apache.poi.hwpf.usermodel.ParagraphProperties;

final class Story {

    enum Kind { MAIN, HEADER, FOOTNOTE, ENDNOTE, TEXTBOX }

    static final int MAX_DEPTH = 8;

    interface Breaks {
        String at(int end);
    }

    record Par(ParagraphProperties props, int start, int end, int level, boolean rowEnd, boolean cellEnd) {}

    final Conv c;

    final Rels rels;

    final Kind kind;

    final int base;

    private final Breaks breaks;

    private final Inline inline;

    Story(Conv c, Rels rels, Kind kind, int base, Breaks breaks) {
        this.c = c;
        this.rels = rels;
        this.kind = kind;
        this.base = base;
        this.breaks = breaks;
        this.inline = new Inline(this);
    }

    void write(int start, int end, StringBuilder out) throws IOException {
        if (end <= start) {
            return;
        }
        List<Par> ps = paragraphs(start, end);
        blocks(ps, 0, ps.size(), 0, out);
    }

    List<Par> paragraphs(int start, int end) throws IOException {
        List<Par> out = new ArrayList<>();
        CharSequence text = c.src.text;
        for (PAPX x : c.src.paragraphs(start, end)) {
            c.checkpoint();
            int s = Math.max(start, x.getStart());
            int e = Math.min(end, x.getEnd());
            if (e <= s) {
                continue;
            }
            ParagraphProperties props = c.src.pap(x);
            int level = props.getFInTable() || props.getItap() > 0 ? Math.max(1, props.getItap()) : 0;
            char last = e - 1 < text.length() ? text.charAt(e - 1) : '\r';
            boolean rowEnd = level > 0 && (props.getFTtp() || props.getFTtpEmbedded());
            boolean cellEnd = level > 0 && (last == '\u0007' || level > 1 && props.getFInnerTableCell());
            out.add(new Par(props, s, e, Math.min(level, MAX_DEPTH), rowEnd, cellEnd));
        }
        return out;
    }

    List<Sprm> direct(Par p) {
        return c.src.direct(p.start());
    }

    void blocks(List<Par> ps, int from, int to, int depth, StringBuilder out) throws IOException {
        int i = from;
        while (i < to) {
            c.checkpoint();
            if (c.full(out)) {
                return;
            }
            Par p = ps.get(i);
            if (p.level() > depth) {
                int j = i;
                while (j < to && ps.get(j).level() > depth) {
                    j++;
                }
                TableXml.write(this, ps, i, j, depth + 1, out);
                if (depth == 0 && breaks != null) {
                    String sect = breaks.at(ps.get(j - 1).end());
                    if (sect != null) {
                        out.append("<w:p><w:pPr>").append(sect).append("</w:pPr></w:p>");
                    }
                }
                i = j;
                continue;
            }
            if (!p.rowEnd() || p.level() == 0) {
                paragraph(p, depth, out);
            }
            i++;
        }
    }

    private void paragraph(Par par, int depth, StringBuilder out) throws IOException {
        ParagraphProperties props = par.props();
        int istd = props.getIstd();
        CharSequence text = c.src.text;
        int end = par.end();
        char last = end - 1 < text.length() ? text.charAt(end - 1) : 0;
        int contentEnd = last == '\r' || last == '\u0007' || last == '\u000C' ? end - 1 : end;
        List<Sprm> sprms = c.src.resolved(istd, par.start());
        String numbering = c.lists.numPr(props.getIlfo(), props.getIlvl());
        List<Source.Segment> markRun = c.src.segments(Math.max(par.start(), end - 1), end, istd);
        String mark = markRun.isEmpty() ? null : c.src.runs.props(markRun.get(0).chp(), markRun.get(0).sprms());
        String sect = depth == 0 && breaks != null ? breaks.at(end) : null;
        out.append("<w:p>");
        ParaXml.write(out, props, sprms, c.styles.id(istd), numbering, mark, sect);
        inline.write(par.start(), contentEnd, end, istd, out);
        out.append("</w:p>");
    }
}
