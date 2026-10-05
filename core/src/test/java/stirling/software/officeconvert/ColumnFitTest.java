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

class ColumnFitTest {

    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final float SIZE = 10;
    private static final float PITCH = 11.5f;
    private static final float FOOTER_BASELINE = 755;
    private static final String LINE = "limit employer and employee share the wage ";
    private static final String[] ENDS = {"base", "fund", "rate", "cost"};
    private static final String NEXT = "limit";

    @TempDir Path dir;

    @Test
    void bodyEndsAboveThePageNumberLine() throws Exception {
        String xml = convert(makePdf());
        Matcher m = Pattern.compile("<w:pgMar [^>]*w:bottom=\"(\\d+)\"").matcher(xml);
        assertTrue(m.find(), "section has page margins");
        float bottom = Integer.parseInt(m.group(1)) / 20f;
        float footerTop = FOOTER_BASELINE - 0.7f * SIZE;
        assertTrue(792 - bottom <= footerTop, "body ends above the page number line, bottom margin " + bottom);
    }

    @Test
    void justifiedLinesLeaveNoRoomForTheNextWord() throws Exception {
        String xml = convert(makePdf());
        Matcher m = Pattern.compile("<w:ind w:left=\"0\" w:right=\"(-?\\d+)\"[^>]*/><w:jc w:val=\"both\"/>").matcher(xml);
        assertTrue(m.find(), "justified body paragraph");
        float right = Integer.parseInt(m.group(1)) / 20f;
        assertTrue(right >= 2.5f, "edge pulled in so space shrinking cannot pull the next word up, right indent " + right);
    }

    private String convert(Path pdf) throws IOException {
        Path docx = dir.resolve("cols.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            return new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Path makePdf() throws IOException {
        Path pdf = dir.resolve("cols.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int p = 1; p <= 4; p++) {
                String line = LINE + ENDS[p - 1];
                float natural = FONT.getStringWidth(line) / 1000f * SIZE;
                float width = FONT.getStringWidth(line + " " + NEXT) / 1000f * SIZE - 3;
                float spacing = (width - natural) / (line.split(" ").length - 1);
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    for (float left : new float[] {42, 42 + width + 18}) {
                        for (int i = 0; i < 55; i++) {
                            boolean last = i % 7 == 6;
                            text(cs, left, 682 - i * PITCH, last ? NEXT + " ends " + ENDS[p - 1] + "." : line, last ? 0 : spacing);
                        }
                    }
                    float y = 792 - FOOTER_BASELINE;
                    float right = 42 + 2 * width + 18;
                    if (p % 2 == 0) {
                        text(cs, 42, y, String.valueOf(p), 0);
                        text(cs, right - 100, y, "Publication 15 (2026)", 0);
                    } else if (p > 1) {
                        text(cs, 42, y, "Publication 15 (2026)", 0);
                        text(cs, right - 6, y, String.valueOf(p), 0);
                    }
                }
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static void text(PDPageContentStream cs, float x, float y, String s, float wordSpacing) throws IOException {
        cs.beginText();
        cs.setFont(FONT, SIZE);
        cs.setWordSpacing(wordSpacing);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }
}
