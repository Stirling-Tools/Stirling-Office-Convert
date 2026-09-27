package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DarkModeTest {

    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final Pattern PARAGRAPH = Pattern.compile("<w:p>.*?</w:p>", Pattern.DOTALL);

    @TempDir Path dir;

    @Test
    void whiteBackdropPictureIsLeftOut() throws Exception {
        Path pdf = dir.resolve("backdrop.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(JPEGFactory.createFromImage(doc, plain(Color.WHITE)), 0, 0, 595.28f, 841.89f);
                text(cs, 72, 760, "A letter set on a white picture of the whole page.");
            }
            doc.save(pdf.toFile());
        }
        String xml = convert(pdf);
        assertFalse(xml.contains("<pic:pic"), "no picture of blank white");
        assertTrue(xml.contains("A letter set on a white picture"));
    }

    @Test
    void whitePatternPaperIsLeftOut() throws Exception {
        Path pdf = dir.resolve("pattern.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            page.setResources(new PDResources());
            doc.addPage(page);
            PDTilingPattern tile = new PDTilingPattern();
            tile.setPaintType(PDTilingPattern.PAINT_COLORED);
            tile.setTilingType(PDTilingPattern.TILING_CONSTANT_SPACING);
            tile.setBBox(new PDRectangle(0, 0, 595.28f, 841.89f));
            tile.setXStep(595.28f);
            tile.setYStep(841.89f);
            PDResources tileResources = new PDResources();
            COSName image = tileResources.add(JPEGFactory.createFromImage(doc, plain(Color.WHITE)));
            tile.setResources(tileResources);
            try (OutputStream out = tile.getContentStream().createOutputStream()) {
                out.write(("q 595.28 0 0 841.89 0 0 cm /" + image.getName() + " Do Q").getBytes(StandardCharsets.US_ASCII));
            }
            COSName name = page.getResources().add(tile);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(new PDColor(name, new PDPattern(null)));
                cs.addRect(0, 0, 595.28f, 841.89f);
                cs.fill();
                cs.setNonStrokingColor(0f);
                text(cs, 72, 760, "A CV set on a page-sized pattern of white.");
            }
            doc.save(pdf.toFile());
        }
        String xml = convert(pdf);
        assertFalse(xml.contains("<pic:pic"), "no picture of the white paper");
        assertTrue(xml.contains("A CV set on a page-sized pattern"));
    }

    @Test
    void whitePictureOverPaintStays() throws Exception {
        Path pdf = dir.resolve("whiteout.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(0.2f, 0.4f, 0.8f);
                cs.addRect(72, 400, 450, 300);
                cs.fill();
                cs.drawImage(JPEGFactory.createFromImage(doc, plain(Color.WHITE)), 150, 450, 200, 150);
                cs.setNonStrokingColor(0f);
                text(cs, 72, 760, "A blue panel with a white patch over it.");
            }
            doc.save(pdf.toFile());
        }
        assertTrue(convert(pdf).contains("<pic:pic"), "the white patch is drawn");
    }

    @Test
    void headingOnABarTakesTheBarColour() throws Exception {
        Path pdf = dir.resolve("bars.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(0.85f, 0.92f, 1f);
                cs.addRect(66, 752, 470, 26);
                cs.fill();
                cs.setNonStrokingColor(0f);
                text(cs, 72, 760, "Quarterly service report");
                for (int i = 0; i < 4; i++) {
                    text(cs, 72, 730 - i * 15, "The service desk closed most requests within the agreed time and kept customers informed.");
                }
                cs.setNonStrokingColor(0.9f);
                cs.addRect(72, 560, 220, 110);
                cs.fill();
                cs.setNonStrokingColor(0f);
                String[] card = {"Highlights", "Response time fell by a fifth.", "Two new regional desks opened."};
                for (int i = 0; i < card.length; i++) {
                    text(cs, 88, i == 0 ? 645 : 640 - i * 15, card[i]);
                }
                for (int i = 0; i < 3; i++) {
                    text(cs, 72, 530 - i * 15, "Each regional desk now reports its figures weekly, and the numbers are shared with all staff.");
                }
            }
            doc.save(pdf.toFile());
        }
        String xml = convert(pdf);
        assertEquals("D9EBFF", shading(xml, "Quarterly service report"), "the heading takes the bar's blue");
        assertTrue(paragraph(xml, "Quarterly service report").contains("relativeFrom=\"paragraph\""), "the bar rides on its heading");
        assertEquals("", shading(xml, "Highlights"), "a card of several paragraphs keeps its text as it was");
        assertEquals("", shading(xml, "The service desk"), "body text keeps the page");
        assertEquals("", shading(xml, "Each regional desk"), "body text keeps the page");
    }

    private static String shading(String xml, String text) {
        Matcher s = Pattern.compile("<w:pPr>.*?<w:shd [^>]*w:fill=\"([0-9A-F]{6})\"", Pattern.DOTALL).matcher(paragraph(xml, text));
        return s.find() ? s.group(1) : "";
    }

    private static String paragraph(String xml, String text) {
        Matcher m = PARAGRAPH.matcher(xml);
        while (m.find()) {
            if (m.group().contains(text)) {
                return m.group();
            }
        }
        throw new AssertionError("no paragraph with " + text);
    }

    private String convert(Path pdf) throws IOException {
        Path docx = dir.resolve(pdf.getFileName().toString().replace(".pdf", ".docx"));
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            return new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static BufferedImage plain(Color c) {
        BufferedImage img = new BufferedImage(600, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.dispose();
        return img;
    }

    private static void text(PDPageContentStream cs, float x, float y, String s) throws IOException {
        cs.beginText();
        cs.setFont(FONT, 10);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    private static void roundRect(PDPageContentStream cs, float x, float y, float w, float h, float r) throws IOException {
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
}
