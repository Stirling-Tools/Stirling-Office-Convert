package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import stirling.software.officeconvert.extract.GuardedRendererTest;

class HostileCoverTest {

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void aRecursiveType3FontStillGivesTheBookACover() throws Exception {
        try (PDDocument doc = GuardedRendererTest.recursiveType3()) {
            assertDoesNotThrow(() -> PdfBook.cover(doc, PdfToDocx.Options.defaults(), false));
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void aFormFanOutFinishesTheCover() throws Exception {
        try (PDDocument doc = GuardedRendererTest.fanOut()) {
            assertDoesNotThrow(() -> PdfBook.cover(doc, PdfToDocx.Options.defaults(), false));
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void anImageBombIsLeftOffTheCover() throws Exception {
        try (PDDocument doc = GuardedRendererTest.imageBomb()) {
            assertDoesNotThrow(() -> PdfBook.cover(doc, PdfToDocx.Options.defaults(), false));
        }
    }

    @Test
    void anInterruptedCoverStopsTheConversion() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, () -> PdfBook.cover(doc, PdfToDocx.Options.defaults(), false));
            } finally {
                Thread.interrupted();
            }
        }
    }
}
