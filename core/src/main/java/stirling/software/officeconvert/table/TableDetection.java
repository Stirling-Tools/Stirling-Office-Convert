package stirling.software.officeconvert.table;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.layout.Polls;
import stirling.software.officeconvert.table.PageContent.FillBox;
import stirling.software.officeconvert.table.PageContent.PdfWord;
import stirling.software.officeconvert.table.PageContent.Ruling;

public final class TableDetection {

    private static final int MAX_GUIDES = 4000;

    public enum HAlign {
        GENERAL,
        LEFT,
        CENTER,
        RIGHT
    }

    public enum VAlign {
        TOP,
        CENTER,
        BOTTOM
    }

    public record FoundCell(
            int row,
            int col,
            int rowSpan,
            int colSpan,
            float left,
            float top,
            float right,
            float bottom,
            boolean borderTop,
            boolean borderBottom,
            boolean borderLeft,
            boolean borderRight,
            Integer fill,
            HAlign hAlign,
            VAlign vAlign,
            String text) {}

    public record Found(
            int rows,
            int cols,
            float[] colEdges,
            boolean ruled,
            int headerRows,
            float padding,
            List<FoundCell> cells,
            float left,
            float top,
            float right,
            float bottom) {}

    private TableDetection() {}

    public static List<Found> detect(PageContent content) {
        List<Ruling> horizontals = Rulings.merge(content.horizontals());
        List<Ruling> verticals = Rulings.merge(content.verticals());
        List<LatticeFinder.Found> lattices = new ArrayList<>();
        List<LatticeFinder.Found> frames = new ArrayList<>();
        for (LatticeFinder.Found f :
                LatticeFinder.find(content.pageNumber(), horizontals, verticals, content.words())) {
            (f.table() != null ? lattices : frames).add(f);
        }
        List<List<PdfWord>> framed = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) {
            framed.add(new ArrayList<>());
        }
        List<PdfWord> free = new ArrayList<>();
        int wi = 0;
        for (PdfWord w : content.words()) {
            Polls.check(wi++);
            if (smallest(lattices, w) >= 0) {
                continue;
            }
            int frame = smallest(frames, w);
            (frame >= 0 ? framed.get(frame) : free).add(w);
        }
        List<Ruling> rules = horizontals.size() > MAX_GUIDES ? List.of() : horizontals;
        List<FillBox> fills = content.fills().size() > MAX_GUIDES ? List.of() : content.fills();
        List<DraftTable> drafts = new ArrayList<>();
        for (LatticeFinder.Found f : lattices) {
            drafts.add(f.table());
        }
        for (List<PdfWord> words : framed) {
            drafts.addAll(TextTableFinder.find(content.pageNumber(), words, rules, fills));
        }
        drafts.addAll(TextTableFinder.find(content.pageNumber(), free, rules, fills));
        drafts.sort(Comparator.comparingDouble(DraftTable::top));

        List<Found> out = new ArrayList<>();
        for (DraftTable draft : drafts) {
            Polls.check();
            ExtractedTable assembled = TableAssembler.assemble(draft, fills);
            Map<Long, ExtractedCell> byPlace = new HashMap<>();
            for (ExtractedCell ec : assembled.cells()) {
                byPlace.putIfAbsent(place(ec.row(), ec.col()), ec);
            }
            List<FoundCell> cells = new ArrayList<>();
            List<DraftTable.Cell> sorted = new ArrayList<>(draft.cells());
            sorted.sort(
                    Comparator.comparingInt(DraftTable.Cell::row)
                            .thenComparingInt(DraftTable.Cell::col));
            for (DraftTable.Cell c : sorted) {
                ExtractedCell ec = byPlace.get(place(c.row(), c.col()));
                CellStyle style = ec == null ? CellStyle.PLAIN : ec.style();
                String text = ec == null ? "" : ec.text();
                cells.add(
                        new FoundCell(
                                c.row(),
                                c.col(),
                                c.rowSpan(),
                                c.colSpan(),
                                c.box().left(),
                                c.box().top(),
                                c.box().right(),
                                c.box().bottom(),
                                c.borders().top(),
                                c.borders().bottom(),
                                c.borders().left(),
                                c.borders().right(),
                                style.fillRgb(),
                                HAlign.valueOf(style.horizontal().name()),
                                VAlign.valueOf(style.vertical().name()),
                                text));
            }
            out.add(
                    new Found(
                            draft.rows(),
                            draft.cols(),
                            draft.colEdges().clone(),
                            draft.ruled(),
                            assembled.headerRows(),
                            0f,
                            cells,
                            draft.left(),
                            draft.top(),
                            draft.right(),
                            draft.bottom()));
        }
        return out;
    }

    private static long place(int row, int col) {
        return (long) row << 32 | col & 0xFFFFFFFFL;
    }

    private static int smallest(List<LatticeFinder.Found> regions, PdfWord w) {
        int best = -1;
        float bestArea = Float.MAX_VALUE;
        for (int i = 0; i < regions.size(); i++) {
            Box region = regions.get(i).region();
            if (region.contains(w) && region.area() < bestArea) {
                best = i;
                bestArea = region.area();
            }
        }
        return best;
    }
}
