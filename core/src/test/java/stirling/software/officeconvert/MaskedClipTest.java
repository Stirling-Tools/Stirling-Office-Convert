package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaskedClipTest {

    @TempDir Path dir;

    @Test
    void maskedPhotoIsTakenFromItsImage() throws Exception {
        Path pdf = dir.resolve("masked.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDResources resources = new PDResources();
            page.setResources(resources);
            BufferedImage photo = new BufferedImage(200, 150, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 150; y++) {
                for (int x = 0; x < 200; x++) {
                    photo.setRGB(x, y, new Color(200, 40 + x / 4, 40).getRGB());
                }
            }
            PDImageXObject image = LosslessFactory.createFromImage(doc, photo);
            PDFormXObject group = form(doc, "q 200 0 0 150 0 0 cm /Im Do Q", 200, 150);
            group.getResources().put(COSName.getPDFName("Im"), image);
            PDFormXObject maskShape = form(doc, "1 g 0 0 200 150 re f", 200, 150);
            maskShape.getCOSObject().setItem(COSName.GROUP, transparency(COSName.DEVICEGRAY));
            COSDictionary mask = new COSDictionary();
            mask.setItem(COSName.TYPE, COSName.MASK);
            mask.setItem(COSName.S, COSName.LUMINOSITY);
            mask.setItem(COSName.G, maskShape.getCOSObject());
            PDExtendedGraphicsState masked = new PDExtendedGraphicsState();
            masked.getCOSObject().setItem(COSName.SMASK, mask);
            resources.put(COSName.getPDFName("Fm"), group);
            resources.put(COSName.getPDFName("GS"), masked);
            resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            String chevron = "0 0 m 0 150 l 130 150 l 200 75 l 130 0 l h";
            String content = "q 1 0 0 1 72 500 cm " + chevron + " W n /GS gs /Fm Do Q "
                    + "BT /F1 12 Tf 300 600 Td (The media, public agencies and the private sector all publish.) Tj ET "
                    + "BT /F1 12 Tf 300 584 Td (This intelligence is collected and analysed in a timely manner.) Tj ET";
            PDStream stream = new PDStream(doc);
            try (OutputStream out = stream.createOutputStream()) {
                out.write(content.getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(stream);
            doc.save(pdf.toFile());
        }
        Path docx = dir.resolve("masked.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            ZipEntry media = zip.stream().filter(e -> e.getName().startsWith("word/media/")).findFirst().orElseThrow();
            BufferedImage shown = ImageIO.read(zip.getInputStream(media));
            assertEquals(200, shown.getWidth(), "the photo's own pixels, not a render of the page");
            Color corner = new Color(shown.getRGB(shown.getWidth() - 2, 1));
            assertTrue(corner.getRed() > 150 && corner.getBlue() < 90, "the photo, not the page around its clip: " + corner);
        }
    }

    private static PDFormXObject form(PDDocument doc, String content, float w, float h) throws IOException {
        PDFormXObject f = new PDFormXObject(doc);
        f.setBBox(new PDRectangle(w, h));
        f.setResources(new PDResources());
        f.getCOSObject().setItem(COSName.GROUP, transparency(null));
        try (OutputStream out = f.getContentStream().createOutputStream()) {
            out.write(content.getBytes(StandardCharsets.US_ASCII));
        }
        return f;
    }

    private static COSDictionary transparency(COSName colourSpace) {
        COSDictionary g = new COSDictionary();
        g.setItem(COSName.TYPE, COSName.GROUP);
        g.setItem(COSName.S, COSName.TRANSPARENCY);
        if (colourSpace != null) {
            g.setItem(COSName.CS, colourSpace);
        }
        return g;
    }
}
