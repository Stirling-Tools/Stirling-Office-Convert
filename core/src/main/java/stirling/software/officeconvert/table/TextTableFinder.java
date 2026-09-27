package stirling.software.officeconvert.table;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import stirling.software.officeconvert.table.DraftTable.Borders;
import stirling.software.officeconvert.table.PageContent.FillBox;
import stirling.software.officeconvert.table.PageContent.PdfWord;
import stirling.software.officeconvert.table.PageContent.Ruling;

final class TextTableFinder {

    private TextTableFinder() {}

    static List<DraftTable> find(
            int page, List<PdfWord> words, List<Ruling> rules, List<FillBox> fills) {
        List<ChunkedLine> lines = new ArrayList<>();
        for (TextLine line : TextLine.group(words)) {
            lines.add(ChunkedLine.of(line));
        }
        List<DraftTable> tables = new ArrayList<>();
        for (TextBlocks.Block block : TextBlocks.find(lines)) {
            List<ChunkedLine> blockLines =
                    lines.subList(block.start(), block.end() + block.trailing() + 1);
            DraftTable table = build(page, blockLines, block.trailing(), rules, fills);
            if (table != null) {
                tables.add(table);
            }
        }
        return tables;
    }

    private static DraftTable build(
            int page,
            List<ChunkedLine> lines,
            int trailing,
            List<Ruling> rules,
            List<FillBox> fills) {
        lines = withinOuterRules(lines, trailing, rules);
        if (lines.stream().filter(ChunkedLine::tabular).count() < 2) {
            return null;
        }
        TextColumns columns = TextColumns.find(lines);
        if (columns.count() < 2) {
            return null;
        }
        List<CellLine> cellLines = new ArrayList<>(lines.size());
        List<TextLine> textLines = new ArrayList<>(lines.size());
        for (ChunkedLine line : lines) {
            cellLines.add(columns.assign(line));
            textLines.add(line.line());
        }
        float[] edges = columns.edges();
        Separators separators =
                Separators.across(rules, fills, edges[0], edges[edges.length - 1], textLines);
        ColumnProfile profile = ColumnProfile.of(cellLines, columns.count());

        List<LineRange> rows = RowGrouper.group(cellLines, profile, separators);
        int firstTrailing = lines.size() - trailing;
        while (rows.size() > 1 && rows.getLast().first() >= firstTrailing) {
            rows.removeLast();
        }
        RowGrouper.joinRuledHeader(rows, cellLines, separators);
        if (!TextTableFilter.accepts(cellLines, rows, profile, separators.count() > 0)) {
            return null;
        }
        return draft(page, cellLines, rows, columns, profile, separators);
    }

    private static List<ChunkedLine> withinOuterRules(
            List<ChunkedLine> lines, int trailing, List<Ruling> rules) {
        float left = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        List<TextLine> textLines = new ArrayList<>();
        for (ChunkedLine line : lines) {
            left = Math.min(left, line.line().x());
            right = Math.max(right, line.line().right());
            textLines.add(line.line());
        }
        Separators ruled = Separators.across(rules, List.of(), left, right, textLines);
        if (ruled.count() < 3) {
            return lines;
        }
        while (lines.size() > 3 && outsideRules(ruled, lines.get(0), lines.get(1), true)) {
            lines = lines.subList(1, lines.size());
        }
        while (trailing == 0
                && lines.size() > 3
                && outsideRules(ruled, lines.getLast(), lines.get(lines.size() - 2), false)) {
            lines = lines.subList(0, lines.size() - 1);
        }
        return lines;
    }

    private static boolean outsideRules(
            Separators ruled, ChunkedLine edge, ChunkedLine inner, boolean top) {
        TextLine line = edge.line();
        float side = top ? line.top() : line.bottom();
        boolean parted =
                top ? ruled.between(line, inner.line()) : ruled.between(inner.line(), line);
        return !ruled.near(side, line.fontSize()) && parted;
    }

    private static DraftTable draft(
            int page,
            List<CellLine> lines,
            List<LineRange> rows,
            TextColumns columns,
            ColumnProfile profile,
            Separators separators) {
        int cols = columns.count();
        float[] edges = columns.edges();
        float[] contentLeft = new float[cols];
        float[] contentRight = new float[cols];
        Arrays.fill(contentLeft, Float.MAX_VALUE);
        Arrays.fill(contentRight, -Float.MAX_VALUE);
        for (CellLine line : lines) {
            for (int c = 0; c < cols; c++) {
                for (PdfWord w : line.cell(c)) {
                    contentLeft[c] = Math.min(contentLeft[c], w.x());
                    contentRight[c] = Math.max(contentRight[c], w.right());
                }
            }
        }
        List<DraftTable.Cell> cells = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            LineRange row = rows.get(r);
            TextLine firstLine = lines.get(row.first()).line();
            TextLine lastLine = lines.get(row.last()).line();
            float top = Float.MAX_VALUE;
            float bottom = -Float.MAX_VALUE;
            for (int li = row.first(); li <= row.last(); li++) {
                top = Math.min(top, lines.get(li).line().top());
                bottom = Math.max(bottom, lines.get(li).line().bottom());
            }
            boolean ruledAbove =
                    r == 0
                            ? separators.near(top, firstLine.fontSize())
                            : separators.between(
                                    lines.get(rows.get(r - 1).last()).line(), firstLine);
            boolean ruledBelow =
                    r == rows.size() - 1
                            ? separators.near(bottom, lastLine.fontSize())
                            : separators.between(
                                    lastLine, lines.get(rows.get(r + 1).first()).line());
            Borders borders = new Borders(ruledAbove, ruledBelow, false, false);
            for (int c = 0; c < cols; c++) {
                float left = contentLeft[c] == Float.MAX_VALUE ? edges[c] : contentLeft[c];
                float right = contentRight[c] == -Float.MAX_VALUE ? edges[c + 1] : contentRight[c];
                List<PdfWord> words = new ArrayList<>();
                for (int li = row.first(); li <= row.last(); li++) {
                    words.addAll(lines.get(li).cell(c));
                }
                cells.add(
                        new DraftTable.Cell(
                                r,
                                c,
                                1,
                                1,
                                new Box(left, top, right, bottom),
                                profile.wrapWidths()[c],
                                borders,
                                words));
            }
        }
        return new DraftTable(
                page,
                rows.size(),
                cols,
                edges,
                false,
                cells,
                ruledHeaderRows(rows, lines, separators));
    }

    private static int ruledHeaderRows(
            List<LineRange> rows, List<CellLine> lines, Separators separators) {
        if (separators.count() == 0 || rows.size() < 3) {
            return 0;
        }
        int ruledGaps = 0;
        int firstRuled = -1;
        for (int r = 1; r < rows.size(); r++) {
            if (separators.between(
                    lines.get(rows.get(r - 1).last()).line(),
                    lines.get(rows.get(r).first()).line())) {
                ruledGaps++;
                if (firstRuled < 0) {
                    firstRuled = r;
                }
            }
        }
        return firstRuled > 0 && firstRuled <= 3 && ruledGaps * 2 < rows.size() - 1
                ? firstRuled
                : 0;
    }
}
