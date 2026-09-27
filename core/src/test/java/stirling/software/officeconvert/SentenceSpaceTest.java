package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SentenceSpaceTest {

    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
    private static final String[] LINES = {
        "The overview draws on the existing literature.  However, the existing data are",
        "far from complete, and the estimates differ from one source to the next.  We",
        "have tried to reconcile them where the sources allow a fair comparison of the",
        "figures for each member state, and say so where they do not."};

    @TempDir Path dir;

    @Test
    void twoSpacesAfterASentenceStayTwo() throws Exception {
        String xml = convert(makePdf());
        assertTrue(xml.contains("literature.  However"), "two spaces after the first sentence");
        assertTrue(xml.contains("next.  We"), "two spaces after the second");
        assertFalse(xml.contains("the  existing"), "one space between words inside a sentence");
    }

    private String convert(Path pdf) throws IOException {
        Path docx = dir.resolve("spaces.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            return new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Path makePdf() throws IOException {
        Path pdf = dir.resolve("spaces.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                for (int i = 0; i < LINES.length; i++) {
                    cs.beginText();
                    cs.setFont(FONT, 12);
                    cs.newLineAtOffset(72, 720 - i * 16);
                    cs.showText(LINES[i]);
                    cs.endText();
                }
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }
}
