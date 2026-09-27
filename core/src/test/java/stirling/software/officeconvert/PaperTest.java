package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType2;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDShadingPattern;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperTest {

    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    @TempDir Path dir;

    @Test
    void photoOverAPageGradientStaysItsOwnPicture() throws Exception {
        String xml = convert(makePdf(true, false));
        assertEquals(2, count(xml, "<pic:pic>"), "pictures in the document");
        assertTrue(xml.contains("cx=\"" + 200 * 12700 + "\""), "the photo keeps its own size");
    }

    @Test
    void tableOnAPatternedBandStaysATable() throws Exception {
        String xml = convert(makePdf(false, true));
        assertTrue(xml.contains("<w:tbl>"), "the ruled grid on the band is a table");
        assertTrue(xml.contains(">Region<") && xml.contains(">North<"), "its cells are text");
    }

    @Test
    void patternOfAPictureIsPainted() throws Exception {
        Path pdf = dir.resolve("tiled.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(720, 540));
            page.setResources(new org.apache.pdfbox.pdmodel.PDResources());
            doc.addPage(page);
            BufferedImage img = new BufferedImage(120, 120, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(30, 90, 160));
            g.fillRect(0, 0, 120, 120);
            g.dispose();
            PDImageXObject tileImage = LosslessFactory.createFromImage(doc, img);
            org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern tile = new org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern();
            tile.setPaintType(org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern.PAINT_COLORED);
            tile.setTilingType(org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern.TILING_CONSTANT_SPACING);
            tile.setBBox(new PDRectangle(0, 0, 120, 120));
            tile.setXStep(120);
            tile.setYStep(120);
            org.apache.pdfbox.pdmodel.PDResources tileResources = new org.apache.pdfbox.pdmodel.PDResources();
            COSName imageName = tileResources.add(tileImage);
            tile.setResources(tileResources);
            try (java.io.OutputStream out = tile.getContentStream().createOutputStream()) {
                out.write(("q 120 0 0 120 0 0 cm /" + imageName.getName() + " Do Q").getBytes(StandardCharsets.US_ASCII));
            }
            COSName name = page.getResources().add(tile);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(new PDColor(name, new PDPattern(null)));
                cs.addRect(0, 0, 720, 540);
                cs.fill();
                text(cs, 72, 300, 32, "Annual review");
            }
            doc.save(pdf.toFile());
        }
        Path docx = dir.resolve("tiled.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            java.util.zip.ZipEntry paper = zip.stream().filter(e -> e.getName().startsWith("word/media/")).findFirst().orElseThrow();
            BufferedImage shown = javax.imageio.ImageIO.read(zip.getInputStream(paper));
            Color centre = new Color(shown.getRGB(shown.getWidth() / 2, shown.getHeight() / 4));
            assertTrue(centre.getBlue() > 120 && centre.getRed() < 90, "the paper shows the tile's blue: " + centre);
        }
    }

    private String convert(Path pdf) throws IOException {
        Path docx = dir.resolve(pdf.getFileName().toString().replace(".pdf", ".docx"));
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            return new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int count(String s, String what) {
        Matcher m = Pattern.compile(Pattern.quote(what)).matcher(s);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private Path makePdf(boolean photo, boolean band) throws IOException {
        Path pdf = dir.resolve(photo ? "photo.pdf" : "band.pdf");
        PDRectangle size = new PDRectangle(792, 612);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(size);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                if (photo) {
                    cs.shadingFill(gradient(0, 0, 0, 612, new float[] {0.85f, 0.9f, 1f}, new float[] {0.4f, 0.5f, 0.8f}));
                    text(cs, 72, 540, 28, "Quarterly review");
                    cs.drawImage(photo(doc), 296, 200, 200, 150);
                    text(cs, 72, 150, 14, "The new office opened in March with room for forty people.");
                }
                if (band) {
                    PDShadingPattern pattern = new PDShadingPattern();
                    pattern.setShading(gradient(0, 150, 0, 450, new float[] {0.95f, 0.95f, 0.9f}, new float[] {0.8f, 0.8f, 0.7f}));
                    COSName name = page.getResources().add(pattern);
                    cs.setNonStrokingColor(new PDColor(name, new PDPattern(null, PDDeviceRGB.INSTANCE)));
                    cs.addRect(0, 150, 792, 300);
                    cs.fill();
                    text(cs, 72, 520, 28, "Sales by region");
                    String[][] rows = {{"Region", "Q1", "Q2"}, {"North", "120", "135"}, {"South", "98", "110"}, {"West", "143", "150"}};
                    cs.setStrokingColor(0.3f);
                    cs.setLineWidth(1f);
                    for (int r = 0; r <= rows.length; r++) {
                        cs.moveTo(150, 400 - r * 40);
                        cs.lineTo(642, 400 - r * 40);
                    }
                    for (int c = 0; c <= 3; c++) {
                        float x = c == 3 ? 642 : 150 + c * 164;
                        cs.moveTo(x, 400);
                        cs.lineTo(x, 400 - rows.length * 40);
                    }
                    cs.stroke();
                    for (int r = 0; r < rows.length; r++) {
                        for (int c = 0; c < 3; c++) {
                            text(cs, 160 + c * 164, 400 - r * 40 - 25, 14, rows[r][c]);
                        }
                    }
                }
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static PDShading gradient(float x0, float y0, float x1, float y1, float[] from, float[] to) {
        COSDictionary fn = new COSDictionary();
        fn.setInt(COSName.FUNCTION_TYPE, 2);
        COSArray domain = new COSArray();
        domain.add(COSInteger.get(0));
        domain.add(COSInteger.get(1));
        fn.setItem(COSName.DOMAIN, domain);
        fn.setItem(COSName.C0, floats(from));
        fn.setItem(COSName.C1, floats(to));
        fn.setInt(COSName.N, 1);
        PDShadingType2 shading = new PDShadingType2(new COSDictionary());
        shading.setShadingType(PDShading.SHADING_TYPE2);
        shading.setColorSpace(PDDeviceRGB.INSTANCE);
        shading.setFunction(new PDFunctionType2(fn));
        shading.setCoords(floats(new float[] {x0, y0, x1, y1}));
        COSArray extend = new COSArray();
        extend.add(org.apache.pdfbox.cos.COSBoolean.TRUE);
        extend.add(org.apache.pdfbox.cos.COSBoolean.TRUE);
        shading.setExtend(extend);
        return shading;
    }

    private static COSArray floats(float[] v) {
        COSArray a = new COSArray();
        for (float f : v) {
            a.add(new COSFloat(f));
        }
        return a;
    }

    private static PDImageXObject photo(PDDocument doc) throws IOException {
        BufferedImage img = new BufferedImage(200, 150, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        for (int y = 0; y < 150; y++) {
            for (int x = 0; x < 200; x++) {
                img.setRGB(x, y, new Color((x * 7 + y * 3) % 256, (x * 2 + y * 5) % 256, (x + y * 9) % 256).getRGB());
            }
        }
        g.dispose();
        return LosslessFactory.createFromImage(doc, img);
    }

    private static void text(PDPageContentStream cs, float x, float y, float size, String s) throws IOException {
        cs.beginText();
        cs.setFont(FONT, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }
}
