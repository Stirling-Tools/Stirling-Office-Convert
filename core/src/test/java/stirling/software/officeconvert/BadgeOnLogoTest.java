package stirling.software.officeconvert;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BadgeOnLogoTest {

    @TempDir Path dir;

    @Test
    void logoWithABadgeStillWraps() throws Exception {
        Path pdf = dir.resolve("letterhead.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font plain = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image(doc, 120, 150, new Color(20, 60, 160)), 65, 724, 58, 73);
                cs.drawImage(image(doc, 40, 40, new Color(230, 180, 20)), 99, 744, 18, 18);
                text(cs, bold, 16, 150, 770, "Sportverein 1923 Musterstadt e.V.");
                text(cs, bold, 13, 150, 745, "Hygiene plan for the annual meeting");
                for (int i = 0; i < 12; i++) {
                    text(cs, plain, 10, 72, 690 - i * 14, "Members are asked to keep their distance and to follow the signs in the hall.");
                }
            }
            doc.save(pdf.toFile());
        }
        Path docx = dir.resolve("letterhead.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        String xml;
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            xml = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile("<wp:anchor [^>]*>(?:(?!</wp:anchor>).)*?<wp:extent cx=\"" + 58 * 12700
                + "\"(?:(?!</wp:anchor>).)*?<wp:(wrap\\w+)").matcher(xml);
        assertTrue(m.find(), "the logo is placed on the page");
        assertTrue(m.group(1).equals("wrapSquare"), "text wraps round the logo: " + m.group(1));
    }

    private static PDImageXObject image(PDDocument doc, int w, int h, Color c) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.WHITE);
        g.fillOval(w / 4, h / 4, w / 2, h / 2);
        g.dispose();
        return LosslessFactory.createFromImage(doc, img);
    }

    private static void text(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String s) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }
}
