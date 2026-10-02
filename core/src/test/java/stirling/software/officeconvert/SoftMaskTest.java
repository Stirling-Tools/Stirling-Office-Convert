package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SoftMaskTest {

    private static final String FILL = "0 0 1 rg 50 50 200 200 re f";

    private static final String IMAGE = "q 200 0 0 200 50 50 cm /Im Do Q";

    @TempDir Path dir;

    @Test
    void aFillUnderAnRgbLuminosityMaskIsDrawnHalfSeeThroughInWord() throws Exception {
        assertWord(masked("fill.pdf", COSName.DEVICERGB, FILL));
    }

    @Test
    void anImageUnderAnRgbLuminosityMaskIsDrawnHalfSeeThroughInWord() throws Exception {
        assertWord(masked("image.pdf", COSName.DEVICERGB, IMAGE));
    }

    @Test
    void aFillUnderAnRgbLuminosityMaskIsDrawnHalfSeeThroughInPowerPoint() throws Exception {
        assertSlides(masked("fill.pdf", COSName.DEVICERGB, FILL));
    }

    @Test
    void anImageUnderAGreyLuminosityMaskIsDrawnHalfSeeThroughInPowerPoint() throws Exception {
        assertSlides(masked("image.pdf", COSName.DEVICEGRAY, IMAGE));
    }

    @Test
    void textOverAMaskedFillStaysTextInWord() throws Exception {
        Path pdf = masked("text.pdf", COSName.DEVICERGB,
                FILL + " BT /F1 14 Tf 60 150 Td (Words over the masked panel stay editable) Tj ET");
        Path docx = dir.resolve("text.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            String body = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(body.contains("masked panel"), "the words are kept as text");
        }
        assertWord(pdf);
    }

    private void assertWord(Path pdf) throws IOException {
        Path docx = dir.resolve("out.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        assertBlended(docx, "word/");
    }

    private void assertSlides(Path pdf) throws IOException {
        Path pptx = dir.resolve("out.pptx");
        PdfToPptx.convert(pdf, pptx, PdfToPptx.Options.defaults());
        assertBlended(pptx, "ppt/");
    }

    private static void assertBlended(Path office, String root) throws IOException {
        try (ZipFile zip = new ZipFile(office.toFile())) {
            List<Color> centres = new ArrayList<>();
            StringBuilder xml = new StringBuilder();
            for (ZipEntry e : zip.stream().toList()) {
                if (e.getName().startsWith(root + "media/")) {
                    BufferedImage img = ImageIO.read(zip.getInputStream(e));
                    centres.add(new Color(img.getRGB(img.getWidth() / 2, img.getHeight() / 2), true));
                } else if (e.getName().endsWith(".xml")) {
                    xml.append(new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            assertTrue(centres.stream().anyMatch(SoftMaskTest::halfBlueOverRed), "pictures " + centres);
            assertFalse(xml.toString().contains("<a:srgbClr val=\"0000FF\"/>"), "the blue is not drawn unmasked");
        }
    }

    private static boolean halfBlueOverRed(Color c) {
        return c.getAlpha() == 255 && Math.abs(c.getRed() - 128) <= 24 && c.getGreen() <= 24
                && Math.abs(c.getBlue() - 128) <= 24;
    }

    private Path masked(String name, COSName maskSpace, String content) throws IOException {
        Path pdf = dir.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(300, 300));
            doc.addPage(page);
            PDResources resources = new PDResources();
            page.setResources(resources);
            BufferedImage blue = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    blue.setRGB(x, y, 0x0000FF);
                }
            }
            resources.put(COSName.getPDFName("Im"), LosslessFactory.createFromImage(doc, blue));
            PDFormXObject maskShape = new PDFormXObject(doc);
            maskShape.setBBox(new PDRectangle(300, 300));
            maskShape.setResources(new PDResources());
            COSDictionary group = new COSDictionary();
            group.setItem(COSName.S, COSName.TRANSPARENCY);
            group.setItem(COSName.CS, maskSpace);
            maskShape.getCOSObject().setItem(COSName.GROUP, group);
            try (OutputStream out = maskShape.getContentStream().createOutputStream()) {
                out.write("0.5 g 0 0 300 300 re f".getBytes(StandardCharsets.US_ASCII));
            }
            COSDictionary mask = new COSDictionary();
            mask.setItem(COSName.TYPE, COSName.MASK);
            mask.setItem(COSName.S, COSName.LUMINOSITY);
            mask.setItem(COSName.G, maskShape.getCOSObject());
            PDExtendedGraphicsState state = new PDExtendedGraphicsState();
            state.getCOSObject().setItem(COSName.SMASK, mask);
            resources.put(COSName.getPDFName("GS"), state);
            resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            PDStream stream = new PDStream(doc);
            try (OutputStream out = stream.createOutputStream()) {
                out.write(("1 0 0 rg 0 0 300 300 re f /GS gs " + content).getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(stream);
            doc.save(pdf.toFile());
        }
        return pdf;
    }
}
