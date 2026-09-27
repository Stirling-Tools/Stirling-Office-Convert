package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType2;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PicturesTest {

    private static final int JPEG_SIDE = 300;

    @TempDir static Path dir;
    static Path pdf;
    static BufferedImage photo;
    static BufferedImage big;

    record Media(String name, String format, BufferedImage image) {
        int width() {
            return image.getWidth();
        }
    }

    @BeforeAll
    static void makePdf() throws IOException {
        photo = photo(900, 900, 1);
        big = photo(1200, 1200, 2);
        pdf = dir.resolve("pictures.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.shadingFill(gradient());
                cs.drawImage(LosslessFactory.createFromImage(doc, photo), 72, 400, 300, 300);
                cs.drawImage(LosslessFactory.createFromImage(doc, big), 450, 600, 72, 72);
                cs.saveGraphicsState();
                cs.addRect(400, 400, 100, 60);
                cs.clip();
                cs.drawImage(JPEGFactory.createFromImage(doc, photo(JPEG_SIDE, JPEG_SIDE, 3), 0.9f), 400, 380, 150, 150);
                cs.restoreGraphicsState();
            }
            doc.save(pdf.toFile());
        }
    }

    @Test
    void compactWordThinsAndCompresses() throws IOException {
        List<Media> media = media(convert("docx", Pictures.COMPACT), "word/media/");
        assertEquals("jpeg", find(media, 900).format(), "the large opaque picture becomes a JPEG");
        assertTrue(media.stream().anyMatch(m -> m.width() == 240), "the picture drawn an inch wide is thinned to 220 ppi");
        assertFalse(media.stream().anyMatch(m -> m.width() == 1200), "nothing keeps its full 1200 pixels");
    }

    @Test
    void losslessWordKeepsEveryPixel() throws IOException {
        List<Media> media = media(convert("docx", Pictures.LOSSLESS), "word/media/");
        assertLossless(media);
        assertEquals("png", find(media, 900).format());
        assertTrue(samePixels(photo, find(media, 900).image()), "the photo's pixels are the PDF's, exactly");
        assertTrue(samePixels(big, find(media, 1200).image()), "the picture drawn small keeps all 1200 pixels");
        assertEquals("jpeg", find(media, JPEG_SIDE).format(), "the PDF's own JPEG is copied as it is");
    }

    @Test
    void losslessSlidesKeepEveryPixel() throws IOException {
        for (String format : List.of("pptx", "odp")) {
            List<Media> media = media(convert(format, Pictures.LOSSLESS), "pptx".equals(format) ? "ppt/media/" : "Pictures/");
            assertLossless(media);
            assertTrue(samePixels(photo, find(media, 900).image()), format + ": the photo's pixels are the PDF's");
        }
        List<Media> compact = media(convert("pptx", Pictures.COMPACT), "ppt/media/");
        assertTrue(compact.stream().anyMatch(m -> "jpeg".equals(m.format()) && m.width() != JPEG_SIDE),
                "compact slides compress the photo or the art");
    }

    @Test
    void losslessOdtCutsTheCroppedJpegOutAsPng() throws IOException {
        List<Media> compact = media(convert("odt", Pictures.COMPACT), "Pictures/");
        assertTrue(compact.stream().anyMatch(m -> "jpeg".equals(m.format()) && m.width() < JPEG_SIDE),
                "compact cuts the cropped JPEG out as a JPEG again");
        List<Media> lossless = media(convert("odt", Pictures.LOSSLESS), "Pictures/");
        assertLossless(lossless);
        assertTrue(lossless.stream().anyMatch(m -> "png".equals(m.format()) && m.width() < JPEG_SIDE && m.width() > 100),
                "the cropped part of the JPEG is a PNG");
    }

    @Test
    void losslessRtfHoldsNoJpeg() throws IOException {
        String compact = Files.readString(convert("rtf", Pictures.COMPACT), StandardCharsets.ISO_8859_1);
        String lossless = Files.readString(convert("rtf", Pictures.LOSSLESS), StandardCharsets.ISO_8859_1);
        assertTrue(compact.contains("\\jpegblip"), "compact RTF has JPEG pictures");
        assertFalse(lossless.contains("\\jpegblip"), "lossless RTF has none: its only JPEG is shown cropped, cut out as PNG");
        assertTrue(lossless.contains("\\pngblip"));
    }

    @Test
    void settingsCarryPicturesToEveryFormat() throws IOException {
        assertEquals(Pictures.COMPACT, OfficeConvert.Settings.defaults().pictures());
        assertEquals(Pictures.COMPACT, PdfToDocx.Options.defaults().pictures());
        assertEquals(Pictures.COMPACT, PdfToPptx.Options.defaults().pictures());
        Path docx = dir.resolve("facade.docx");
        OfficeConvert.convert(pdf, docx, OfficeConvert.Settings.defaults().pictures(Pictures.LOSSLESS));
        assertTrue(samePixels(photo, find(media(docx, "word/media/"), 900).image()));
        assertTrue(OfficeConvert.Settings.defaults().pictures(Pictures.LOSSLESS).toString().contains("pictures=LOSSLESS"));
        assertThrows(NullPointerException.class, () -> OfficeConvert.Settings.defaults().pictures(null));
        assertThrows(NullPointerException.class, () -> PdfToDocx.Options.defaults().withPictures(null));
        assertThrows(NullPointerException.class, () -> PdfToPptx.Options.defaults().withPictures(null));
    }

    private static void assertLossless(List<Media> media) {
        for (Media m : media) {
            assertTrue("png".equals(m.format()) || "jpeg".equals(m.format()) && m.width() == JPEG_SIDE,
                    m.name() + " is " + m.format() + " at " + m.width() + " pixels");
        }
    }

    private static Path convert(String format, Pictures pictures) throws IOException {
        Path out = dir.resolve(pictures.name().toLowerCase(Locale.ROOT) + "." + format);
        OfficeConvert.convert(pdf, out, OfficeConvert.Settings.defaults().pictures(pictures));
        return out;
    }

    private static Media find(List<Media> media, int width) {
        return media.stream().filter(m -> m.width() == width).findFirst()
                .orElseThrow(() -> new AssertionError("no picture " + width + " pixels wide in " + media.stream()
                        .map(m -> m.name() + " " + m.format() + " " + m.width()).toList()));
    }

    private static List<Media> media(Path file, String folder) throws IOException {
        List<Media> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            for (ZipEntry e : Collections.list(zip.entries())) {
                if (e.isDirectory() || !e.getName().startsWith(folder)) {
                    continue;
                }
                byte[] bytes = zip.getInputStream(e).readAllBytes();
                try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                    Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
                    if (!readers.hasNext()) {
                        continue;
                    }
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(in);
                        String format = reader.getFormatName().toLowerCase(Locale.ROOT).startsWith("jp") ? "jpeg" : "png";
                        out.add(new Media(e.getName(), format, reader.read(0)));
                    } finally {
                        reader.dispose();
                    }
                }
            }
        }
        return out;
    }

    private static boolean samePixels(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return false;
        }
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if ((a.getRGB(x, y) & 0xFFFFFF) != (b.getRGB(x, y) & 0xFFFFFF)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static BufferedImage photo(int w, int h, long seed) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(seed);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int red = Math.min(255, x * 200 / w + r.nextInt(56));
                int green = Math.min(255, y * 200 / h + r.nextInt(56));
                int blue = Math.min(255, (x + y) * 100 / (w + h) + 100 + r.nextInt(56));
                img.setRGB(x, y, red << 16 | green << 8 | blue);
            }
        }
        return img;
    }

    private static PDShading gradient() {
        COSDictionary fn = new COSDictionary();
        fn.setInt(COSName.FUNCTION_TYPE, 2);
        fn.setItem(COSName.DOMAIN, floats(0, 1));
        fn.setItem(COSName.C0, floats(0.85f, 0.9f, 1f));
        fn.setItem(COSName.C1, floats(0.4f, 0.5f, 0.8f));
        fn.setInt(COSName.N, 1);
        PDShadingType2 shading = new PDShadingType2(new COSDictionary());
        shading.setShadingType(PDShading.SHADING_TYPE2);
        shading.setColorSpace(PDDeviceRGB.INSTANCE);
        shading.setFunction(new PDFunctionType2(fn));
        shading.setCoords(floats(0, 0, 0, 792));
        COSArray extend = new COSArray();
        extend.add(COSBoolean.TRUE);
        extend.add(COSBoolean.TRUE);
        shading.setExtend(extend);
        return shading;
    }

    private static COSArray floats(float... v) {
        COSArray a = new COSArray();
        for (float f : v) {
            a.add(f == (int) f ? COSInteger.get((int) f) : new COSFloat(f));
        }
        return a;
    }
}
