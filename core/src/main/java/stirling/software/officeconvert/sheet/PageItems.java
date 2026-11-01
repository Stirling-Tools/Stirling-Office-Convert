package stirling.software.officeconvert.sheet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import stirling.software.officeconvert.layout.Box;
import stirling.software.officeconvert.layout.DocStats;
import stirling.software.officeconvert.layout.HeaderFooter;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.LineBuilder;
import stirling.software.officeconvert.layout.OcrText;
import stirling.software.officeconvert.layout.PageLayout;
import stirling.software.officeconvert.layout.ParaDraft;
import stirling.software.officeconvert.layout.RunningLine;
import stirling.software.officeconvert.layout.Word;
import stirling.software.officeconvert.sheet.PageBlocks.Block;

final class PageItems {

    record Para(PageBlocks.Text block, CellText text, boolean figure) {}

    record Prepared(List<Object> items, List<RunningLine> taken) {}

    private PageItems() {}

    static Prepared of(PageLayout layout, DocStats stats, Conventions document, Conventions[] prior, boolean typed,
            boolean dropHyphens) {
        HeaderFooter running = stats.headerFooterInfo();
        List<Line> segments = LineBuilder.build(OcrText.pageGlyphs(layout.page()));
        List<Line> runningLines = new ArrayList<>();
        if (running.any()) {
            for (Line seg : segments) {
                if (running.match(layout.page(), seg) != null) {
                    runningLines.add(seg);
                }
            }
        }
        Set<Line> strict = identitySet(runningLines);
        Set<Line> flow = identitySet(List.of());
        List<Line> offered = offered(layout, runningLines, strict, flow);
        Set<Line> taken = identitySet(List.of());
        Map<PageLayout.TableItem, PageLayout.TableItem> restored = new IdentityHashMap<>();
        for (Block b : PageBlocks.of(layout)) {
            if (b instanceof PageBlocks.Grid g) {
                offered.removeIf(taken::contains);
                TableRestore.Result r = TableRestore.restore(g.item(), offered, strict, flow);
                restored.put(g.item(), r.item());
                taken.addAll(r.taken());
            }
        }
        List<RunningLine> takenRunning = new ArrayList<>();
        for (Line l : runningLines) {
            if (taken.contains(l)) {
                takenRunning.add(running.match(layout.page(), l));
            }
        }
        List<Object> items = new ArrayList<>();
        Set<Word> claimed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Block b : PageBlocks.of(layout, taken)) {
            switch (b) {
                case PageBlocks.Grid g -> {
                    for (Object part : TableSplit.parts(restored.getOrDefault(g.item(), g.item()), dropHyphens)) {
                        if (part instanceof ParaDraft heading) {
                            items.add(para(heading, dropHyphens, false));
                            continue;
                        }
                        TableGrid grid = TableGrid.of((PageLayout.TableItem) part, layout.page().graphics().rules(),
                                document, prior, typed, dropHyphens);
                        if (grid != null) {
                            items.add(grid);
                        } else {
                            PageLayout.TableItem table = (PageLayout.TableItem) part;
                            Box box = new Box(table.x(), table.top(), table.right(), table.bottom());
                            for (ParaDraft p : captions(FigureText.lines(box, segments, strict, claimed))) {
                                items.add(para(p, dropHyphens, false));
                            }
                        }
                    }
                }
                case PageBlocks.Text t -> {
                    CellText ct = CellText.of(List.of(t.para()), dropHyphens);
                    if (!ct.text().isEmpty() || !t.prefix().isEmpty()) {
                        items.add(new Para(t, ct, false));
                    }
                }
                case PageBlocks.Figure f -> {
                    for (ParaDraft p : captions(FigureText.lines(f.box(), segments, strict, claimed))) {
                        items.add(para(p, dropHyphens, true));
                    }
                }
            }
        }
        return new Prepared(items, takenRunning);
    }

    private static List<Line> offered(PageLayout layout, List<Line> runningLines, Set<Line> strict, Set<Line> flow) {
        List<Line> offered = new ArrayList<>(runningLines);
        for (PageLayout.TextBoxItem box : layout.furniture()) {
            if (box.turn() == null) {
                box.paras().forEach(p -> offered.addAll(p.lines));
            }
        }
        Set<Line> seen = identitySet(offered);
        for (Block b : PageBlocks.of(layout)) {
            if (b instanceof PageBlocks.Text t && t.prefix().isEmpty() && tabular(t.para())
                    && t.para().lines.stream().noneMatch(seen::contains)) {
                offered.addAll(t.para().lines);
                strict.addAll(t.para().lines);
                flow.addAll(t.para().lines);
            }
        }
        return offered;
    }

    static List<ParaDraft> captions(List<Line> lines) {
        List<ParaDraft> out = new ArrayList<>();
        Line previous = null;
        for (Line l : lines) {
            if (previous != null && brokenWord(previous, l)) {
                ParaDraft p = out.getLast();
                p.lines.add(l);
            } else {
                ParaDraft p = new ParaDraft(l.x, l.right);
                p.lines.add(l);
                out.add(p);
            }
            previous = l;
        }
        return out;
    }

    private static boolean brokenWord(Line above, Line below) {
        String last = above.words.getLast().text;
        return !last.isEmpty() && (last.endsWith("-") || last.endsWith("\u00AD")) && last.length() > 1
                && Character.isLetter(last.charAt(last.length() - 2)) && !below.words.isEmpty()
                && !below.words.getFirst().text.isEmpty()
                && Character.isLowerCase(below.words.getFirst().text.codePointAt(0))
                && below.top - above.bottom < Math.max(above.size, below.size)
                && firstSegmentRight(below) > lastSegmentLeft(above) && below.x < above.right;
    }

    private static float lastSegmentLeft(Line line) {
        int start = 0;
        for (int i = 1; i < line.words.size(); i++) {
            if (line.gaps[i] != Line.SPACE) {
                start = i;
            }
        }
        return line.words.get(start).x;
    }

    private static float firstSegmentRight(Line line) {
        int end = line.words.size();
        for (int i = 1; i < line.words.size(); i++) {
            if (line.gaps[i] != Line.SPACE) {
                end = i;
                break;
            }
        }
        return line.words.get(end - 1).right;
    }

    private static Para para(ParaDraft p, boolean dropHyphens, boolean figure) {
        return new Para(new PageBlocks.Text(p, ""), CellText.of(List.of(p), dropHyphens), figure);
    }

    private static boolean tabular(ParaDraft p) {
        for (Line l : p.lines) {
            boolean tab = false;
            for (int i = 1; i < l.gaps.length; i++) {
                tab |= l.gaps[i] != Line.SPACE;
            }
            if (!tab) {
                return false;
            }
        }
        return !p.lines.isEmpty();
    }

    private static Set<Line> identitySet(List<Line> lines) {
        Set<Line> s = Collections.newSetFromMap(new IdentityHashMap<>());
        s.addAll(lines);
        return s;
    }
}
