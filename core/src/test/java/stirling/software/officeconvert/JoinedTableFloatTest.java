package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
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
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JoinedTableFloatTest {

    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
    private static final String WORDS = "The survey covered every district and the results were checked twice by the office before release";

    @TempDir Path dir;

    @Test
    void secondPagePaperStaysOnTheSecondPage() throws Exception {
        Path pdf = dir.resolve("joined.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage first = new PDPage(PDRectangle.A4);
            doc.addPage(first);
            try (PDPageContentStream cs = new PDPageContentStream(doc, first)) {
                for (int i = 0; i < 50; i++) {
                    text(cs, 72, 770 - i * 14, WORDS.substring(0, 90 - (i % 3)));
                }
            }
            PDPage second = new PDPage(PDRectangle.A4);
            doc.addPage(second);
            try (PDPageContentStream cs = new PDPageContentStream(doc, second)) {
                BufferedImage paper = new BufferedImage(60, 85, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = paper.createGraphics();
                g.setPaint(new java.awt.GradientPaint(0, 0, new Color(250, 240, 220), 0, 85, new Color(220, 230, 250)));
                g.fillRect(0, 0, 60, 85);
                g.dispose();
                cs.drawImage(LosslessFactory.createFromImage(doc, paper), 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                text(cs, 72, 770, "and the figures below were agreed.");
                table(cs, 72, 740);
            }
            doc.save(pdf.toFile());
        }
        Path docx = dir.resolve("joined.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        String xml;
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            xml = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
        int drawing = xml.indexOf("<w:drawing>");
        int firstPageText = xml.indexOf("The survey covered");
        int secondPageText = xml.indexOf("figures below were agreed");
        assertTrue(drawing >= 0 && firstPageText >= 0 && secondPageText >= 0, "picture and both pages' text are written");
        assertTrue(drawing > secondPageText, "the picture is anchored on the second page's text, not before the first page's");
    }

    private static void table(PDPageContentStream cs, float x, float top) throws IOException {
        String[][] rows = {{"District", "Homes", "Share"}, {"North", "1,204", "31%"}, {"South", "982", "26%"}, {"West", "1,630", "43%"}};
        cs.setLineWidth(0.8f);
        for (int r = 0; r <= rows.length; r++) {
            cs.moveTo(x, top - r * 20);
            cs.lineTo(x + 360, top - r * 20);
        }
        for (int c = 0; c <= 3; c++) {
            cs.moveTo(x + c * 120, top);
            cs.lineTo(x + c * 120, top - rows.length * 20);
        }
        cs.stroke();
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < 3; c++) {
                text(cs, x + 6 + c * 120, top - r * 20 - 14, rows[r][c]);
            }
        }
    }

    private static void text(PDPageContentStream cs, float x, float y, String s) throws IOException {
        cs.beginText();
        cs.setFont(FONT, 11);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }
}
