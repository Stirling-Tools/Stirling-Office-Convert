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

class DeadDestinationsTest {

    @TempDir
    Path dir;

    @Test
    void linksAndOpenActionsToPagesOutsideTheDocumentLoseOnlyTheirTarget() throws Exception {
        Path in = dir.resolve("in.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDPage second = new PDPage();
            doc.addPage(second);
            COSDictionary orphan = new COSDictionary();
            orphan.setItem(COSName.TYPE, COSName.PAGE);
            COSArray annots = new COSArray();
            annots.add(link(destination(orphan)));
            annots.add(link(destination(second.getCOSObject())));
            page.getCOSObject().setItem(COSName.ANNOTS, annots);
            doc.getDocumentCatalog().getCOSObject().setItem(COSName.OPEN_ACTION, destination(orphan));
            doc.save(in.toFile());
        }
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, PdfOptimizer.Options.defaults().alwaysRewrite(true));

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            COSArray annots = doc.getPage(0).getCOSObject().getCOSArray(COSName.ANNOTS);
            assertEquals(2, annots.size());
            assertFalse(((COSDictionary) annots.getObject(0)).containsKey(COSName.DEST));
            assertTrue(((COSDictionary) annots.getObject(1)).containsKey(COSName.DEST));
            assertFalse(doc.getDocumentCatalog().getCOSObject().containsKey(COSName.OPEN_ACTION));
            assertEquals(2, doc.getDocument().getXrefTable().keySet().stream()
                    .map(key -> doc.getDocument().getObjectFromPool(key).getObject())
                    .filter(o -> o instanceof COSDictionary d && COSName.PAGE.equals(d.getCOSName(COSName.TYPE)))
                    .count());
        }
    }

    @Test
    void linksStayWhenThePageTreeCannotBeFollowed() throws Exception {
        Path in = dir.resolve("direct-pages.pdf");
        java.nio.file.Files.writeString(in, """
                %PDF-1.7
                1 0 obj <</Type/Catalog/Pages <</Type/Pages/Kids[2 0 R 3 0 R]/Count 2>> >> endobj
                2 0 obj <</Type/Page/MediaBox[0 0 200 200]/Annots[4 0 R]>> endobj
                3 0 obj <</Type/Page/MediaBox[0 0 200 200]>> endobj
                4 0 obj <</Type/Annot/Subtype/Link/Rect[0 0 50 50]/Dest[3 0 R/Fit]>> endobj
                trailer <</Root 1 0 R>>
                %%EOF
                """, java.nio.charset.StandardCharsets.ISO_8859_1);
        Path out = dir.resolve("direct-pages-out.pdf");
        PdfOptimizer.optimize(in, out, PdfOptimizer.Options.defaults().alwaysRewrite(true));

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            COSArray annots = doc.getPage(0).getCOSObject().getCOSArray(COSName.ANNOTS);
            assertTrue(((COSDictionary) annots.getObject(0)).containsKey(COSName.DEST));
        }
    }

    private static COSArray destination(COSDictionary page) {
        COSArray destination = new COSArray();
        destination.add(page);
        destination.add(COSName.getPDFName("Fit"));
        return destination;
    }

    private static COSDictionary link(COSArray destination) {
        COSDictionary link = new COSDictionary();
        link.setItem(COSName.TYPE, COSName.ANNOT);
        link.setItem(COSName.SUBTYPE, COSName.LINK);
        COSArray rect = new COSArray();
        rect.setFloatArray(new float[] {0, 0, 50, 50});
        link.setItem(COSName.RECT, rect);
        link.setItem(COSName.DEST, destination);
        return link;
    }
}
