package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ErrorContractTest {

    @TempDir Path dir;

    interface Convert {
        void run(Path pdf, Path out, int first, int last) throws IOException;
    }

    private static final Convert[] PATHS = {
        (p, o, f, l) -> PdfToDocx.convert(p, o.resolveSibling(o.getFileName() + ".docx"), new PdfToDocx.Options(f, l, true, 150f, null)),
        (p, o, f, l) -> PdfToPptx.convert(p, o.resolveSibling(o.getFileName() + ".pptx"), new PdfToPptx.Options(f, l, true, 150f, null)),
        (p, o, f, l) -> PdfToXlsx.convert(p, o.resolveSibling(o.getFileName() + ".xlsx"), PdfToXlsx.Options.defaults().withPages(f, l)),
    };

    private Path pdf(int pages) throws IOException {
        Path file = dir.resolve("in" + pages + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Page " + i);
                    cs.endText();
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    @Test
    void aFirstPagePastTheEndIsRefusedAndALastOneMeansTheEnd() throws IOException {
        Path pdf = pdf(3);
        for (Convert c : PATHS) {
            IOException e = assertThrows(IOException.class, () -> c.run(pdf, dir.resolve("past"), 5, 6));
            assertEquals("Page 5 is past the end of this 3-page PDF", e.getMessage());
            c.run(pdf, dir.resolve("tail"), 2, 99);
        }
    }

    @Test
    void impossibleOptionsAreArgumentErrors() {
        assertThrows(IllegalArgumentException.class, () -> new PdfToDocx.Options(3, 2, true, 150f, null));
        assertThrows(IllegalArgumentException.class, () -> new PdfToPptx.Options(3, 2, true, 150f, null));
        assertThrows(IllegalArgumentException.class, () -> PdfToXlsx.Options.defaults().withPages(3, 2));
        assertThrows(IllegalArgumentException.class, () -> OfficeConvert.Settings.defaults().pages(3, 2));
        assertThrows(IllegalArgumentException.class, () -> new PdfToDocx.Options(0, 0, true, 5000f, null));
    }

    @Test
    void nullsAreCallerErrorsNotIoFailures() throws IOException {
        Path pdf = pdf(1);
        assertThrows(NullPointerException.class, () -> PdfToDocx.convert(null, dir.resolve("a.docx"), PdfToDocx.Options.defaults()));
        assertThrows(NullPointerException.class, () -> PdfToXlsx.convert(pdf, dir.resolve("a.xlsx"), null));
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertThrows(NullPointerException.class, () -> PdfToDocx.convert(doc, new ByteArrayOutputStream(), null));
            assertThrows(NullPointerException.class, () -> PdfToPptx.convert(doc, null, PdfToPptx.Options.defaults()));
        }
        assertThrows(NullPointerException.class, () -> OfficeConvert.convert(pdf, null));
    }

    @Test
    void anInterruptedCallerGetsInterruptedIoAndKeepsTheFlag() throws IOException {
        Path pdf = pdf(2);
        for (Convert c : PATHS) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, () -> c.run(pdf, dir.resolve("stop"), 0, 0));
                assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag was cleared");
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void aClosedDocumentIsRefused() throws IOException {
        PDDocument doc = Loader.loadPDF(pdf(1).toFile());
        doc.close();
        IOException e = assertThrows(IOException.class,
                () -> PdfToDocx.convert(doc, new ByteArrayOutputStream(), PdfToDocx.Options.defaults()));
        assertEquals("The PDF document is closed", e.getMessage());
    }

    @Test
    void aMissingOutputFolderIsNamed() throws IOException {
        Path pdf = pdf(1);
        Path folder = dir.resolve("no-such-folder");
        NoSuchFileException e = assertThrows(NoSuchFileException.class,
                () -> PdfToDocx.convert(pdf, folder.resolve("out.docx"), PdfToDocx.Options.defaults()));
        assertEquals(folder.toAbsolutePath().toString(), e.getFile());
    }

    @Test
    void filesThatAreNoPdfSaySo() throws IOException {
        Path empty = Files.write(dir.resolve("empty.pdf"), new byte[0]);
        Path html = Files.writeString(dir.resolve("page.pdf"), "<html>not a pdf</html>");
        IOException a = assertThrows(IOException.class, () -> PdfToDocx.convert(empty, dir.resolve("e.docx"), PdfToDocx.Options.defaults()));
        IOException b = assertThrows(IOException.class, () -> PdfToDocx.convert(html, dir.resolve("h.docx"), PdfToDocx.Options.defaults()));
        assertEquals("The PDF could not be opened: the file is empty", a.getMessage());
        assertEquals("The PDF could not be opened: the file is not a PDF", b.getMessage());
    }
}
