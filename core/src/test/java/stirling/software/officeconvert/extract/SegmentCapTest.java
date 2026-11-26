package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import stirling.software.officeconvert.PdfToDocx;

class SegmentCapTest {

    private static final int CAP = 50_000;

    @TempDir Path dir;

    @Test
    void linesPastTheCapAreDrawnAsAPicture() throws IOException {
        PageGraphics g = read(60_000, false);
        assertEquals(CAP, g.rules().size() + g.fills().size());
        assertFalse(g.pastBudget().isEmpty());
    }

    @Test
    void boxesPastTheCapAreDrawnAsAPicture() throws IOException {
        PageGraphics g = read(60_000, true);
        assertTrue(g.rules().size() + g.fills().size() <= CAP);
        assertFalse(g.pastBudget().isEmpty());
    }

    @Test
    void linesUpToTheCapAreKept() throws IOException {
        PageGraphics g = read(2_000, false);
        assertEquals(2_000, g.rules().size());
        assertTrue(g.pastBudget().isEmpty());
    }

    @Test
    void aPageOverTheCapStillConverts() throws IOException {
        Path pdf = dir.resolve("dense.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                cs.newLineAtOffset(40, 760);
                cs.showText("Dense scatter plot");
                cs.endText();
                draw(cs, 60_000, false);
            }
            document.save(pdf.toFile());
        }
        Path docx = dir.resolve("dense.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            String body = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(body.contains("Dense scatter plot"));
            assertTrue(zip.stream().anyMatch(e -> e.getName().startsWith("word/media/")));
        }
    }

    private static PageGraphics read(int count, boolean boxes) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                draw(cs, count, boxes);
            }
            return GraphicsCollector.read(page, PageReader.displayTransform(page.getCropBox(), 0), 612, 792,
                    new ParsedStreams());
        }
    }

    private static void draw(PDPageContentStream cs, int count, boolean boxes) throws IOException {
        cs.setLineWidth(0.1f);
        for (int i = 0; i < count; i++) {
            float x = 20 + (i % 100) * 5.5f;
            float y = 20 + (i / 100) * 1.2f;
            if (boxes) {
                cs.addRect(x, y, 4, 0.8f);
            } else {
                cs.moveTo(x, y);
                cs.lineTo(x + 4, y);
            }
            cs.stroke();
        }
    }
}
