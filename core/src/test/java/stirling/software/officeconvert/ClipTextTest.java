package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class ClipTextTest {

    @Test
    void textThatOnlyClipsShowsWhenPaintFillsIt() throws IOException {
        String text = text("BT /F1 18 Tf 7 Tr 72 700 Td (Painted through letters) Tj ET 1 0 0 rg 60 690 400 30 re f Q"
                + " BT /F1 12 Tf 0 g 72 600 Td (Body) Tj ET");
        assertTrue(text.contains("Painted through letters"), text);
    }

    @Test
    void clipTextWithNothingPaintedStaysOut() throws IOException {
        String text = text("BT /F1 18 Tf 7 Tr 72 700 Td (Never painted) Tj ET Q BT /F1 12 Tf 0 Tr 72 600 Td (Body) Tj ET");
        assertFalse(text.contains("Never painted"), text);
    }

    @Test
    void clipTextEndsWithItsGraphicsState() throws IOException {
        String text = text("BT /F1 18 Tf 7 Tr 72 700 Td (Restored away) Tj ET Q 1 0 0 rg 60 690 400 30 re f"
                + " BT /F1 12 Tf 0 g 72 600 Td (Body) Tj ET");
        assertFalse(text.contains("Restored away"), text);
    }

    @Test
    void invisibleTextStaysOut() throws IOException {
        String text = text("BT /F1 18 Tf 3 Tr 72 700 Td (Not drawn) Tj ET 1 0 0 rg 60 690 400 30 re f Q"
                + " BT /F1 12 Tf 0 Tr 72 600 Td (Body) Tj ET");
        assertFalse(text.contains("Not drawn"), text);
    }

    private static String text(String content) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            page.setResources(resources);
            document.addPage(page);
            PDStream stream = new PDStream(document);
            try (OutputStream out = stream.createOutputStream()) {
                out.write(("q " + content).getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(stream);
            return PdfToText.text(document, PdfToDocx.Options.defaults());
        }
    }
}
