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

class DuplicateTextTest {

    @Test
    void tinyTextKeepsDoubledLetters() throws IOException {
        String text = text("BT /F1 2 Tf 72 700 Td (Fine print that is still all printed) Tj ET"
                + " BT /F1 12 Tf 72 680 Td (Normal line.) Tj ET");
        assertTrue(text.contains("Fine print that is still all printed"), text);
    }

    @Test
    void offsetShadowIsDroppedAsAWholeRun() throws IOException {
        String text = text("BT 0.7 g /F1 18 Tf 73.5 698.5 Td (Shadowed title) Tj ET"
                + " BT 0 g /F1 18 Tf 72 700 Td (Shadowed title) Tj ET BT /F1 12 Tf 72 670 Td (Body.) Tj ET");
        assertTrue(text.contains("Shadowed title"), text);
        assertFalse(text.contains("tt"), text);
        assertFalse(text.contains("Shadowed title Shadowed"), text);
    }

    @Test
    void rotatedFakeBoldReadsOnce() throws IOException {
        String text = text("BT /F1 12 Tf 0 1 -1 0 40 300 Tm (Rotated heading) Tj ET BT /F1 12 Tf 0 1 -1 0 40.3 300 Tm (Rotated heading) Tj ET"
                + " BT /F1 12 Tf 72 700 Td (Upright body text on the page.) Tj ET");
        assertTrue(text.contains("Rotated heading"), text);
        assertFalse(text.contains("RRoo"), text);
        assertFalse(text.contains("Rotated heading Rotated"), text);
    }

    @Test
    void doubledLettersNextToEachOtherAreNotAShadow() throws IOException {
        String text = text("BT /F1 12 Tf 72 700 Td (Balloons fill all small rooms.) Tj ET");
        assertTrue(text.contains("Balloons fill all small rooms."), text);
    }

    private static String text(String content) throws IOException {
        try (PDDocument document = document(content)) {
            return PdfToText.text(document, PdfToDocx.Options.defaults());
        }
    }

    private static PDDocument document(String content) throws IOException {
        PDDocument document = new PDDocument();
        PDPage page = new PDPage(PDRectangle.LETTER);
        PDResources resources = new PDResources();
        resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
        page.setResources(resources);
        document.addPage(page);
        page.setContents(stream(document, content));
        return document;
    }

    private static PDStream stream(PDDocument document, String content) throws IOException {
        PDStream stream = new PDStream(document);
        try (OutputStream out = stream.createOutputStream()) {
            out.write(content.getBytes(StandardCharsets.US_ASCII));
        }
        return stream;
    }
}
