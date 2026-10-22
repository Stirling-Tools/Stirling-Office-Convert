package stirling.software.officeconvert.topdf.testing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;

public final class Outlines {

    public record Mark(int depth, int page, int top, String title) {

        @Override
        public String toString() {
            return depth + " p" + page + " " + title;
        }
    }

    private Outlines() {}

    public static List<Mark> read(Path pdf) throws IOException {
        List<Mark> out = new ArrayList<>();
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            PDDocumentOutline root = d.getDocumentCatalog().getDocumentOutline();
            if (root != null) {
                walk(root.children(), 1, out);
            }
        }
        return out;
    }

    public static List<String> titles(Path pdf) throws IOException {
        return read(pdf).stream().map(Mark::toString).toList();
    }

    private static void walk(Iterable<PDOutlineItem> items, int depth, List<Mark> out) throws IOException {
        for (PDOutlineItem item : items) {
            PDPageXYZDestination dest = (PDPageXYZDestination) item.getDestination();
            out.add(new Mark(depth, dest.retrievePageNumber() + 1, dest.getTop(), item.getTitle()));
            walk(item.children(), depth + 1, out);
        }
    }
}
