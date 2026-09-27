package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

class FigureTextTest {

    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final String[] LABELS = {"North 40%", "South 35%", "West 25%"};

    @TempDir Path dir;

    @Test
    void chartLabelsReadAsText() throws Exception {
        Path pdf = makePdf();
        Path txt = dir.resolve("chart.txt");
        PdfToText.convert(pdf, txt, PdfToDocx.Options.defaults());
        String text = Files.readString(txt, StandardCharsets.UTF_8);
        Path docx = dir.resolve("chart.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        String xml;
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            xml = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String label : LABELS) {
            assertTrue(text.contains(label), label + " is in the text file");
            assertTrue(xml.contains(label), label + " is in the document, as text or as the chart's description");
        }
        assertTrue(text.indexOf("Sales by region") < text.indexOf(LABELS[0]), "the chart reads after the text above it");
        assertTrue(xml.matches("(?s).*<wp:docPr [^>]*descr=\"[^\"]*North 40%.*"), "the chart is a picture described by its words");
        assertTrue(!xml.contains("prst=\"roundRect\""), "no slice turns into a rounded box");
    }

    private Path makePdf() throws IOException {
        Path pdf = dir.resolve("chart.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, 72, 760, 14, "Sales by region");
                text(cs, 72, 740, 11, "The chart below splits the year's sales between three regions.");
                float cx = 300;
                float cy = 520;
                float r = 120;
                float[][] colours = {{0.2f, 0.4f, 0.8f}, {0.9f, 0.5f, 0.1f}, {0.3f, 0.7f, 0.3f}};
                float start = 0;
                float[] shares = {0.40f, 0.35f, 0.25f};
                for (int i = 0; i < shares.length; i++) {
                    cs.setNonStrokingColor(colours[i][0], colours[i][1], colours[i][2]);
                    wedge(cs, cx, cy, r, start, start + shares[i] * 360);
                    cs.fill();
                    start += shares[i] * 360;
                }
                cs.setNonStrokingColor(1f);
                text(cs, cx + 20, cy + 40, 10, LABELS[0]);
                text(cs, cx - 90, cy - 30, 10, LABELS[1]);
                text(cs, cx + 20, cy - 70, 10, LABELS[2]);
                cs.setNonStrokingColor(0f);
                text(cs, 72, 360, 11, "Figures are for the calendar year and exclude returns.");
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static void wedge(PDPageContentStream cs, float cx, float cy, float r, float from, float to) throws IOException {
        cs.moveTo(cx, cy);
        cs.lineTo(cx + r * (float) Math.cos(Math.toRadians(from)), cy + r * (float) Math.sin(Math.toRadians(from)));
        for (float a = from; a < to; a += 30) {
            float b = Math.min(to, a + 30);
            double a0 = Math.toRadians(a);
            double a1 = Math.toRadians(b);
            float k = (float) (4.0 / 3.0 * Math.tan((a1 - a0) / 4));
            float x0 = cx + r * (float) Math.cos(a0);
            float y0 = cy + r * (float) Math.sin(a0);
            float x3 = cx + r * (float) Math.cos(a1);
            float y3 = cy + r * (float) Math.sin(a1);
            cs.curveTo(x0 - k * r * (float) Math.sin(a0), y0 + k * r * (float) Math.cos(a0),
                    x3 + k * r * (float) Math.sin(a1), y3 - k * r * (float) Math.cos(a1), x3, y3);
        }
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
