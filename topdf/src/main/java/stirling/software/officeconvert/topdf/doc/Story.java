package stirling.software.officeconvert.topdf.doc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.hwpf.model.PAPX;
import org.apache.poi.hwpf.usermodel.LineSpacingDescriptor;
import org.apache.poi.hwpf.usermodel.ParagraphProperties;

final class Story {

    enum Kind { MAIN, HEADER, FOOTNOTE, ENDNOTE, TEXTBOX }

    static final int MAX_DEPTH = 8;

    static final int HAIRLINE = 120;

    interface Breaks {
        String at(int end);

        boolean ends(int end);
    }

    record Par(ParagraphProperties props, int start, int end, int level, boolean rowEnd, boolean cellEnd) {}

    final Conv c;

    final Rels rels;

    final Kind kind;

    final int base;

    private final Breaks breaks;

    private final Inline inline;

    private boolean pageBreak;

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
                if (pageBreak && depth == 0) {
                    out.append("<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/>")
                            .append("</w:pPr><w:r><w:br w:type=\"page\"/></w:r></w:p>");
                    pageBreak = false;
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
        boolean breakBefore = pageBreak && depth == 0;
        pageBreak = last == '\u000C' && depth == 0 && breaks != null && !breaks.ends(end);
        List<Sprm> sprms = c.src.resolved(istd, par.start());
        String numbering = c.lists.numPr(props.getIlfo(), props.getIlvl());
        List<Source.Segment> markRun = c.src.segments(Math.max(par.start(), end - 1), end, istd);
        String mark = markRun.isEmpty() ? null : c.src.runs.props(markRun.get(0).chp(), markRun.get(0).sprms());
        String sect = depth == 0 && breaks != null ? breaks.at(end) : null;
        int split = hairlineBreak(props, par.start(), contentEnd);
        if (split > par.start()) {
            ParagraphProperties head = props.copy();
            head.setLspd(new LineSpacingDescriptor());
            out.append("<w:p>");
            ParaXml.write(out, head, sprms, c.styles.id(istd), numbering, mark, null, breakBefore);
            inline.write(par.start(), split, split, istd, out);
            out.append("</w:p>");
            breakBefore = false;
            numbering = null;
        }
        out.append("<w:p>");
        ParaXml.write(out, props, sprms, c.styles.id(istd), numbering, mark, sect, breakBefore);
        inline.write(Math.max(par.start(), split), contentEnd, end, istd, out);
        out.append("</w:p>");
    }

    private int hairlineBreak(ParagraphProperties props, int start, int contentEnd) {
        int lspd = props.getLspd() == null ? 0 : props.getLspd().toInt();
        int line = (short) (lspd & 0xFFFF);
        if (lspd >>> 16 != 0 || line >= 0 || -line >= HAIRLINE || contentEnd - start < 2) {
            return -1;
        }
        char ch = c.src.text.charAt(contentEnd - 1);
        return ch == '\u000E' || ch == '\u000C' ? contentEnd - 1 : -1;
    }
}
