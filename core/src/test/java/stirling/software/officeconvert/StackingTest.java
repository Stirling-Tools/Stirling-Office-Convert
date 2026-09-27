package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
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
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.model.Stacking;

class StackingTest {

    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final float PAGE_W = PDRectangle.A4.getWidth();
    private static final float PAGE_H = PDRectangle.A4.getHeight();

    @TempDir Path dir;

    @Test
    void heightsStayWithinWordsLimit() throws Exception {
        String xml = convert(page(true, false));
        Matcher m = Pattern.compile("relativeHeight=\"(\\d+)\"").matcher(xml);
        int found = 0;
        while (m.find()) {
            found++;
            assertTrue(Long.parseLong(m.group(1)) <= Stacking.LIMIT, "Word drops heights over its limit: " + m.group(1));
        }
        assertTrue(found >= 3, "the image, the card and the watermark float");
    }

    @Test
    void watermarkComesThroughWhole() throws Exception {
        String xml = convert(page(true, false));
        Matcher m = Pattern.compile("<wp:anchor [^>]*behindDoc=\"1\"[^>]*>.*?<wp:extent cx=\"(\\d+)\"", Pattern.DOTALL)
                .matcher(xml);
        boolean whole = false;
        while (m.find()) {
            whole |= Math.abs(Long.parseLong(m.group(1)) / 12700f - 178f) < 2f;
        }
        assertTrue(whole, "the lettering is one picture as wide as the word");
    }

    @Test
    void paintedFrameLeavesThePageSeen() throws Exception {
        String xml = convert(page(false, true));
        Matcher m = Pattern.compile("<wp:anchor .*?<wp:extent cx=\"(\\d+)\" cy=\"(\\d+)\".*?</wp:anchor>", Pattern.DOTALL)
                .matcher(xml);
        while (m.find()) {
            boolean wholePage = Long.parseLong(m.group(1)) / 12700f > PAGE_W - 2 && Long.parseLong(m.group(2)) / 12700f > PAGE_H - 2;
            assertFalse(wholePage && m.group().contains("<wps:wsp>") && m.group().contains("FFFFFF"),
                    "a frame is no white sheet over the page");
        }
        assertTrue(xml.contains("<pic:pic"), "the page's picture stays");
    }

    private String convert(Path pdf) throws IOException {
        Path docx = dir.resolve(pdf.getFileName() + ".docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            return new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Path page(boolean watermark, boolean frame) throws IOException {
        Path pdf = dir.resolve((watermark ? "watermark" : "frame") + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                if (frame) {
                    cs.drawImage(image(doc, new Color(20, 90, 110)), 0, 0, PAGE_W, PAGE_H);
                    cs.setNonStrokingColor(1f);
                    cs.addRect(0, 0, PAGE_W, PAGE_H);
                    cs.addRect(36, 36, PAGE_W - 72, PAGE_H - 72);
                    cs.fillEvenOdd();
                } else {
                    cs.drawImage(image(doc, new Color(200, 60, 40)), 400, 700, 120, 80);
                    cs.setNonStrokingColor(0.2f, 0.4f, 0.8f);
                    cs.addRect(72, 560, 200, 30);
                    cs.fill();
                }
                if (watermark) {
                    PDExtendedGraphicsState faint = new PDExtendedGraphicsState();
                    faint.setNonStrokingAlphaConstant(0.4f);
                    cs.saveGraphicsState();
                    cs.setGraphicsStateParameters(faint);
                    cs.setNonStrokingColor(0.5f);
                    for (int i = 0; i < 4; i++) {
                        ring(cs, 170 + i * 46, 400, 20);
                    }
                    cs.restoreGraphicsState();
                }
                cs.setNonStrokingColor(frame ? 1f : 0f);
                for (int i = 0; i < 6; i++) {
                    cs.beginText();
                    cs.setFont(FONT, 11);
                    cs.newLineAtOffset(72, 740 - i * 16);
                    cs.showText("Body text line " + (i + 1) + " of the page, with a few more words to fill it.");
                    cs.endText();
                }
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static PDImageXObject image(PDDocument doc, Color c) throws IOException {
        BufferedImage bi = new BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bi.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, 60, 40);
        g.setColor(Color.WHITE);
        g.fillOval(10, 10, 20, 20);
        g.dispose();
        return LosslessFactory.createFromImage(doc, bi);
    }

    private static void ring(PDPageContentStream cs, float cx, float cy, float r) throws IOException {
        circle(cs, cx, cy, r);
        circle(cs, cx, cy, r * 0.6f);
        cs.fillEvenOdd();
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
}
