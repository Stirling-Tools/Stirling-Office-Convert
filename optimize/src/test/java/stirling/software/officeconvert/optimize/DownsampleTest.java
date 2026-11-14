package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
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
