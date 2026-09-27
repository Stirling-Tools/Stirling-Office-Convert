package stirling.software.officeconvert.build;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import stirling.software.officeconvert.layout.DocStats;
import stirling.software.officeconvert.layout.HeaderFooter;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.PageFrame;
import stirling.software.officeconvert.layout.RunningLine;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph.Align;
import stirling.software.officeconvert.model.Paragraph.LineRule;
import stirling.software.officeconvert.model.Paragraph;

final class RunningContent {

    private final DocStats stats;
    private final RunBuilder runs;

    RunningContent(DocStats stats, RunBuilder runs) {
        this.stats = stats;
        this.runs = runs;
    }

    DocSink.HeaderFooterSet runningContent(float pageWidth, float pageHeight) {
        HeaderFooter hf = stats.headerFooterInfo();
        if (!hf.any()) {
            return new DocSink.HeaderFooterSet(List.of(), List.of(), List.of(), List.of(), false);
        }
        PageFrame frame = stats.frame(pageWidth, pageHeight);
        float colLeft = frame.textLeft();
        float colRight = frame.textRight();
        List<RunningLine> headers = hf.headers();
        List<RunningLine> footers = hf.footers();
        boolean split = paritySplit(headers) || paritySplit(footers);
        return new DocSink.HeaderFooterSet(
                running(split ? onParity(headers, 0) : headers, colLeft, colRight),
                running(split ? onParity(footers, 0) : footers, colLeft, colRight),
                split ? running(onParity(headers, 1), colLeft, colRight) : List.of(),
                split ? running(onParity(footers, 1), colLeft, colRight) : List.of(),
                hf.firstPageDiffers());
    }

    private static boolean paritySplit(List<RunningLine> lines) {
        boolean odd = false;
        boolean even = false;
        for (RunningLine r : lines) {
            int p = parityOf(r);
            odd |= p == 0;
            even |= p == 1;
        }
        return odd && even;
    }

    private static int parityOf(RunningLine r) {
        boolean odd = false;
        boolean even = false;
        for (int page : r.pages()) {
            if (page % 2 == 0) {
                odd = true;
            } else {
                even = true;
            }
        }
        return odd && even ? -1 : odd ? 0 : even ? 1 : -1;
    }

    private static List<RunningLine> onParity(List<RunningLine> lines, int parity) {
        List<RunningLine> out = new ArrayList<>();
        for (RunningLine r : lines) {
            int p = parityOf(r);
            if (p == parity || p == -1) {
                out.add(r);
            }
        }
        return out;
    }

    private List<Paragraph> running(List<RunningLine> lines, float colLeft, float colRight) {
        List<RunningLine> sorted = new ArrayList<>(lines);
        sorted.sort((a, b) -> Float.compare(a.baseline, b.baseline));
        List<Paragraph> out = new ArrayList<>();
        int i = 0;
        while (i < sorted.size()) {
            int j = i + 1;
            while (j < sorted.size() && Math.abs(sorted.get(j).baseline - sorted.get(i).baseline) < 2f) {
                j++;
            }
            List<RunningLine> row = new ArrayList<>(sorted.subList(i, j));
            row.sort((a, b) -> Float.compare(a.sample().x, b.sample().x));
            out.add(runningParagraph(row, colLeft, colRight));
            i = j;
        }
        return out;
    }

    private Paragraph runningParagraph(List<RunningLine> row, float colLeft, float colRight) {
        Paragraph p = new Paragraph();
        p.lineRule = LineRule.EXACT;
        p.lineHeight = SectionPlanner.runningLineHeight(row.getFirst().sample().size);
        float centre = (colLeft + colRight) / 2f;
        float width = colRight - colLeft;
        boolean firstPiece = true;
        for (RunningLine r : row) {
            Line line = r.sample();
            float lc = line.centre();
            boolean isCentre = Math.abs(lc - centre) < width * 0.06f;
            boolean isRight = (Math.abs(colRight - line.right) < 6f || line.right > colRight) && !isCentre;
            if (firstPiece) {
                if (row.size() == 1) {
                    p.align = isCentre ? Align.CENTER : isRight ? Align.RIGHT : Align.LEFT;
                    if (p.align == Align.LEFT) {
                        p.indentLeft = Math.clamp(line.x - colLeft, 0, Math.max(0, width - line.width()));
                    }
                } else if (line.x - colLeft > 4f) {
                    p.inlines.add(new Inline.Tab(null));
                    p.tabs.add(tabFor(isCentre, isRight, line, colLeft, width));
                }
            } else {
                p.inlines.add(new Inline.Tab(null));
                p.tabs.add(tabFor(isCentre, isRight, line, colLeft, width));
            }
            appendRunning(p, r, line);
            firstPiece = false;
        }
        p.markStyle = ParagraphFactory.lastStyle(p);
        return p;
    }

    private static Paragraph.TabStop tabFor(boolean centre, boolean right, Line line, float colLeft, float width) {
        if (centre) {
            return new Paragraph.TabStop(Math.clamp(line.centre() - colLeft, 0, width), Paragraph.TabStop.Kind.CENTER, (char) 0);
        }
        if (right) {
            return new Paragraph.TabStop(Math.clamp(line.right - colLeft, 0, width), Paragraph.TabStop.Kind.RIGHT, (char) 0);
        }
        return new Paragraph.TabStop(Math.clamp(line.x - colLeft, 0, width), Paragraph.TabStop.Kind.LEFT, (char) 0);
    }

    private void appendRunning(Paragraph p, RunningLine r, Line line) {
        Paragraph tmp = new Paragraph();
        runs.fill(tmp, List.of(line), 0, 0, 10_000, line.size);
        int run = 0;
        for (Inline in : tmp.inlines) {
            if (!(in instanceof Inline.Text t)) {
                p.inlines.add(in);
                continue;
            }
            String s = t.text();
            Matcher m = Pattern.compile("[0-9]+").matcher(s);
            int last = 0;
            while (m.find()) {
                boolean page = run < r.pageOffsets.length && r.pageOffsets[run] != Integer.MIN_VALUE;
                boolean total = run < r.totalPages.length && r.totalPages[run] && !page;
                if (page || total) {
                    if (m.start() > last) {
                        p.inlines.add(new Inline.Text(s.substring(last, m.start()), t.style(), t.link(), t.anchorPage()));
                    }
                    p.inlines.add(new Inline.PageNumber(t.style(), total));
                    last = m.end();
                }
                run++;
            }
            if (last < s.length()) {
                p.inlines.add(new Inline.Text(s.substring(last), t.style(), t.link(), t.anchorPage()));
            }
        }
    }

    public int pageNumberStart() {
        HeaderFooter hf = stats.headerFooterInfo();
        for (RunningLine r : hf.footers()) {
            for (int off : r.pageOffsets) {
                if (off != Integer.MIN_VALUE) {
                    return off;
                }
            }
        }
        for (RunningLine r : hf.headers()) {
            for (int off : r.pageOffsets) {
                if (off != Integer.MIN_VALUE) {
                    return off;
                }
            }
        }
        return 1;
    }
}
