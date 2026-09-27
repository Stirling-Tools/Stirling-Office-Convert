package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoxDiagramTest {

    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final String[] ROLES = {"Director", "Finance Lead", "Operations Lead", "Support Lead"};
    private static final Pattern TEXT_BOX = Pattern.compile("<wps:wsp><wps:cNvSpPr txBox=\"1\"/>.*?</wps:wsp>", Pattern.DOTALL);

    @TempDir Path dir;

    @Test
    void boxesKeepTheirText() throws Exception {
        String xml = convert(makePdf(false));
        assertEquals(ROLES.length, count(xml, "<wps:txbx>"));
        for (String role : ROLES) {
            assertTrue(xml.contains(role), role + " stays text");
        }
        assertTrue(xml.contains("behindDoc=\"1\"") && xml.contains("<pic:pic"), "the drawing goes behind as a picture");
    }

    @Test
    void labelsTakeTheirBoxColour() throws Exception {
        String xml = convert(makePdf(false));
        Matcher m = TEXT_BOX.matcher(xml);
        int white = 0;
        while (m.find()) {
            white += m.group().contains("<a:solidFill><a:srgbClr val=\"FFFFFF\"><a:alpha val=\"0\"/></a:srgbClr></a:solidFill>") ? 1 : 0;
        }
        assertEquals(ROLES.length, white, "every label carries its box's colour, unseen");
    }

    @Test
    void outlinedWatermarkLettersTakeNoText() throws Exception {
        String xml = convert(makePdf(true));
        assertEquals(ROLES.length, count(xml, "<wps:txbx>"), "one text box per drawn box");
        Matcher m = TEXT_BOX.matcher(xml);
        while (m.find()) {
            String text = m.group().replaceAll("<[^>]+>", "");
            assertTrue(List.of(ROLES).contains(text), "each label holds its own box's words: " + text);
        }
    }

    private String convert(Path pdf) throws IOException {
        Path docx = dir.resolve(pdf.getFileName().toString().replace(".pdf", ".docx"));
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            return new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Path makePdf(boolean watermark) throws IOException {
        Path pdf = dir.resolve(watermark ? "watermarked.pdf" : "chart.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                if (watermark) {
                    watermark(cs);
                }
                box(cs, 230, 700, ROLES[0]);
                for (int i = 1; i < ROLES.length; i++) {
                    float x = 80 + (i - 1) * 160;
                    box(cs, x, 600, ROLES[i]);
                    cs.moveTo(290, 700);
                    cs.lineTo(x + 60, 632);
                    cs.stroke();
                    cs.moveTo(x + 55, 632);
                    cs.lineTo(x + 65, 632);
                    cs.lineTo(x + 60, 624);
                    cs.closePath();
                    cs.fill();
                }
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static void watermark(PDPageContentStream cs) throws IOException {
        PDExtendedGraphicsState faint = new PDExtendedGraphicsState();
        faint.setStrokingAlphaConstant(0.5f);
        faint.setNonStrokingAlphaConstant(0.5f);
        cs.saveGraphicsState();
        cs.setGraphicsStateParameters(faint);
        cs.setStrokingColor(0.87f);
        cs.setLineWidth(1f);
        for (int i = 0; i < 4; i++) {
            ring(cs, 150 + i * 44, 560 + i * 30, 26, 18);
            cs.stroke();
        }
        cs.restoreGraphicsState();
    }

    private static void ring(PDPageContentStream cs, float cx, float cy, float outer, float inner) throws IOException {
        for (float r : new float[] {outer, inner}) {
            for (int k = 0; k <= 32; k++) {
                double a = 2 * Math.PI * k / 32;
                float x = cx + (float) (r * Math.cos(a));
                float y = cy + (float) (r * Math.sin(a));
                if (k == 0) {
                    cs.moveTo(x, y);
                } else {
                    cs.lineTo(x, y);
                }
            }
            cs.closePath();
        }
    }

    private static void box(PDPageContentStream cs, float x, float y, String text) throws IOException {
        cs.setNonStrokingColor(1f);
        cs.addRect(x, y, 120, 24);
        cs.fillAndStroke();
        cs.setNonStrokingColor(0f);
        cs.beginText();
        cs.setFont(FONT, 10);
        cs.newLineAtOffset(x + 12, y + 8);
        cs.showText(text);
        cs.endText();
    }

    private static int count(String s, String part) {
        int n = 0;
        for (int i = s.indexOf(part); i >= 0; i = s.indexOf(part, i + 1)) {
            n++;
        }
        return n;
    }
}
