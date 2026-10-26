package stirling.software.officeconvert.lineart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquare;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LineArtTest {

    @TempDir
    Path dir;

    @Test
    void scanBecomesOneBitAndKeepsItsPlacement() throws IOException {
        BufferedImage scan = Pictures.scan(400, 300, 5);
        PDDocument doc = Pictures.onePage(scan);
        String content = new String(doc.getPage(0).getContents().readAllBytes());
        LineArt.Result result = LineArt.convert(doc, LineArt.Options.defaults());
        assertEquals(1, result.images());
        assertEquals(1, result.converted());
        assertTrue(result.bytesAfter() < result.bytesBefore() / 4, result.toString());
        try (PDDocument back = Pictures.reload(doc)) {
            PDImageXObject image = Pictures.firstImage(back);
            assertEquals(400, image.getWidth());
            assertEquals(300, image.getHeight());
            assertEquals(1, image.getBitsPerComponent());
            assertEquals(content, new String(back.getPage(0).getContents().readAllBytes()));
            assertTrue(back.getVersion() >= 1.4f);
        }
    }

    @Test
    void sauvolaKeepsTheDrawingUnderUnevenLight() {
        BufferedImage clean = Pictures.drawing(400, 300, new Color(30, 30, 35), Color.WHITE, 5);
        BufferedImage scan = Pictures.scan(400, 300, 5, 0.4);
        long sauvola = wrong(clean, LineArt.binarize(scan, LineArt.Options.defaults()));
        long otsu = wrong(clean, LineArt.binarize(scan, LineArt.Options.defaults().method(LineArt.Method.OTSU)));
        assertTrue(sauvola < 400 * 300 / 30, sauvola + " pixels differ");
        assertTrue(otsu > 4 * sauvola, otsu + " against " + sauvola);
    }

    private static long wrong(BufferedImage clean, Bitmap bitmap) {
        long wrong = 0;
        for (int y = 0; y < bitmap.height(); y++) {
            for (int x = 0; x < bitmap.width(); x++) {
                if (bitmap.ink(x, y) != Pictures.dark(clean.getRGB(x, y))) {
                    wrong++;
                }
            }
        }
        return wrong;
    }

    @Test
    void otsuSplitsTwoTones() {
        BufferedImage picture = Pictures.drawing(200, 100, new Color(90, 90, 90), new Color(170, 170, 170), 9);
        Bitmap bitmap = LineArt.binarize(picture, LineArt.Options.defaults().method(LineArt.Method.OTSU));
        for (int y = 0; y < 100; y++) {
            for (int x = 0; x < 200; x++) {
                int v = picture.getRGB(x, y) & 0xFF;
                if (v <= 95 || v >= 165) {
                    assertEquals(v <= 95, bitmap.ink(x, y), x + "," + y);
                }
            }
        }
    }

    @Test
    void fixedThresholdIsAPercentOfWhite() {
        BufferedImage picture = new BufferedImage(3, 1, BufferedImage.TYPE_INT_RGB);
        picture.setRGB(0, 0, 0x6E6E6E);
        picture.setRGB(1, 0, 0x8C8C8C);
        picture.setRGB(2, 0, 0xAAAAAA);
        Bitmap at60 = LineArt.binarize(picture, LineArt.Options.defaults().method(LineArt.Method.FIXED).threshold(60));
        assertTrue(at60.ink(0, 0));
        assertTrue(at60.ink(1, 0));
        assertFalse(at60.ink(2, 0));
        Bitmap at50 = LineArt.binarize(picture, LineArt.Options.defaults().method(LineArt.Method.FIXED));
        assertTrue(at50.ink(0, 0));
        assertFalse(at50.ink(1, 0));
    }

    @Test
    void softMaskInterpolationAndOptionalContentStay() throws IOException {
        PDDocument doc = Pictures.onePage(Pictures.drawing(120, 80, Color.BLACK, Color.WHITE, 2));
        COSStream image = Pictures.firstImage(doc).getCOSObject();
        BufferedImage alpha = new BufferedImage(120, 80, BufferedImage.TYPE_BYTE_GRAY);
        PDImageXObject soft = LosslessFactory.createFromImage(doc, alpha);
        image.setItem(COSName.SMASK, soft);
        image.setBoolean(COSName.INTERPOLATE, true);
        COSDictionary group = new COSDictionary();
        image.setItem(COSName.OC, group);
        LineArt.Result result = LineArt.convert(doc, LineArt.Options.defaults());
        assertEquals(1, result.converted());
        assertSame(soft.getCOSObject(), image.getDictionaryObject(COSName.SMASK));
        assertTrue(image.getBoolean(COSName.INTERPOLATE, false));
        assertSame(group, image.getDictionaryObject(COSName.OC));
        assertEquals(1, image.getInt(COSName.BITS_PER_COMPONENT));
        doc.close();
    }

    @Test
    void colourKeyMaskBecomesAStencilThatStillHidesTheKeyedPixels() throws IOException {
        BufferedImage picture = new BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 60; x++) {
                picture.setRGB(x, y, x < 20 ? 0x000000 : x < 40 ? 0x00FF00 : 0xFFFFFF);
            }
        }
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(new PDRectangle(60, 40));
        doc.addPage(page);
        PDImageXObject image = LosslessFactory.createFromImage(doc, picture);
        COSArray key = new COSArray();
        for (int v : new int[] {0, 0, 255, 255, 0, 0}) {
            key.add(COSInteger.get(v));
        }
        image.getCOSObject().setItem(COSName.MASK, key);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(Color.RED);
            cs.addRect(0, 0, 60, 40);
            cs.fill();
            cs.drawImage(image, 0, 0, 60, 40);
        }
        LineArt.convert(doc, LineArt.Options.defaults().method(LineArt.Method.FIXED));
        try (PDDocument back = Pictures.reload(doc)) {
            COSStream converted = Pictures.firstImage(back).getCOSObject();
            COSStream mask = assertInstanceOf(COSStream.class, converted.getDictionaryObject(COSName.MASK));
            assertTrue(mask.getBoolean(COSName.IMAGE_MASK, false));
            BufferedImage page0 = new PDFRenderer(back).renderImage(0, 1);
            assertEquals(0x000000, page0.getRGB(10, 20) & 0xFFFFFF);
            assertEquals(0xFF0000, page0.getRGB(30, 20) & 0xFFFFFF);
            assertEquals(0xFFFFFF, page0.getRGB(50, 20) & 0xFFFFFF);
        }
    }

    @Test
    void imagesInFormsPatternsAndAppearancesAreFoundButMasksAndStencilsAreNot() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDImageXObject inner = LosslessFactory.createFromImage(doc, Pictures.drawing(50, 50, Color.BLACK, Color.WHITE, 1));
        PDFormXObject outer = form(doc, form(doc, inner));
        PDImageXObject direct = LosslessFactory.createFromImage(doc, Pictures.drawing(50, 50, Color.BLACK, Color.WHITE, 2));
        PDImageXObject mask = LosslessFactory.createFromImage(doc, new BufferedImage(50, 50, BufferedImage.TYPE_BYTE_GRAY));
        direct.getCOSObject().setItem(COSName.SMASK, mask);
        COSStream stencil = doc.getDocument().createCOSStream();
        stencil.setItem(COSName.SUBTYPE, COSName.IMAGE);
        stencil.setBoolean(COSName.IMAGE_MASK, true);
        stencil.setInt(COSName.WIDTH, 8);
        stencil.setInt(COSName.HEIGHT, 1);
        stencil.setInt(COSName.BITS_PER_COMPONENT, 1);
        try (var os = stencil.createOutputStream()) {
            os.write(0x0F);
        }
        PDResources resources = new PDResources();
        resources.put(COSName.getPDFName("Outer"), outer);
        resources.put(COSName.getPDFName("Direct"), direct);
        resources.getCOSObject().getCOSDictionary(COSName.XOBJECT).setItem(COSName.getPDFName("Stencil"), stencil);
        page.setResources(resources);
        PDImageXObject annotated = LosslessFactory.createFromImage(doc, Pictures.drawing(30, 30, Color.BLACK, Color.WHITE, 4));
        PDAnnotationSquare square = new PDAnnotationSquare();
        square.setRectangle(new PDRectangle(10, 10, 30, 30));
        PDAppearanceStream appearance = new PDAppearanceStream(form(doc, annotated).getCOSObject());
        PDAppearanceDictionary dictionary = new PDAppearanceDictionary();
        dictionary.setNormalAppearance(appearance);
        square.setAppearance(dictionary);
        page.getAnnotations().add(square);

        LineArt.Result result = LineArt.convert(doc, LineArt.Options.defaults());

        assertEquals(4, result.images(), result.toString());
        assertEquals(3, result.converted(), result.toString());
        for (PDImageXObject converted : List.of(inner, direct, annotated)) {
            assertEquals(1, converted.getCOSObject().getInt(COSName.BITS_PER_COMPONENT));
        }
        assertEquals(8, mask.getCOSObject().getInt(COSName.BITS_PER_COMPONENT));
        assertNull(stencil.getDictionaryObject(COSName.FILTER));
        assertTrue(stencil.getBoolean(COSName.IMAGE_MASK, false));
        doc.close();
    }

    @Test
    void inkColourIsKeptAsATwoColourPalette() throws IOException {
        Color blue = new Color(20, 40, 160);
        PDDocument doc = Pictures.onePage(Pictures.drawing(200, 150, blue, Color.WHITE, 8));
        LineArt.convert(doc, LineArt.Options.defaults().inkColour(true));
        try (PDDocument back = Pictures.reload(doc)) {
            PDImageXObject image = Pictures.firstImage(back);
            COSArray indexed = assertInstanceOf(COSArray.class, image.getCOSObject().getDictionaryObject(COSName.COLORSPACE));
            assertEquals(COSName.INDEXED, indexed.getObject(0));
            assertEquals(COSName.DEVICERGB, indexed.getObject(1));
            byte[] palette = ((COSString) indexed.getObject(3)).getBytes();
            int ink = palette[0] == (byte) 0xFF && palette[1] == (byte) 0xFF ? 3 : 0;
            assertTrue(Math.abs((palette[ink + 2] & 0xFF) - 160) < 30, "blue " + (palette[ink + 2] & 0xFF));
            assertTrue((palette[ink] & 0xFF) < 60);
            BufferedImage decoded = image.getImage();
            boolean sawBlue = false;
            for (int y = 0; y < 150 && !sawBlue; y++) {
                for (int x = 0; x < 200; x++) {
                    int rgb = decoded.getRGB(x, y);
                    sawBlue |= (rgb & 0xFF) > 120 && ((rgb >>> 16) & 0xFF) < 60;
                }
            }
            assertTrue(sawBlue);
        }
    }

    @Test
    void neutralInkStaysBlackWithInkColourOn() throws IOException {
        PDDocument doc = Pictures.onePage(Pictures.scan(200, 150, 3));
        LineArt.convert(doc, LineArt.Options.defaults().inkColour(true));
        COSArray indexed = (COSArray) Pictures.firstImage(doc).getCOSObject().getDictionaryObject(COSName.COLORSPACE);
        byte[] palette = ((COSString) indexed.getObject(3)).getBytes();
        int black = 0;
        for (byte b : palette) {
            black += b == 0 ? 1 : 0;
        }
        assertEquals(3, black);
        doc.close();
    }

    @Test
    void spotColourInkStaysOnItsSeparation() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        COSArray separation = new COSArray();
        separation.add(COSName.SEPARATION);
        separation.add(COSName.getPDFName("Spot"));
        separation.add(COSName.DEVICECMYK);
        COSDictionary tint = new COSDictionary();
        tint.setInt(COSName.FUNCTION_TYPE, 2);
        COSArray domain = new COSArray();
        domain.add(COSInteger.ZERO);
        domain.add(COSInteger.ONE);
        tint.setItem(COSName.DOMAIN, domain);
        COSArray c1 = new COSArray();
        for (float v : new float[] {1, 0.5f, 0, 0}) {
            c1.add(new COSFloat(v));
        }
        tint.setItem(COSName.C1, c1);
        COSArray c0 = new COSArray();
        for (int i = 0; i < 4; i++) {
            c0.add(COSInteger.ZERO);
        }
        tint.setItem(COSName.C0, c0);
        tint.setInt(COSName.N, 1);
        separation.add(tint);
        BufferedImage drawing = Pictures.drawing(100, 80, Color.BLACK, Color.WHITE, 6);
        byte[] samples = new byte[100 * 80];
        for (int y = 0; y < 80; y++) {
            for (int x = 0; x < 100; x++) {
                samples[y * 100 + x] = (byte) (255 - (drawing.getRGB(x, y) & 0xFF));
            }
        }
        COSStream stream = doc.getDocument().createCOSStream();
        stream.setItem(COSName.TYPE, COSName.XOBJECT);
        stream.setItem(COSName.SUBTYPE, COSName.IMAGE);
        stream.setInt(COSName.WIDTH, 100);
        stream.setInt(COSName.HEIGHT, 80);
        stream.setInt(COSName.BITS_PER_COMPONENT, 8);
        stream.setItem(COSName.COLORSPACE, separation);
        try (var os = stream.createOutputStream(COSName.FLATE_DECODE)) {
            os.write(samples);
        }
        PDImageXObject image = new PDImageXObject(new PDStream(stream), null);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(image, 50, 400, 100, 80);
        }
        LineArt.Result result = LineArt.convert(doc, LineArt.Options.defaults().inkColour(true));
        assertEquals(1, result.converted(), result.toString());
        COSArray indexed = assertInstanceOf(COSArray.class, stream.getDictionaryObject(COSName.COLORSPACE));
        assertEquals(COSName.INDEXED, indexed.getObject(0));
        assertSame(separation, indexed.getObject(1));
        byte[] palette = ((COSString) indexed.getObject(3)).getBytes();
        assertEquals(2, palette.length);
        assertEquals(255, (palette[0] & 0xFF) + (palette[1] & 0xFF));
        doc.close();
    }

    @Test
    void alreadyBilevelImagesAreOnlyReplacedWhenSmaller() throws IOException {
        Bitmap bitmap = LineArt.binarize(Pictures.drawing(300, 200, Color.BLACK, Color.WHITE, 12),
                LineArt.Options.defaults().method(LineArt.Method.FIXED));
        PDDocument doc = Pictures.onePage(bitmap.toImage());
        COSStream image = Pictures.firstImage(doc).getCOSObject();
        assertEquals(1, image.getInt(COSName.BITS_PER_COMPONENT));
        LineArt.Result once = LineArt.convert(doc, LineArt.Options.defaults().codec(LineArt.Codec.JBIG2));
        assertEquals(1, once.converted());
        LineArt.Result twice = LineArt.convert(doc, LineArt.Options.defaults().codec(LineArt.Codec.FLATE));
        assertEquals(0, twice.converted());
        assertEquals(COSName.JBIG2_DECODE, image.getCOSName(COSName.FILTER));
        doc.close();
    }

    @Test
    void hugeImagesAreLeftWithAWarning() throws IOException {
        PDDocument doc = Pictures.onePage(Pictures.drawing(100, 100, Color.BLACK, Color.WHITE, 1));
        LineArt.Result result = LineArt.convert(doc, LineArt.Options.defaults().maxPixels(9_999));
        assertEquals(0, result.converted());
        assertEquals(1, result.warnings().size());
        assertNull(Pictures.firstImage(doc).getCOSObject().getDictionaryObject(COSName.DECODE));
        doc.close();
    }

    @Test
    void convertsAFileEndToEnd() throws IOException {
        Path in = dir.resolve("in.pdf");
        try (PDDocument doc = Pictures.onePage(Pictures.scan(300, 200, 2))) {
            doc.save(in.toFile());
        }
        Path out = dir.resolve("out.pdf");
        LineArt.Result result = LineArt.convert(in, out, LineArt.Options.imageMagickEdges(2));
        assertEquals(1, result.converted());
        assertTrue(Files.size(out) < Files.size(in));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(1, Pictures.firstImage(doc).getBitsPerComponent());
        }
        try (var files = Files.list(dir)) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void optionsRejectNonsense() {
        LineArt.Options o = LineArt.Options.defaults();
        assertThrows(IllegalArgumentException.class, () -> o.threshold(101));
        assertThrows(IllegalArgumentException.class, () -> o.edgeLevel(4));
        assertThrows(IllegalArgumentException.class, () -> o.window(2));
        assertThrows(IllegalArgumentException.class, () -> o.sensitivity(-0.1));
        assertThrows(IllegalArgumentException.class, () -> o.maxPixels(0));
        assertEquals(LineArt.Method.EDGES, LineArt.Options.imageMagickEdges(3).method());
        assertEquals(50, LineArt.Options.imageMagickEdges(3).threshold());
    }

    private static PDFormXObject form(PDDocument doc, PDXObject child) throws IOException {
        PDFormXObject form = new PDFormXObject(doc);
        form.setBBox(new PDRectangle(0, 0, 100, 100));
        PDResources resources = new PDResources();
        resources.put(COSName.getPDFName("C"), child);
        form.setResources(resources);
        try (var os = form.getCOSObject().createOutputStream()) {
            os.write("q 50 0 0 50 0 0 cm /C Do Q".getBytes());
        }
        return form;
    }
}
