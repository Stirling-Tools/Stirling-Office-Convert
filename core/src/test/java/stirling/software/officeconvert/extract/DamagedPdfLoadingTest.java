package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.testing.DamagedPdf;

class DamagedPdfLoadingTest {

    private static final String PAGE =
            "<< /Parent 1 0 R /MediaBox [0 0 612 792] /Resources 3 0 R /Type /Page /Contents [4 0 R] >>";

    @TempDir Path dir;

    private Path write(DamagedPdf pdf) throws IOException {
        return pdf.write(Files.createTempFile(dir, "damaged-", ".pdf"));
    }

    private static String text(PDDocument doc) throws IOException {
        return new PDFTextStripper().getText(doc).strip();
    }

    private void assertReadsOnePage(DamagedPdf damaged, boolean salvaged) throws IOException {
        try (PDDocument doc = PdfFiles.open(write(damaged), null)) {
            assertEquals(1, doc.getNumberOfPages());
            assertEquals("Hello PDF-world!", text(doc));
            assertEquals(salvaged, PdfFiles.repairs(doc).salvaged() != null);
        }
    }

    @Test
    void aDocumentWithoutACatalogIsSalvaged() throws IOException {
        assertReadsOnePage(DamagedPdf.hello().without(5), true);
    }

    @Test
    void aTrailerPointingAtTheWrongObjectIsSalvaged() throws IOException {
        assertReadsOnePage(DamagedPdf.hello().trailer("/Root 4 0 R"), true);
        assertReadsOnePage(DamagedPdf.hello().trailer("/Root 5 R"), true);
    }

    @Test
    void aCatalogWithoutPagesIsSalvaged() throws IOException {
        assertReadsOnePage(DamagedPdf.hello().object(5, "<< /Type /Catalog >>"), true);
        assertReadsOnePage(DamagedPdf.hello().object(5, "<< /Pages 9 0 R /Type /Catalog >>"), true);
        assertReadsOnePage(DamagedPdf.hello().without(1), true);
    }

    @Test
    void theSalvagedCopyIsDeletedWhenTheDocumentCloses() throws IOException {
        Path in = write(DamagedPdf.hello().without(5));
        Path temp = Path.of(System.getProperty("java.io.tmpdir"));
        List<Path> before = salvageFiles(temp);
        try (PDDocument doc = PdfFiles.open(in, null)) {
            assertNotNull(PdfFiles.repairs(doc).salvaged());
        }
        assertEquals(before, salvageFiles(temp));
    }

    private static List<Path> salvageFiles(Path folder) throws IOException {
        try (var files = Files.list(folder)) {
            return files.filter(p -> p.getFileName().toString().startsWith("pdf-salvage-")).sorted().toList();
        }
    }

    @Test
    void aWrongPageCountIsCorrected() throws IOException {
        assertReadsOnePage(DamagedPdf.hello().object(1, "<< /Kids [2 0 R] /Type /Pages /Count 9 >>"), false);
        assertReadsOnePage(DamagedPdf.hello().object(1, "<< /Kids [2 0 R] /Type /Pages >>"), false);
    }

    @Test
    void aLoopingOrKidlessTreeIsRebuiltFromThePagesInTheFile() throws IOException {
        assertReadsOnePage(DamagedPdf.hello().object(1, "<< /Kids [1 0 R] /Type /Pages /Count 1 >>"), false);
        assertReadsOnePage(DamagedPdf.hello().object(1, "<< /Type /Pages /Count 1 >>"), false);
    }

    @Test
    void aLoopBelowARealPageKeepsTheDeclaredCountWithABlankPage() throws IOException {
        DamagedPdf pdf = DamagedPdf.hello()
                .object(1, "<< /Kids [2 0 R 6 0 R] /Type /Pages /Count 2 /MediaBox [0 0 595 842] >>")
                .object(6, "<< /Kids [7 0 R] /Parent 1 0 R /Type /Pages /Count 1 >>")
                .object(7, "<< /Kids [6 0 R] /Parent 6 0 R /Type /Pages /Count 1 >>");
        try (PDDocument doc = PdfFiles.open(write(pdf), null)) {
            assertEquals(2, doc.getNumberOfPages());
            assertEquals("Hello PDF-world!", text(doc));
            assertFalse(doc.getPage(1).hasContents());
            assertEquals(595, doc.getPage(1).getMediaBox().getWidth());
        }
    }

    @Test
    void aPageListedTwiceIsShownTwice() throws IOException {
        try (PDDocument doc = PdfFiles.open(write(DamagedPdf.hello()
                .object(1, "<< /Kids [2 0 R 2 0 R] /Type /Pages /Count 2 >>")), null)) {
            assertEquals(2, doc.getNumberOfPages());
            assertTrue(doc.getPage(0).getCOSObject() != doc.getPage(1).getCOSObject());
            assertEquals("Hello PDF-world!\nHello PDF-world!", text(doc).replace("\r\n", "\n"));
        }
    }

    @Test
    void aPageTreeNodeListedTwiceShowsItsPagesTwice() throws IOException {
        DamagedPdf pdf = DamagedPdf.hello()
                .object(1, "<< /Kids [6 0 R 6 0 R] /Type /Pages /Count 2 >>")
                .object(2, PAGE.replace("/Parent 1 0 R", "/Parent 6 0 R"))
                .object(6, "<< /Kids [2 0 R] /Parent 1 0 R /Type /Pages /Count 1 >>");
        try (PDDocument doc = PdfFiles.open(write(pdf), null)) {
            assertEquals(2, doc.getNumberOfPages());
            assertEquals("Hello PDF-world!\nHello PDF-world!", text(doc).replace("\r\n", "\n"));
        }
    }

    @Test
    void repeatsBeyondTheDeclaredCountAreDropped() throws IOException {
        assertReadsOnePage(DamagedPdf.hello().object(1, "<< /Kids [2 0 R 2 0 R] /Type /Pages /Count 1 >>"), false);
        String chain = "<< /Kids [%1$d 0 R %1$d 0 R] /Type /Pages /Count 2 >>";
        DamagedPdf deep = DamagedPdf.hello().object(1, String.format(chain, 6));
        for (int n = 6; n < 66; n++) {
            deep.object(n, String.format(chain, n + 1));
        }
        deep.object(66, "<< /Kids [] /Type /Pages /Count 0 >>");
        try (PDDocument doc = PdfFiles.open(write(deep), null)) {
            assertEquals(1, doc.getNumberOfPages());
        }
    }

    @Test
    void aPageWithTheWrongTypeIsStillAPage() throws IOException {
        assertReadsOnePage(DamagedPdf.hello().object(2, PAGE.replace("/Type /Page", "/Type /Font")), false);
        assertReadsOnePage(DamagedPdf.hello().object(2, PAGE.replace("/Type /Page ", "")), false);
    }

    @Test
    void aDirectPageUnderACountlessRootSurvivesARebuiltCrossReferenceTable() throws IOException {
        String pdf = "%PDF-1.5\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj 2 0 obj<</Type/Pages/Kids[<</Type/Page"
                + "/Rotate 180/Parent 2 0 R/Resources<</Font<</F<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>>>>>"
                + "/Contents 3 0 R/Count 1/MediaBox [0 0 500 500]>>]>>endobj 3 0 obj<</Length 42>>stream\n"
                + "BT/F 10 Tf 100 100 TD(Hello World!)Tj ET\nendstream\nendobj xref 0 4\n0000000000 65535 f\n"
                + "0000000010 00000 n\n0000000053 00000 n\n0000000197 00000 n\ntrailer<</Size 4/Root 1 0 R>>"
                + "startref 286%%EOF\n";
        Path in = dir.resolve("direct.pdf");
        Files.writeString(in, pdf, StandardCharsets.ISO_8859_1);
        try (PDDocument doc = PdfFiles.open(in, null)) {
            assertEquals(1, doc.getNumberOfPages());
            assertEquals(180, doc.getPage(0).getRotation());
            assertEquals("Hello World!", text(doc));
        }
    }

    @Test
    void aCatalogPointingStraightAtAPageKeepsThatPage() throws IOException {
        try (PDDocument doc = PdfFiles.open(write(DamagedPdf.hello().object(5, "<< /Pages 2 0 R /Type /Catalog >>")),
                null)) {
            assertEquals(1, doc.getNumberOfPages());
            assertEquals("Hello PDF-world!", text(doc));
            assertEquals(612, doc.getPage(0).getMediaBox().getWidth());
        }
    }

    @Test
    void aKidThatDoesNotExistIsFilledByTheOrphanedPage() throws IOException {
        assertReadsOnePage(DamagedPdf.hello().object(1, "<< /Kids [8 0 R] /Type /Pages /Count 1 >>"), false);
    }

    @Test
    void aMissingPageBecomesABlankPageAndOtherKidsKeepTheirPlace() throws IOException {
        try (PDDocument doc = PdfFiles.open(write(DamagedPdf.hello()
                .object(1, "<< /Kids [2 0 R 3 0 R 8 0 R] /Type /Pages /Count 3 >>")), null)) {
            assertEquals(3, doc.getNumberOfPages());
            assertEquals("Hello PDF-world!", text(doc));
            assertNull(doc.getPage(1).getCOSObject().getDictionaryObject(org.apache.pdfbox.cos.COSName.FONT));
            assertEquals(612, doc.getPage(2).getMediaBox().getWidth());
        }
        try (PDDocument doc = PdfFiles.open(write(DamagedPdf.hello().without(2)), null)) {
            assertEquals(1, doc.getNumberOfPages());
            assertFalse(doc.getPage(0).hasContents());
        }
    }

    @Test
    void aMediaBoxThatIsNotARectangleBecomesLetter() throws IOException {
        try (PDDocument doc = PdfFiles.open(write(DamagedPdf.hello()
                .object(2, PAGE.replace("[0 0 612 792]", "[0 612 792]"))), null)) {
            assertEquals(612, doc.getPage(0).getMediaBox().getWidth());
            assertEquals(792, doc.getPage(0).getMediaBox().getHeight());
            assertFalse(PdfFiles.repairs(doc).pageTree());
            assertEquals("Hello PDF-world!", text(doc));
        }
    }

    @Test
    void aSoundTreeIsLeftAlone() throws IOException {
        Path in = write(DamagedPdf.hello().object(1, "<< /Kids [2 0 R] /Type /Pages /Count 1 /Rotate 90 >>"));
        try (PDDocument doc = PdfFiles.open(in, null)) {
            assertFalse(PdfFiles.repairs(doc).pageTree());
            assertEquals(90, doc.getPage(0).getRotation());
            assertNull(doc.getPage(0).getCOSObject().getItem(org.apache.pdfbox.cos.COSName.ROTATE));
        }
    }

    @Test
    void anIntentionallyEmptyTreeStaysEmpty() throws IOException {
        try (PDDocument doc = PdfFiles.open(write(DamagedPdf.hello()
                .object(1, "<< /Kids [] /Type /Pages /Count 0 >>")), null)) {
            assertEquals(0, doc.getNumberOfPages());
        }
    }

    @Test
    void inheritedAttributesFollowAPageOutOfABrokenTree() throws IOException {
        DamagedPdf pdf = DamagedPdf.hello()
                .object(1, "<< /Kids [6 0 R] /Type /Pages /Count 4 /Rotate 90 /MediaBox [0 0 300 400] >>")
                .object(2, "<< /Parent 6 0 R /Resources 3 0 R /Type /Page /Contents [4 0 R] >>")
                .object(6, "<< /Kids [2 0 R] /Parent 1 0 R /Type /Pages /Count 1 >>");
        try (PDDocument doc = PdfFiles.open(write(pdf), null)) {
            assertTrue(PdfFiles.repairs(doc).pageTree());
            assertEquals(1, doc.getNumberOfPages());
            assertEquals(90, doc.getPage(0).getRotation());
            assertEquals(300, doc.getPage(0).getMediaBox().getWidth());
        }
    }

    @Test
    void theOptimizerOpenLeavesThePageTreeAsStored() throws IOException {
        Path in = write(DamagedPdf.hello().object(1, "<< /Kids [2 0 R] /Type /Pages /Count 9 >>"));
        try (PDDocument doc = PdfFiles.openAsStored(in, null)) {
            assertEquals(9, doc.getNumberOfPages());
        }
        assertEquals(1, PdfFiles.pageCount(in, null));
    }

    @Test
    void theStrictOpenDoesNotSalvage() throws IOException {
        Path in = write(DamagedPdf.hello().without(5));
        IOException e = assertThrows(IOException.class, () -> PdfFiles.openUnrepaired(in, null));
        assertTrue(e.getMessage().startsWith("The PDF could not be opened"), e.getMessage());
    }

    @Test
    void aFileWithNoObjectsStillFails() throws IOException {
        Path in = dir.resolve("empty.pdf");
        Files.writeString(in, "%PDF-1.4\n%%EOF\n");
        IOException e = assertThrows(IOException.class, () -> PdfFiles.open(in, null));
        assertTrue(e.getMessage().startsWith("The PDF could not be opened"), e.getMessage());
    }
}
