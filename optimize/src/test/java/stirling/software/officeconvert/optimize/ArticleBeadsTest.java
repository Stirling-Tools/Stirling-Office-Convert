package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArticleBeadsTest {

    private static final COSName BEADS = COSName.getPDFName("B");

    @TempDir
    Path dir;

    @Test
    void beadsOfUnlistedThreadsAreDroppedWithThePagesOnlyTheyKeptAlive() throws Exception {
        Path out = optimize(false);

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertFalse(doc.getPage(0).getCOSObject().containsKey(BEADS));
            assertEquals(1, pageObjects(doc));
        }
    }

    @Test
    void beadsOfThreadsTheDocumentListsAreKept() throws Exception {
        Path out = optimize(true);

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertTrue(doc.getPage(0).getCOSObject().containsKey(BEADS));
            assertEquals(1, doc.getDocumentCatalog().getCOSObject().getCOSArray(COSName.THREADS).size());
        }
    }

    private Path optimize(boolean listThread) throws Exception {
        Path in = dir.resolve("in.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            COSDictionary orphan = new COSDictionary();
            orphan.setItem(COSName.TYPE, COSName.PAGE);
            orphan.setString(COSName.T, "orphan page");
            COSDictionary thread = new COSDictionary();
            thread.setItem(COSName.TYPE, COSName.getPDFName("Thread"));
            COSDictionary first = bead(thread, page.getCOSObject());
            COSDictionary second = bead(thread, orphan);
            first.setItem(COSName.N, second);
            first.setItem(COSName.V, second);
            second.setItem(COSName.N, first);
            second.setItem(COSName.V, first);
            thread.setItem(COSName.F, first);
            COSArray onPage = new COSArray();
            onPage.add(first);
            page.getCOSObject().setItem(BEADS, onPage);
            COSArray onOrphan = new COSArray();
            onOrphan.add(second);
            orphan.setItem(BEADS, onOrphan);
            if (listThread) {
                COSArray threads = new COSArray();
                threads.add(thread);
                doc.getDocumentCatalog().getCOSObject().setItem(COSName.THREADS, threads);
            }
            doc.save(in.toFile());
        }
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, PdfOptimizer.Options.defaults().alwaysRewrite(true));
        return out;
    }

    private static long pageObjects(PDDocument doc) {
        return doc.getDocument().getXrefTable().keySet().stream()
                .map(key -> doc.getDocument().getObjectFromPool(key).getObject())
                .filter(o -> o instanceof COSDictionary d && COSName.PAGE.equals(d.getCOSName(COSName.TYPE)))
                .count();
    }

    private static COSDictionary bead(COSDictionary thread, COSDictionary page) {
        COSDictionary bead = new COSDictionary();
        bead.setItem(COSName.TYPE, COSName.getPDFName("Bead"));
        bead.setItem(COSName.T, thread);
        bead.setItem(COSName.P, page);
        COSArray rect = new COSArray();
        rect.setFloatArray(new float[] {0, 0, 100, 100});
        bead.setItem(COSName.R, rect);
        return bead;
    }
}
