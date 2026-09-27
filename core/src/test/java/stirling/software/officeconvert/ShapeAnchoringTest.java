package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

class ShapeAnchoringTest {

    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final String[] SKILLS = {"Nutrition", "Sales", "Coaching", "Planning"};

    @TempDir Path dir;

    @Test
    void barsRideOnTheirLabels() throws Exception {
        Path docx = dir.resolve("skills.docx");
        PdfToDocx.convert(makePdf(), docx, PdfToDocx.Options.defaults());
        String xml;
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            xml = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(SKILLS.length * 5, count(xml, "<wp:positionV relativeFrom=\"paragraph\">"));
        assertEquals(1, count(xml, "<wp:positionV relativeFrom=\"page\">"));
        int rows = 0;
        Matcher p = Pattern.compile("<w:p>.*?</w:p>", Pattern.DOTALL).matcher(xml);
        while (p.find()) {
            if (p.group().contains("relativeFrom=\"paragraph\"") && !p.group().contains("<w:t")) {
                rows++;
            }
        }
        assertEquals(SKILLS.length - 1, rows);
    }

    private Path makePdf() throws IOException {
        Path pdf = dir.resolve("skills.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(0.9f);
                cs.addRect(0, 0, 24, 792);
                cs.fill();
                for (int i = 0; i < SKILLS.length; i++) {
                    float baseline = 700 - i * 30;
                    cs.setNonStrokingColor(0f);
                    cs.beginText();
                    cs.setFont(FONT, 11);
                    cs.newLineAtOffset(72, baseline);
                    cs.showText(SKILLS[i]);
                    cs.endText();
                    for (int s = 0; s < 5; s++) {
                        cs.setNonStrokingColor(s < 3 + i % 2 ? 0.1f : 0.7f);
                        cs.addRect(72 + s * 22, baseline - 9, 20, 2);
                        cs.fill();
                    }
                }
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static int count(String s, String part) {
        int n = 0;
        for (int i = s.indexOf(part); i >= 0; i = s.indexOf(part, i + 1)) {
            n++;
        }
        return n;
    }
}
