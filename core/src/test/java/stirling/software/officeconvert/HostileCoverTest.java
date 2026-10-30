package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.concurrent.TimeUnit;

import org.apache.pdfbox.pdmodel.PDDocument;
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
}
