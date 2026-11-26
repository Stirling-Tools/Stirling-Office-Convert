package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.html.FixedHtml;
import stirling.software.officeconvert.memory.Admission;

class FixedHtmlAdmissionTest {

    @Test
    void aPageWhoseRenderingPassesTheBudgetIsRefusedUpFront() throws IOException {
        Admission.Limits saved = Admission.jvm().limits();
        Admission.configure(new Admission.Limits(10, 0, Duration.ZERO, true));
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(new PDRectangle(14_400, 14_400)));
            long render = FixedHtml.peakRenderBytes(doc, 1, Integer.MAX_VALUE, FixedHtml.Options.DEFAULT_DPI);
            assertTrue(render > Admission.jvm().budget(), () -> render + " <= " + Admission.jvm().budget());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertThrows(Admission.TooLarge.class, () -> PdfToHtml.convertFixed(doc, out, FixedHtml.Options.defaults()));
            assertTrue(out.size() == 0);
        } finally {
            Admission.configure(saved);
        }
    }

    @Test
    void aSmallPageStillConverts() throws IOException {
        Admission.Limits saved = Admission.jvm().limits();
        Admission.configure(new Admission.Limits(10, 0, Duration.ZERO, true));
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfToHtml.convertFixed(doc, out, FixedHtml.Options.defaults());
            assertTrue(out.size() > 0);
        } finally {
            Admission.configure(saved);
        }
    }
}
