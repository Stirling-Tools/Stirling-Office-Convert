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

class SkillCardTest {

    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final String[] SKILLS = {"Negotiation", "Planning", "Spreadsheets", "Public speaking", "Budgeting"};

    @TempDir Path dir;

    @Test
    void skillsRatedWithDotsStayText() throws Exception {
        assertSkillsAreText(makePdf(false));
    }

    @Test
    void skillsRatedWithBarsStayText() throws Exception {
        assertSkillsAreText(makePdf(true));
    }

    private void assertSkillsAreText(Path pdf) throws IOException {
        Path docx = dir.resolve(pdf.getFileName().toString().replace(".pdf", ".docx"));
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        String xml;
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            xml = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
        StringBuilder text = new StringBuilder();
        Matcher m = Pattern.compile("<w:t(?: [^>]*)?>([^<]*)</w:t>").matcher(xml);
        while (m.find()) {
            text.append(m.group(1)).append(' ');
        }
        for (String skill : SKILLS) {
            assertTrue(text.toString().contains(skill), skill + " is text in the document");
        }
        assertTrue(text.toString().contains("Skills"), "the card's heading is text");
    }

    private Path makePdf(boolean bars) throws IOException {
        Path pdf = dir.resolve(bars ? "bars.pdf" : "dots.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, 72, 780, 18, "Alex Example");
                text(cs, 72, 760, 11, "Project manager with ten years in logistics and supply planning.");
                cs.setNonStrokingColor(0.93f, 0.95f, 0.98f);
                roundedBox(cs, 72, 520, 300, 200, 10);
                cs.fill();
                cs.setNonStrokingColor(0f);
                text(cs, 90, 695, 13, "Skills");
                for (int i = 0; i < SKILLS.length; i++) {
                    float y = 665 - i * 28;
                    text(cs, 90, y, 11, SKILLS[i]);
                    if (bars) {
                        cs.setNonStrokingColor(0.8f);
                        roundedBox(cs, 250, y, 100, 8, 4);
                        cs.fill();
                        cs.setNonStrokingColor(0.1f, 0.5f, 0.6f);
                        roundedBox(cs, 250, y, 50 + 10 * i, 8, 4);
                        cs.fill();
                    } else {
                        for (int d = 0; d < 5; d++) {
                            if (d < 4 - i % 2) {
                                cs.setNonStrokingColor(0.1f, 0.5f, 0.6f);
                            } else {
                                cs.setNonStrokingColor(0.8f);
                            }
                            circle(cs, 250 + d * 16, y + 4, 5);
                            cs.fill();
                        }
                    }
                    cs.setNonStrokingColor(0f);
                }
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static void circle(PDPageContentStream cs, float cx, float cy, float r) throws IOException {
        float k = 0.5523f * r;
        cs.moveTo(cx + r, cy);
        cs.curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r);
        cs.curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy);
        cs.curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r);
        cs.curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy);
        cs.closePath();
    }

    private static void roundedBox(PDPageContentStream cs, float x, float y, float w, float h, float r) throws IOException {
        float k = 0.5523f * r;
        cs.moveTo(x + r, y);
        cs.lineTo(x + w - r, y);
        cs.curveTo(x + w - r + k, y, x + w, y + r - k, x + w, y + r);
        cs.lineTo(x + w, y + h - r);
        cs.curveTo(x + w, y + h - r + k, x + w - r + k, y + h, x + w - r, y + h);
        cs.lineTo(x + r, y + h);
        cs.curveTo(x + r - k, y + h, x, y + h - r + k, x, y + h - r);
        cs.lineTo(x, y + r);
        cs.curveTo(x, y + r - k, x + r - k, y, x + r, y);
        cs.closePath();
    }

    private static void text(PDPageContentStream cs, float x, float y, float size, String s) throws IOException {
        cs.beginText();
        cs.setFont(FONT, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }
}
