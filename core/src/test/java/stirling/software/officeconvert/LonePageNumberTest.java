package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LonePageNumberTest {

    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    @TempDir Path dir;

    private Path pdf(String bottom, boolean beside) throws IOException {
        Path pdf = dir.resolve("one.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = 700;
                for (int i = 0; i < 12; i++) {
                    text(cs, 72, y, "Line " + i + " of a short letter that fits on one page with room to spare.");
                    y -= 14;
                }
                text(cs, 303, 40, bottom);
                if (beside) {
                    text(cs, 72, 40, "Total");
                }
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static void text(PDPageContentStream cs, float x, float y, String s) throws IOException {
        cs.beginText();
        cs.setFont(FONT, 11);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    private String[] convert(Path pdf) throws Exception {
        Path docx = dir.resolve("one.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        StringBuilder footers = new StringBuilder();
        String body;
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            body = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
            for (ZipEntry e : zip.stream().toList()) {
                if (e.getName().startsWith("word/footer")) {
                    footers.append(new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return new String[] {body, footers.toString()};
    }

    @Test
    void aLonePageNumberOnAOnePageDocumentBecomesAFooterField() throws Exception {
        String[] parts = convert(pdf("1", false));
        assertTrue(parts[1].contains("PAGE"), parts[1]);
        assertFalse(parts[0].contains(">1</w:t>"), "the number left the body");
    }

    @Test
    void aOneBesideOtherTextStaysInTheBody() throws Exception {
        String[] parts = convert(pdf("1", true));
        assertFalse(parts[1].contains("PAGE"));
        assertTrue(parts[0].contains(">1</w:t>"));
    }
}
