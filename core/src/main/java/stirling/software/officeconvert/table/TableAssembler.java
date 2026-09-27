package stirling.software.officeconvert.table;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import stirling.software.officeconvert.table.CellStyle.HorizontalAlignment;
import stirling.software.officeconvert.table.CellStyle.VerticalAlignment;
import stirling.software.officeconvert.table.PageContent.FillBox;
import stirling.software.officeconvert.table.PageContent.PdfWord;

final class TableAssembler {

    private static final int MAX_HEADER_ROWS = 3;

    private static final float FILL_COVERAGE = 0.5f;

    private static final int NO_FILL = -1;

    private TableAssembler() {}

    static ExtractedTable assemble(DraftTable draft, List<FillBox> pageFills) {
        List<FillBox> fills = tableFills(draft, pageFills);
        List<DraftTable.Cell> sorted = new ArrayList<>(draft.cells());
        sorted.sort(
                Comparator.comparingInt(DraftTable.Cell::row)
                        .thenComparingInt(DraftTable.Cell::col));
        List<List<TextLine>> linesPerCell = new ArrayList<>(sorted.size());
        for (DraftTable.Cell cell : sorted) {
            linesPerCell.add(TextLine.group(cell.words()));
        }
        float padding = draft.ruled() ? padding(sorted, linesPerCell) : 0f;

        List<ExtractedCell> cells = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            DraftTable.Cell cell = sorted.get(i);
            List<TextLine> lines = linesPerCell.get(i);
            float wrapWidth = draft.ruled() ? cell.box().width() - 2 * padding : cell.wrapWidth();
            cells.add(
                    new ExtractedCell(
                            cell.row(),
                            cell.col(),
                            cell.rowSpan(),
                            cell.colSpan(),
                            CellText.build(cell.words(), wrapWidth),
                            style(cell, lines, fills, draft.ruled(), padding)));
        }

        List<Float> widths = new ArrayList<>(draft.cols());
        for (int c = 0; c < draft.cols(); c++) {
            widths.add(draft.colEdges()[c + 1] - draft.colEdges()[c]);
        }
        int header =
                draft.headerHint() > 0 ? draft.headerHint() : detectHeader(cells, draft.rows());
        return new ExtractedTable(
                draft.page(),
                draft.page(),
                draft.rows(),
                draft.cols(),
                widths,
                cells,
                Math.min(header, Math.max(0, draft.rows() - 1)),
                draft.ruled());
    }

    private static List<FillBox> tableFills(DraftTable draft, List<FillBox> pageFills) {
        Box table = new Box(draft.left(), draft.top(), draft.right(), draft.bottom());
        List<FillBox> result = new ArrayList<>();
        for (FillBox f : pageFills) {
            boolean overlaps =
                    f.x() < table.right()
                            && f.right() > table.left()
                            && f.top() < table.bottom()
                            && f.bottom() > table.top();
            if (overlaps && f.area() <= table.area() * 1.5f) {
                result.add(f);
            }
        }
        return result;
    }

    private static float padding(List<DraftTable.Cell> cells, List<List<TextLine>> linesPerCell) {
        List<Float> gaps = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            Box box = cells.get(i).box();
            for (TextLine l : linesPerCell.get(i)) {
                gaps.add(Math.min(l.x() - box.left(), box.right() - l.right()));
            }
        }
        if (gaps.isEmpty()) {
            return 2f;
        }
        gaps.sort(Float::compare);
        return Math.clamp(gaps.get(gaps.size() / 10), 0.5f, 8f);
    }

    private static CellStyle style(
            DraftTable.Cell cell,
            List<TextLine> lines,
            List<FillBox> fills,
            boolean boxed,
            float padding) {
        Map<Float, Integer> sizes = new HashMap<>();
        Map<Integer, Integer> colours = new HashMap<>();
        int chars = 0;
        int boldChars = 0;
        int italicChars = 0;
        for (PdfWord w : cell.words()) {
            int n = Math.max(1, w.text().length());
            chars += n;
            if (w.bold()) {
                boldChars += n;
            }
            if (w.italic()) {
                italicChars += n;
            }
            sizes.merge(Math.round(w.fontSize() * 2f) / 2f, n, Integer::sum);
            colours.merge(w.rgb(), n, Integer::sum);
        }
        int textRgb = mode(colours, 0);
        return new CellStyle(
                chars > 0 && boldChars * 2 > chars,
                chars > 0 && italicChars * 2 > chars,
                mode(sizes, 0f),
                Colours.isNearBlack(textRgb) ? null : textRgb,
                fill(cell.box(), fills),
                horizontal(cell.box(), lines, padding),
                boxed ? vertical(cell.box(), lines) : VerticalAlignment.TOP,
                cell.borders().top(),
                cell.borders().bottom(),
                cell.borders().left(),
                cell.borders().right());
    }

    private static <K> K mode(Map<K, Integer> counts, K fallback) {
        K best = fallback;
        int bestCount = -1;
        for (Map.Entry<K, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    private static Integer fill(Box cell, List<FillBox> fills) {
        float cellArea = Math.max(1f, cell.area());
        Integer rgb = null;
        for (FillBox f : fills) {
            float cx = cell.centreX();
            float cy = cell.centreY();
            if (cx < f.x() || cx > f.right() || cy < f.top() || cy > f.bottom()) {
                continue;
            }
            float ix = Math.min(cell.right(), f.right()) - Math.max(cell.left(), f.x());
            float iy = Math.min(cell.bottom(), f.bottom()) - Math.max(cell.top(), f.top());
            if (ix * iy >= cellArea * FILL_COVERAGE) {
                rgb = Colours.isNearWhite(f.rgb()) ? null : f.rgb();
            }
        }
        return rgb;
    }

    private static HorizontalAlignment horizontal(Box cell, List<TextLine> lines, float padding) {
        if (lines.isEmpty()) {
            return HorizontalAlignment.GENERAL;
        }
        float width = cell.width();
        float slack = Math.max(1.5f, width * 0.04f);
        boolean right = true;
        boolean centre = true;
        boolean left = true;
        for (TextLine l : lines) {
            float lg = l.x() - cell.left();
            float rg = cell.right() - l.right();
            right &= rg <= padding + slack && lg > padding + slack * 2;
            centre &= Math.abs(lg - rg) <= Math.max(slack, width * 0.06f) && lg > padding + slack;
            left &= lg <= padding + slack && rg > padding + slack * 2;
        }
        if (centre) {
            return HorizontalAlignment.CENTER;
        }
        if (right) {
            return HorizontalAlignment.RIGHT;
        }
        return left ? HorizontalAlignment.LEFT : HorizontalAlignment.GENERAL;
    }

    private static VerticalAlignment vertical(Box cell, List<TextLine> lines) {
        if (lines.isEmpty()) {
            return VerticalAlignment.TOP;
        }
        float topGap = lines.getFirst().top() - cell.top();
        float bottomGap = cell.bottom() - lines.getLast().bottom();
        float slack = Math.max(2f, cell.height() * 0.12f);
        if (Math.abs(topGap - bottomGap) <= slack) {
            return VerticalAlignment.CENTER;
        }
        return bottomGap < topGap ? VerticalAlignment.BOTTOM : VerticalAlignment.TOP;
    }

    private static int detectHeader(List<ExtractedCell> cells, int rows) {
        if (rows < 2) {
            return 0;
        }
        List<List<ExtractedCell>> byRow = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            byRow.add(new ArrayList<>());
        }
        for (ExtractedCell c : cells) {
            byRow.get(c.row()).add(c);
        }
        int limit = Math.min(MAX_HEADER_ROWS, rows - 1);
        int header = 0;
        while (header < limit) {
            List<ExtractedCell> body = new ArrayList<>();
            for (int r = header + 1; r < rows; r++) {
                body.addAll(byRow.get(r));
            }
            if (!rowDiffers(byRow.get(header), body)) {
                break;
            }
            header++;
        }
        return header;
    }

    private static boolean rowDiffers(List<ExtractedCell> row, List<ExtractedCell> body) {
        int filled = 0;
        int bold = 0;
        Integer rowFill = row.isEmpty() ? null : row.getFirst().style().fillRgb();
        boolean uniformFill = true;
        for (ExtractedCell c : row) {
            if (!c.text().isBlank()) {
                filled++;
                if (c.style().bold()) {
                    bold++;
                }
            }
            uniformFill &= Objects.equals(rowFill, c.style().fillRgb());
        }
        if (filled == 0) {
            return false;
        }
        int bodyFilled = 0;
        int bodyBold = 0;
        Map<Integer, Integer> bodyFills = new HashMap<>();
        for (ExtractedCell c : body) {
            if (!c.text().isBlank()) {
                bodyFilled++;
                if (c.style().bold()) {
                    bodyBold++;
                }
            }
            bodyFills.merge(
                    Objects.requireNonNullElse(c.style().fillRgb(), NO_FILL), 1, Integer::sum);
        }
        boolean boldHeader = bold == filled && (bodyFilled == 0 || bodyBold * 10 < bodyFilled * 6);
        int bodyFill = mode(bodyFills, NO_FILL);
        boolean fillHeader =
                uniformFill && rowFill != null && (bodyFill == NO_FILL || rowFill != bodyFill);
        return boldHeader || fillHeader;
    }
}
