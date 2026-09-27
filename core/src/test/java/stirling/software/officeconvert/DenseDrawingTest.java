package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class DenseDrawingTest {

    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final String[] STREETS = {"Elm Street", "Mill Lane", "Harbour Road", "Station Way", "Quarry Hill"};
    private static final int POINTS = 100;
    private static final int UPPER_WALKS = 2000;
    private static final int LOWER_WALKS = 600;

    @TempDir Path dir;

    @Test
    void mapPastThePointBudgetIsOnePicture() throws Exception {
        Path pdf = makePdf();
        Path docx = dir.resolve("map.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        String xml;
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            xml = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(1, count(xml, "<w:drawing>"), "the map is one picture");
        StringBuilder text = new StringBuilder();
        Matcher m = Pattern.compile("<w:t(?: [^>]*)?>([^<]*)</w:t>").matcher(xml);
        while (m.find()) {
            text.append(m.group(1)).append(' ');
        }
        for (String street : STREETS) {
            assertFalse(text.toString().contains(street), street + " is drawn with the map, not set as text");
        }
        assertTrue(text.toString().contains("District map"), "the heading above the map stays text");
        assertTrue(text.toString().contains("The boundary follows the river."), "the note below the map stays text");
        assertTrue(xml.contains("descr=\"Elm Street"), "the map is described by its labels");
    }

    private static int count(String s, String part) {
        int n = 0;
        for (int i = s.indexOf(part); i >= 0; i = s.indexOf(part, i + 1)) {
            n++;
        }
        return n;
    }

    private Path makePdf() throws IOException {
        Path pdf = dir.resolve("map.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, 72, 790, 16, "District map");
                cs.setStrokingColor(0.45f);
                cs.setLineWidth(0.4f);
                long[] seed = {7};
                for (int i = 0; i < UPPER_WALKS; i++) {
                    walk(cs, seed, 450, 700);
                }
                for (int i = 0; i < LOWER_WALKS; i++) {
                    walk(cs, seed, 200, 450);
                }
                cs.setNonStrokingColor(0f);
                for (int i = 0; i < STREETS.length; i++) {
                    text(cs, 100 + 80 * i, 420 - 45 * i, 8, STREETS[i]);
                }
                text(cs, 72, 160, 11, "The boundary follows the river.");
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static void walk(PDPageContentStream cs, long[] seed, float bottom, float top) throws IOException {
        float x = 80 + 440 * next(seed);
        float y = bottom + (top - bottom) * next(seed);
        cs.moveTo(x, y);
        for (int p = 1; p < POINTS; p++) {
            x = Math.clamp(x + 6 * next(seed) - 3, 80, 520);
            y = Math.clamp(y + 6 * next(seed) - 3, bottom, top);
            cs.lineTo(x, y);
        }
        cs.stroke();
    }

    private static float next(long[] seed) {
        seed[0] = seed[0] * 6364136223846793005L + 1442695040888963407L;
        return (seed[0] >>> 40) / (float) (1L << 24);
    }

    private static void text(PDPageContentStream cs, float x, float y, float size, String s) throws IOException {
        cs.beginText();
        cs.setFont(FONT, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }
}
