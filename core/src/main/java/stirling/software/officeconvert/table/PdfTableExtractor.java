package stirling.software.officeconvert.table;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.table.PageContent.FillBox;
import stirling.software.officeconvert.table.PageContent.PdfWord;
import stirling.software.officeconvert.table.PageContent.Ruling;

public class PdfTableExtractor {

    private static final System.Logger log = System.getLogger(PdfTableExtractor.class.getName());

    private static final int MAX_GUIDES = 4000;

    private static final float PAGE_EDGE_SHARE = 0.45f;

    public List<ExtractedTable> extract(PDDocument document, List<Integer> pageNumbers) {
        return stitch(extractPlaced(document, pageNumbers));
    }

    List<ExtractedTable> extractUnstitched(PDDocument document, List<Integer> pageNumbers) {
        return extractPlaced(document, pageNumbers).stream().map(Placed::table).toList();
    }

    private record Placed(ExtractedTable table, Box box, float pageHeight) {}

    private List<Placed> extractPlaced(PDDocument document, List<Integer> pageNumbers) {
        List<Placed> placed = new ArrayList<>();
        for (int page : pageNumbers) {
            try {
                placed.addAll(extractPage(document, page));
            } catch (IOException | RuntimeException e) {
                log.log(
                        System.Logger.Level.WARNING,
                        "Table extraction failed on page " + page + ": " + e.getMessage());
            }
        }
        return placed;
    }

    private List<Placed> extractPage(PDDocument document, int pageNumber) throws IOException {
        PageContent content = PageContentReader.read(document, pageNumber);
        List<Ruling> horizontals = Rulings.merge(content.horizontals());
        List<Ruling> verticals = Rulings.merge(content.verticals());
        List<LatticeFinder.Found> lattices = new ArrayList<>();
        List<LatticeFinder.Found> frames = new ArrayList<>();
        for (LatticeFinder.Found f :
                LatticeFinder.find(pageNumber, horizontals, verticals, content.words())) {
            (f.table() != null ? lattices : frames).add(f);
        }
        WordRegions regions = partition(content.words(), lattices, frames);

        List<Ruling> rules = horizontals.size() > MAX_GUIDES ? List.of() : horizontals;
        List<FillBox> fills = content.fills().size() > MAX_GUIDES ? List.of() : content.fills();
        List<DraftTable> drafts = new ArrayList<>();
        for (LatticeFinder.Found f : lattices) {
            drafts.add(f.table());
        }
        for (List<PdfWord> words : regions.framed()) {
            drafts.addAll(TextTableFinder.find(pageNumber, words, rules, fills));
        }
        drafts.addAll(TextTableFinder.find(pageNumber, regions.free(), rules, fills));
        drafts.sort(Comparator.comparingDouble(DraftTable::top));

        List<Placed> tables = new ArrayList<>(drafts.size());
        for (DraftTable draft : drafts) {
            Box box = new Box(draft.left(), draft.top(), draft.right(), draft.bottom());
            tables.add(new Placed(TableAssembler.assemble(draft, fills), box, content.height()));
        }
        return tables;
    }

    private record WordRegions(List<List<PdfWord>> framed, List<PdfWord> free) {}

    private static WordRegions partition(
            List<PdfWord> words,
            List<LatticeFinder.Found> lattices,
            List<LatticeFinder.Found> frames) {
        List<List<PdfWord>> framed = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) {
            framed.add(new ArrayList<>());
        }
        List<PdfWord> free = new ArrayList<>();
        for (PdfWord w : words) {
            if (smallestContaining(lattices, w) >= 0) {
                continue;
            }
            int frame = smallestContaining(frames, w);
            (frame >= 0 ? framed.get(frame) : free).add(w);
        }
        return new WordRegions(framed, free);
    }

    private static int smallestContaining(List<LatticeFinder.Found> regions, PdfWord w) {
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

    private static List<ExtractedTable> stitch(List<Placed> placed) {
        List<ExtractedTable> out = new ArrayList<>();
        int i = 0;
        while (i < placed.size()) {
            ExtractedTable first = placed.get(i).table();
            List<ExtractedCell> cells = new ArrayList<>(first.cells());
            int rows = first.rowCount();
            int lastPage = first.lastPage();
            int j = i + 1;
            while (j < placed.size() && continues(placed.get(j - 1), placed.get(j))) {
                ExtractedTable next = placed.get(j).table();
                int drop = repeatedHeaderRows(first, next);
                if (drop < 0) {
                    break;
                }
                for (ExtractedCell cell : next.cells()) {
                    if (cell.row() >= drop) {
                        cells.add(
                                new ExtractedCell(
                                        cell.row() + rows - drop,
                                        cell.col(),
                                        cell.rowSpan(),
                                        cell.colSpan(),
                                        cell.text(),
                                        cell.style()));
                    }
                }
                rows += next.rowCount() - drop;
                lastPage = next.lastPage();
                j++;
            }
            out.add(
                    j == i + 1
                            ? first
                            : new ExtractedTable(
                                    first.firstPage(),
                                    lastPage,
                                    rows,
                                    first.columnCount(),
                                    first.columnWidths(),
                                    cells,
                                    first.headerRows(),
                                    first.ruled()));
            i = j;
        }
        return out;
    }

    private static boolean continues(Placed prev, Placed cur) {
        ExtractedTable a = prev.table();
        ExtractedTable b = cur.table();
        if (a.firstPage() + 1 != b.firstPage()
                || a.columnCount() != b.columnCount()
                || a.ruled() != b.ruled()) {
            return false;
        }
        if (prev.box().bottom() < prev.pageHeight() * (1 - PAGE_EDGE_SHARE)
                || cur.box().top() > cur.pageHeight() * PAGE_EDGE_SHARE
                || Math.abs(prev.box().left() - cur.box().left()) > 12f) {
            return false;
        }
        for (int c = 0; c < a.columnCount(); c++) {
            float wa = a.columnWidths().get(c);
            float wb = b.columnWidths().get(c);
            if (Math.abs(wa - wb) > Math.max(4f, wa * 0.08f)) {
                return false;
            }
        }
        return true;
    }

    private static int repeatedHeaderRows(ExtractedTable table, ExtractedTable next) {
        int candidate = next.headerRows() > 0 ? next.headerRows() : 1;
        boolean repeated =
                candidate <= next.rowCount()
                        && candidate <= table.rowCount()
                        && Arrays.deepEquals(
                                leadingRows(table, candidate), leadingRows(next, candidate));
        if (!repeated) {
            return next.headerRows() > 0 ? -1 : 0;
        }
        for (ExtractedCell cell : next.cells()) {
            if (cell.row() < candidate && cell.row() + cell.rowSpan() > candidate) {
                return -1;
            }
        }
        return candidate;
    }

    private static String[][] leadingRows(ExtractedTable table, int rows) {
        String[][] grid = new String[rows][table.columnCount()];
        for (String[] row : grid) {
            Arrays.fill(row, "");
        }
        for (ExtractedCell cell : table.cells()) {
            if (cell.row() < rows) {
                grid[cell.row()][cell.col()] = cell.text().replaceAll("\\s+", " ").strip();
            }
        }
        return grid;
    }
}
