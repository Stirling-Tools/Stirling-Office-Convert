package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TopRowFiguresTest {

    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    @TempDir Path dir;

    private static void text(PDPageContentStream cs, float x, float y, String s) throws IOException {
        cs.beginText();
        cs.setFont(FONT, 10);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    private static void right(PDPageContentStream cs, float right, float y, String s) throws IOException {
        text(cs, right - FONT.getStringWidth(s) / 100f, y, s);
    }

    @Test
    void figuresInTheFirstRowsOfEveryPageStayInTheirCells() throws Exception {
        Path pdf = dir.resolve("sheet.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int p = 0; p < 3; p++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    float y = 842 - 60;
                    text(cs, 40, y, "Item");
                    text(cs, 140, y, "Regional");
                    text(cs, 220, y, "Strategic");
                    text(cs, 300, y, "Legal");
                    for (int r = 0; r < 40; r++) {
                        y -= 15;
                        text(cs, 40, y, "Row " + (p * 40 + r + 1));
                        right(cs, 190, y, String.format("%d,%03d", 10 + (p * 97 + r * 13) % 89, (p * 311 + r * 37) % 1000));
                        right(cs, 270, y, String.format("%d,%03d", 11 + (p * 53 + r * 7) % 87, (p * 173 + r * 91) % 1000));
                        right(cs, 340, y, String.format("%d,%03d", 12 + (p * 31 + r * 11) % 85, (p * 229 + r * 53) % 1000));
                    }
                }
            }
            doc.save(pdf.toFile());
        }
        Path docx = dir.resolve("sheet.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        String body;
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            body = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
        for (int p = 0; p < 3; p++) {
            String first = String.format("%d,%03d", 10 + p * 97 % 89, p * 311 % 1000);
            Matcher m = Pattern.compile("<w:tc>(?:(?!</w:tc>).)*>" + Pattern.quote(first) + "</w:t>(?:(?!</w:tc>).)*</w:tc>")
                    .matcher(body);
            assertTrue(m.find(), "page " + (p + 1) + " keeps " + first + " in its own cell");
        }
    }
}
