package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShapedClipTest {

    @TempDir Path dir;

    @Test
    void roundPhotoStaysRound() throws Exception {
        Path pdf = dir.resolve("round.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            BufferedImage photo = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 100; y++) {
                for (int x = 0; x < 100; x++) {
                    photo.setRGB(x, y, new Color(200, 40, 40).getRGB());
                }
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.saveGraphicsState();
                circle(cs, 122, 700, 50);
                cs.clip();
                cs.drawImage(LosslessFactory.createFromImage(doc, photo), 72, 650, 100, 100);
                cs.restoreGraphicsState();
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 18);
                cs.newLineAtOffset(200, 700);
                cs.showText("Alex Example");
                cs.endText();
            }
            doc.save(pdf.toFile());
        }
        Path docx = dir.resolve("round.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            ZipEntry media = zip.stream().filter(e -> e.getName().startsWith("word/media/")).findFirst().orElseThrow();
            BufferedImage shown = ImageIO.read(zip.getInputStream(media));
            Color corner = new Color(shown.getRGB(1, 1));
            Color middle = new Color(shown.getRGB(shown.getWidth() / 2, shown.getHeight() / 2));
            assertTrue(middle.getRed() > 150 && middle.getGreen() < 90, "the photo shows in the middle: " + middle);
            assertTrue(corner.getGreen() > 200, "the corner is the page, cut off by the circle: " + corner);
        }
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
