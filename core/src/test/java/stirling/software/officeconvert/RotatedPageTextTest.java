package stirling.software.officeconvert;

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

class RotatedPageTextTest {

    private static final String STAMP = "Downloaded from example.org on 1 January 2024 by a reader";

    @Test
    void uprightLineSurvivesAMostlyRotatedPage() throws IOException {
        String text = text("BT /F1 12 Tf 0 1 -1 0 300 200 Tm (" + STAMP + ") Tj ET BT /F1 12 Tf 72 700 Td (Upright line.) Tj ET");
        assertTrue(text.contains("Upright line."), text);
        assertTrue(text.contains(STAMP), text);
    }

    @Test
    void ocrLayerSurvivesARotatedVisibleStamp() throws IOException {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            content.append("BT 3 Tr /F1 11 Tf 72 ").append(700 - i * 14).append(" Td (Scanned line ").append(i)
                    .append(" recognised by OCR.) Tj ET ");
        }
        content.append("BT 0 Tr /F1 1 Tf 0 -9 9 0 580 600 Tm (" + STAMP + ") Tj ET");
        String text = text(content.toString());
        assertTrue(text.contains("Scanned line 0 recognised by OCR."), text);
        assertTrue(text.contains("Scanned line 11 recognised by OCR."), text);
        assertTrue(text.contains(STAMP), text);
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
