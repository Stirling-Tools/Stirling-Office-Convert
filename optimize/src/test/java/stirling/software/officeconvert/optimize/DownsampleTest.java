package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownsampleTest {

    private static final PdfOptimizer.Options AT_150_DPI = PdfOptimizer.Options.defaults()
            .images(new PdfOptimizer.Images(150, 0.7f));

    @TempDir
    Path dir;

    @Test
    void photoDrawnSmallIsResampledToTheTargetAsJpegInSrgb() throws Exception {
        Path in = pdf(doc -> draw(doc, LosslessFactory.createFromImage(doc, photo(1200, 1200)), 144));

        COSStream image = onlyImage(optimize(in, AT_150_DPI));

        assertTrue(Math.abs(image.getInt(COSName.WIDTH) - 300) <= 5, "width " + image.getInt(COSName.WIDTH));
        assertTrue(filters(image).contains(COSName.DCT_DECODE), "filters " + filters(image));
        COSArray colorSpace = assertInstanceOf(COSArray.class, image.getDictionaryObject(COSName.COLORSPACE));
        assertEquals(COSName.ICCBASED, colorSpace.getObject(0));
        assertEquals(3, ((COSStream) colorSpace.getObject(1)).getInt(COSName.N));
    }

    @Test
    void aGrayPhotoIsReencodedAsJpeg() throws Exception {
        Path in = pdf(doc -> draw(doc, LosslessFactory.createFromImage(doc, grayPhoto(600, 600)), 288));

        COSStream image = onlyImage(optimize(in, AT_150_DPI));

        assertEquals(600, image.getInt(COSName.WIDTH));
        assertTrue(filters(image).contains(COSName.DCT_DECODE), "filters " + filters(image));
        assertEquals(COSName.DEVICEGRAY, image.getDictionaryObject(COSName.COLORSPACE));
    }

    @Test
    void hardEdgedGraphicsStayLosslessWhenResampled() throws Exception {
        Path in = pdf(doc -> draw(doc, LosslessFactory.createFromImage(doc, stripes(1200, 1200)), 144));

        COSStream image = onlyImage(optimize(in, AT_150_DPI));

        assertTrue(Math.abs(image.getInt(COSName.WIDTH) - 300) <= 5, "width " + image.getInt(COSName.WIDTH));
        assertEquals(List.of(COSName.FLATE_DECODE), filters(image));
    }

    @Test
    void aPhotoWithHardEdgesStaysJpegWhenALowerResolutionResamplesIt() throws Exception {
        Path in = pdf(doc -> draw(doc, LosslessFactory.createFromImage(doc, photoWithRules(1200, 1200)), 560));
        PdfOptimizer.Options at72Dpi = PdfOptimizer.Options.defaults().images(new PdfOptimizer.Images(72, 0.3f));

        COSStream at150 = onlyImage(optimize(in, AT_150_DPI));
        COSStream at72 = onlyImage(optimize(in, at72Dpi));

        assertEquals(1200, at150.getInt(COSName.WIDTH));
        assertTrue(Math.abs(at72.getInt(COSName.WIDTH) - 560) <= 5, "width " + at72.getInt(COSName.WIDTH));
        assertTrue(filters(at150).contains(COSName.DCT_DECODE), "filters " + filters(at150));
        assertTrue(filters(at72).contains(COSName.DCT_DECODE), "filters " + filters(at72));
        assertTrue(at72.getLength() < at150.getLength(), at72.getLength() + " >= " + at150.getLength());
    }

    @Test
    void aSavingSmallerThanTheColourProfileItAddsIsNotTaken() throws Exception {
        Path in = pdf(doc -> draw(doc, LosslessFactory.createFromImage(doc, checks(240, 240)), 144));
        long before = java.nio.file.Files.size(optimize(in, PdfOptimizer.Options.defaults()));

        Path out = optimize(in, PdfOptimizer.Options.defaults().images(new PdfOptimizer.Images(72, 0.3f)));

        assertEquals(240, onlyImage(out).getInt(COSName.WIDTH));
        assertTrue(java.nio.file.Files.size(out) <= before, java.nio.file.Files.size(out) + " > " + before);
    }

    @Test
    void aSmallSavingIsTakenOnceAnotherImageAddsTheColourProfile() throws Exception {
        Path in = pdf(doc -> {
            draw(doc, LosslessFactory.createFromImage(doc, checks(240, 240)), 144);
            draw(doc, LosslessFactory.createFromImage(doc, photo(1200, 1200)), 144);
        });

        Path out = optimize(in, PdfOptimizer.Options.defaults().images(new PdfOptimizer.Images(72, 0.3f)));

        assertTrue(Math.abs(onlyImage(out).getInt(COSName.WIDTH) - 144) <= 2, "width " + onlyImage(out).getInt(COSName.WIDTH));
    }

    @Test
    void anImageDrawnTwiceIsSizedForItsLargestPlacement() throws Exception {
        Path in = pdf(doc -> {
            PDImageXObject image = LosslessFactory.createFromImage(doc, noise(1200, 1200));
            draw(doc, image, 72);
            draw(doc, image, 288);
        });

        COSStream image = onlyImage(optimize(in, AT_150_DPI));

        assertTrue(Math.abs(image.getInt(COSName.WIDTH) - 600) <= 5, "width " + image.getInt(COSName.WIDTH));
    }

    @Test
    void imageWithAMattedSoftMaskKeepsItsDimensions() throws Exception {
        Path in = pdf(doc -> {
            PDImageXObject image = LosslessFactory.createFromImage(doc, noise(1200, 1200));
            PDImageXObject mask = LosslessFactory.createFromImage(doc, gradient(1200, 1200));
            COSArray matte = new COSArray();
            matte.setFloatArray(new float[] {0, 0, 0});
            mask.getCOSObject().setItem(COSName.getPDFName("Matte"), matte);
            image.getCOSObject().setItem(COSName.SMASK, mask.getCOSObject());
            draw(doc, image, 144);
        });

        COSStream image = onlyImage(optimize(in, AT_150_DPI));

        assertEquals(1200, image.getInt(COSName.WIDTH));
    }

    @Test
    void flatColourImageAtTheTargetResolutionIsLeftAlone() throws Exception {
        Path in = pdf(doc -> draw(doc, LosslessFactory.createFromImage(doc, checks(300, 300)), 144));

        COSStream image = onlyImage(optimize(in, AT_150_DPI));

        assertEquals(300, image.getInt(COSName.WIDTH));
        assertEquals(COSName.FLATE_DECODE, image.getDictionaryObject(COSName.FILTER));
    }

    @Test
    void grayscaleConvertsColourImagesToDeviceGray() throws Exception {
        Path in = pdf(doc -> draw(doc, LosslessFactory.createFromImage(doc, noise(1200, 1200)), 144));

        COSStream image = onlyImage(optimize(in, PdfOptimizer.Options.defaults()
                .images(new PdfOptimizer.Images(150, 0.7f)).grayscale(true)));

        assertEquals(COSName.DEVICEGRAY, image.getDictionaryObject(COSName.COLORSPACE));
    }

    @Test
    void withoutImageSettingsImagesAreKeptAsTheyAre() throws Exception {
        Path in = pdf(doc -> draw(doc, LosslessFactory.createFromImage(doc, noise(1200, 1200)), 144));

        COSStream image = onlyImage(optimize(in, PdfOptimizer.Options.defaults()));

        assertEquals(1200, image.getInt(COSName.WIDTH));
    }

    @Test
    void aPageWhoseContentDecodesPastTheLimitIsNotMeasured() throws Exception {
        Path in = pdf(doc -> {
            PDImageXObject image = LosslessFactory.createFromImage(doc, noise(1200, 1200));
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            page.setResources(new PDResources());
            COSName name = page.getResources().add(image);
            COSStream content = doc.getDocument().createCOSStream();
            try (OutputStream out = content.createOutputStream(COSName.FLATE_DECODE)) {
                out.write(("q 144 0 0 144 50 50 cm /" + name.getName() + " Do Q").getBytes(StandardCharsets.US_ASCII));
                byte[] spaces = new byte[1 << 20];
                Arrays.fill(spaces, (byte) ' ');
                for (long written = 0; written <= Recoder.MAX_DECODED_BYTES; written += spaces.length) {
                    out.write(spaces);
                }
            }
            page.getCOSObject().setItem(COSName.CONTENTS, content);
        });

        COSStream image = onlyImage(optimize(in, AT_150_DPI));

        assertEquals(1200, image.getInt(COSName.WIDTH));
    }

    @Test
    void anImageTooLargeToDecodeWholeIsStillResampledWhenDrawnSmall() throws Exception {
        int side = (int) Math.sqrt(Recoder.MAX_DECODED_BYTES / 4.0) + 64;
        Path in = pdf(doc -> {
            BufferedImage gray = new BufferedImage(side, side, BufferedImage.TYPE_BYTE_GRAY);
            for (int y = 0; y < side; y++) {
                for (int x = 0; x < side; x++) {
                    gray.getRaster().setSample(x, y, 0, (x / 7 + y / 5) & 0xFF);
                }
            }
            draw(doc, LosslessFactory.createFromImage(doc, gray), 72);
        });

        COSStream image = onlyImage(optimize(in, AT_150_DPI));

        assertTrue(image.getInt(COSName.WIDTH) <= 160, "width " + image.getInt(COSName.WIDTH));
    }

    @Test
    void aPaletteImageDrawnSmallIsResampledAndKeepsItsColours() throws Exception {
        int[] palette = {0xFFFFFF, 0x1F4E9A, 0xC03020, 0x2A9A4E};
        Path in = pdf(doc -> draw(doc, indexed(doc, 1200, 1200, palette), 144));

        BufferedImage decoded = decodedOnlyImage(optimize(in, AT_150_DPI));

        assertTrue(Math.abs(decoded.getWidth() - 300) <= 5, "width " + decoded.getWidth());
        for (int block = 0; block < 4; block++) {
            int x = (block * 300 + 150) * decoded.getWidth() / 1200;
            assertEquals(palette[block], decoded.getRGB(x, decoded.getHeight() / 2) & 0xFFFFFF, "block " + block);
        }
    }

    @Test
    void aSoftMaskIsResampledWithItsImage() throws Exception {
        Path in = pdf(doc -> {
            PDImageXObject image = LosslessFactory.createFromImage(doc, noise(1200, 1200));
            PDImageXObject mask = LosslessFactory.createFromImage(doc, gradient(1200, 1200));
            image.getCOSObject().setItem(COSName.SMASK, mask.getCOSObject());
            draw(doc, image, 144);
        });

        try (PDDocument doc = Loader.loadPDF(optimize(in, AT_150_DPI).toFile())) {
            PDResources resources = doc.getPage(0).getResources();
            COSStream image = ((PDImageXObject) resources.getXObject(resources.getXObjectNames().iterator().next()))
                    .getCOSObject();
            COSStream mask = assertInstanceOf(COSStream.class, image.getDictionaryObject(COSName.SMASK));
            assertTrue(image.getInt(COSName.WIDTH) < 1200, "width " + image.getInt(COSName.WIDTH));
            assertEquals(image.getInt(COSName.WIDTH), mask.getInt(COSName.WIDTH));
            assertEquals(image.getInt(COSName.HEIGHT), mask.getInt(COSName.HEIGHT));
        }
    }

    @Test
    void aSoftMaskSharedWithAnImageKeptWholeIsLeftAtItsResolution() throws Exception {
        Path in = pdf(doc -> {
            PDImageXObject mask = LosslessFactory.createFromImage(doc, gradient(1200, 1200));
            PDImageXObject small = LosslessFactory.createFromImage(doc, noise(1200, 1200));
            PDImageXObject large = LosslessFactory.createFromImage(doc, noise(1200, 1200));
            small.getCOSObject().setItem(COSName.SMASK, mask.getCOSObject());
            large.getCOSObject().setItem(COSName.SMASK, mask.getCOSObject());
            draw(doc, small, 144);
            draw(doc, large, 576);
        });

        try (PDDocument doc = Loader.loadPDF(optimize(in, AT_150_DPI).toFile())) {
            List<Integer> maskWidths = new ArrayList<>();
            for (PDPage page : doc.getPages()) {
                PDResources resources = page.getResources();
                COSStream image = ((PDImageXObject) resources.getXObject(resources.getXObjectNames().iterator().next()))
                        .getCOSObject();
                COSStream mask = assertInstanceOf(COSStream.class, image.getDictionaryObject(COSName.SMASK));
                maskWidths.add(mask.getInt(COSName.WIDTH));
            }
            assertTrue(maskWidths.get(0) < 1200, "resampled image's mask " + maskWidths);
            assertEquals(1200, maskWidths.get(1));
        }
    }

    @Test
    void aTwoColourPatternIsNotFlattenedOntoItsOwnPalette() throws Exception {
        int[] palette = {0x000000, 0xFFFFFF};
        Path in = pdf(doc -> draw(doc, checkered(doc, 1200, 1200, palette), 144));

        BufferedImage decoded = decodedOnlyImage(optimize(in, AT_150_DPI));

        int min = 255;
        int max = 0;
        for (int y = 0; y < decoded.getHeight(); y++) {
            for (int x = 0; x < decoded.getWidth(); x++) {
                int v = decoded.getRGB(x, y) & 0xFF;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        assertTrue(max - min < 64, "a fine checkerboard resamples to gray, not to " + min + ".." + max);
    }

    @Test
    void aSmoothGradientIsNotPosterisedOntoAPalette() throws Exception {
        Path in = pdf(doc -> draw(doc, LosslessFactory.createFromImage(doc, gradientWithText(1200, 1200)), 144));

        try (PDDocument doc = Loader.loadPDF(optimize(in, AT_150_DPI).toFile())) {
            PDResources resources = doc.getPage(0).getResources();
            PDImageXObject image = (PDImageXObject) resources.getXObject(resources.getXObjectNames().iterator().next());
            assertFalse(image.getColorSpace() instanceof org.apache.pdfbox.pdmodel.graphics.color.PDIndexed,
                    image.getColorSpace().getName());
        }
    }

    @Test
    void aSeparationThatMarksNothingIsLeftAlone() throws Exception {
        Path in = pdf(doc -> {
            PDImageXObject image = indexed(doc, 1200, 1200, new int[] {0, 0xFFFFFF});
            COSArray none = new COSArray();
            none.add(COSName.SEPARATION);
            none.add(COSName.getPDFName("None"));
            none.add(COSName.DEVICEGRAY);
            COSDictionary function = new COSDictionary();
            function.setInt(COSName.FUNCTION_TYPE, 2);
            COSArray domain = new COSArray();
            domain.setFloatArray(new float[] {0, 1});
            function.setItem(COSName.DOMAIN, domain);
            function.setInt(COSName.N, 1);
            none.add(function);
            image.getCOSObject().setItem(COSName.COLORSPACE, none);
            draw(doc, image, 144);
        });

        COSStream image = onlyImage(optimize(in, AT_150_DPI));

        assertEquals(1200, image.getInt(COSName.WIDTH));
    }

    @Test
    void aLowerResolutionNeverMakesALosslessScanLarger() throws Exception {
        Path in = pdf(doc -> draw(doc, LosslessFactory.createFromImage(doc, printedPage(1200, 1200)), 288));
        PdfOptimizer.Options at72Dpi = PdfOptimizer.Options.defaults().images(new PdfOptimizer.Images(72, 0.3f));

        Path at150Pdf = optimize(in, AT_150_DPI);
        long at150 = java.nio.file.Files.size(at150Pdf);
        int width150 = onlyImage(at150Pdf).getInt(COSName.WIDTH);
        Path at72Pdf = optimize(in, at72Dpi);
        long at72 = java.nio.file.Files.size(at72Pdf);
        int width72 = onlyImage(at72Pdf).getInt(COSName.WIDTH);

        assertTrue(width72 < width150, "width " + width72 + " >= " + width150);
        assertTrue(at72 <= at150, at72 + " > " + at150);
    }

    @Test
    void imageSettingsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new PdfOptimizer.Images(0, 0.7f));
        assertThrows(IllegalArgumentException.class, () -> new PdfOptimizer.Images(150, 0f));
        assertThrows(IllegalArgumentException.class, () -> new PdfOptimizer.Images(150, 1.5f));
    }

    private interface Builder {
        void build(PDDocument doc) throws Exception;
    }

    private Path pdf(Builder builder) throws Exception {
        Path in = dir.resolve("in.pdf");
        try (PDDocument doc = new PDDocument()) {
            builder.build(doc);
            doc.save(in.toFile());
        }
        return in;
    }

    private Path optimize(Path in, PdfOptimizer.Options options) throws Exception {
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, options);
        return out;
    }

    private static COSStream onlyImage(Path pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDPage page = doc.getPage(0);
            for (COSName name : page.getResources().getXObjectNames()) {
                if (page.getResources().getXObject(name) instanceof PDImageXObject image) {
                    COSStream stream = image.getCOSObject();
                    if (stream.getDictionaryObject(COSName.COLORSPACE) instanceof COSArray array) {
                        array.getObject(1);
                    }
                    return stream;
                }
            }
        }
        throw new AssertionError("no image on the first page");
    }

    private static BufferedImage decodedOnlyImage(Path pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDResources resources = doc.getPage(0).getResources();
            return ((PDImageXObject) resources.getXObject(resources.getXObjectNames().iterator().next())).getImage();
        }
    }

    private static List<COSBase> filters(COSStream stream) {
        COSBase filter = stream.getDictionaryObject(COSName.FILTER);
        if (filter instanceof COSArray array) {
            List<COSBase> names = new ArrayList<>();
            array.forEach(names::add);
            return names;
        }
        return filter == null ? List.of() : List.of(filter);
    }

    private static void draw(PDDocument doc, PDImageXObject image, float sizePt) throws Exception {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
            content.drawImage(image, 50, 50, sizePt, sizePt);
        }
    }

    private static PDImageXObject indexed(PDDocument doc, int width, int height, int[] palette) throws Exception {
        COSStream stream = doc.getDocument().createCOSStream();
        try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
            byte[] row = new byte[width];
            for (int x = 0; x < width; x++) {
                row[x] = (byte) (x * palette.length / width);
            }
            for (int y = 0; y < height; y++) {
                out.write(row);
            }
        }
        COSArray space = new COSArray();
        space.add(COSName.INDEXED);
        space.add(COSName.DEVICERGB);
        space.add(org.apache.pdfbox.cos.COSInteger.get(palette.length - 1));
        byte[] lookup = new byte[palette.length * 3];
        for (int i = 0; i < palette.length; i++) {
            lookup[i * 3] = (byte) (palette[i] >> 16);
            lookup[i * 3 + 1] = (byte) (palette[i] >> 8);
            lookup[i * 3 + 2] = (byte) palette[i];
        }
        space.add(new org.apache.pdfbox.cos.COSString(lookup));
        stream.setItem(COSName.TYPE, COSName.XOBJECT);
        stream.setItem(COSName.SUBTYPE, COSName.IMAGE);
        stream.setInt(COSName.WIDTH, width);
        stream.setInt(COSName.HEIGHT, height);
        stream.setInt(COSName.BITS_PER_COMPONENT, 8);
        stream.setItem(COSName.COLORSPACE, space);
        return new PDImageXObject(new PDStream(stream), null);
    }

    private static PDImageXObject checkered(PDDocument doc, int width, int height, int[] palette) throws Exception {
        PDImageXObject image = indexed(doc, width, height, palette);
        COSStream stream = image.getCOSObject();
        try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
            byte[] row = new byte[width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    row[x] = (byte) ((x + y) & 1);
                }
                out.write(row);
            }
        }
        return image;
    }

    private static BufferedImage gradientWithText(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, (x * 255 / width) << 16 | (y * 255 / height) << 8 | ((x + y) * 127 / (width + height)));
            }
        }
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(java.awt.Color.BLACK);
        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 40));
        for (int y = 60; y < height; y += 120) {
            g.drawString("Crisp label text over a gradient", 30, y);
        }
        g.dispose();
        return image;
    }

    private static BufferedImage printedPage(int width, int height) {
        Random random = new Random(7);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(java.awt.Color.BLACK);
        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 22));
        for (int y = 40; y < height; y += 34) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < 70; i++) {
                line.append((char) ('a' + random.nextInt(26)));
            }
            g.drawString(line.toString(), 20, y);
        }
        g.dispose();
        return image;
    }

    private static BufferedImage noise(int width, int height) {
        Random random = new Random(7);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        return image;
    }

    private static BufferedImage photo(int width, int height) {
        Random random = new Random(7);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = clamp(x * 255 / width + random.nextInt(9) - 4);
                int g = clamp(y * 255 / height + random.nextInt(9) - 4);
                int b = clamp((x + y) * 127 / (width + height) + 64 + random.nextInt(9) - 4);
                image.setRGB(x, y, r << 16 | g << 8 | b);
            }
        }
        return image;
    }

    private static BufferedImage photoWithRules(int width, int height) {
        BufferedImage image = photo(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (x % 100 < 2 || y % 100 < 2) {
                    image.setRGB(x, y, 0xFFFFFF);
                }
            }
        }
        return image;
    }

    private static BufferedImage grayPhoto(int width, int height) {
        Random random = new Random(7);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.getRaster().setSample(x, y, 0, clamp((x + y) * 255 / (width + height) + random.nextInt(9) - 4));
            }
        }
        return image;
    }

    private static BufferedImage stripes(int width, int height) {
        Random random = new Random(7);
        int[] palette = new int[256];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = random.nextInt(0xFFFFFF);
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, palette[(x / 6 + y / 6 * 7) & 0xFF]);
            }
        }
        return image;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static BufferedImage gradient(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.getRaster().setSample(x, y, 0, (x + y) & 0xFF);
            }
        }
        return image;
    }

    private static BufferedImage checks(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, ((x / 20 + y / 20) & 1) == 0 ? 0xFFFFFF : 0x2040A0);
            }
        }
        return image;
    }
}
