package stirling.software.officeconvert.photosplit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.function.Executable;

import stirling.software.officeconvert.photosplit.SyntheticScan.Style;

class HardeningTest {

    @TempDir
    Path dir;

    private static BufferedImage scan() {
        return new SyntheticScan(900, 700, 21)
                .photo(320, 240, 250, 220, 4, Style.GRADIENT, 0)
                .photo(300, 260, 640, 460, -3, Style.TEXTURE, 0)
                .render()
                .scan();
    }

    @Test
    void smallTolerancesSplitInsteadOfThrowing() {
        BufferedImage scan = scan();
        for (PhotoSplitter.Method method : PhotoSplitter.Method.values()) {
            for (int tolerance : new int[] {0, 1, 5}) {
                PhotoSplitter.Options options = PhotoSplitter.Options.defaults().method(method).tolerance(tolerance);
                List<PhotoSplitter.Photo> photos = assertDoesNotThrow(() -> PhotoSplitter.split(scan, options),
                        method + " tolerance " + tolerance);
                assertTrue(!photos.isEmpty(), method + " tolerance " + tolerance);
            }
        }
    }

    @Test
    void nonsenseOptionsAreRefused() {
        PhotoSplitter.Options defaults = PhotoSplitter.Options.defaults();
        assertThrows(IllegalArgumentException.class, () -> defaults.tolerance(-1));
        assertThrows(IllegalArgumentException.class, () -> defaults.tolerance(256));
        assertThrows(IllegalArgumentException.class, () -> defaults.minArea(-1));
        assertThrows(IllegalArgumentException.class, () -> defaults.minContourArea(-1));
        assertThrows(IllegalArgumentException.class, () -> defaults.maxPixels(0));
        assertThrows(IllegalArgumentException.class, () -> defaults.maxPixels(-1));
        assertDoesNotThrow(() -> defaults.tolerance(0).tolerance(255).minArea(0).minContourArea(0).maxPixels(1));
        assertEquals(PhotoSplitter.MAX_PIXELS, defaults.maxPixels());
    }

    @Test
    void houghAccumulatorSizeCannotOverflow() {
        float[] thetas = assertDoesNotThrow(() -> HoughLines.thetas(new byte[0], 10, 10_000_000, 200));
        assertEquals(0, thetas.length);
    }

    @Test
    void tallThinCropAngleUsesABoundedAccumulator() {
        int w = 20;
        int h = 100_000;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                px[y * w + x] = x < w / 2 ? 0x202020 : 0xF0F0F0;
            }
        }
        ScanImage crop = new ScanImage(w, h, px);
        long bytes = allocated(() -> ScriptMethod.angle(crop, 10));
        assertTrue(bytes < 40L << 20, "allocated " + (bytes >> 20) + " MB");
    }

    @Test
    void largeCropAngleMatchesTheSamePictureAnalysedAtFullSize() {
        ScanImage small = tilted(1500);
        ScanImage large = tilted(3000);
        double expected = ScriptMethod.angle(small, 10);
        double[] angle = new double[1];
        long bytes = allocated(() -> angle[0] = ScriptMethod.angle(large, 10));
        assertTrue(bytes < 100L << 20, "allocated " + (bytes >> 20) + " MB");
        assertTrue(Math.abs(expected) > 10, "expected " + expected);
        assertEquals(expected, angle[0], 1.5);
    }

    private static ScanImage tilted(int n) {
        BufferedImage image = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(246, 246, 243));
        g.fillRect(0, 0, n, n);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.rotate(Math.toRadians(20), n / 2.0, n / 2.0);
        g.setColor(new Color(60, 90, 150));
        g.fillRect(n / 5, n * 3 / 10, n * 3 / 5, n * 2 / 5);
        g.dispose();
        return ScanImage.of(image);
    }

    @Test
    void pngWithPdfMarkerInItsFirstKilobyteIsReadAsAnImage() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(scan(), "png", out);
        byte[] png = out.toByteArray();
        byte[] text = "Comment\0%PDF-1.7".getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        chunk.write(new byte[] {0, 0, 0, (byte) text.length});
        byte[] typed = new byte[4 + text.length];
        System.arraycopy("tEXt".getBytes(StandardCharsets.ISO_8859_1), 0, typed, 0, 4);
        System.arraycopy(text, 0, typed, 4, text.length);
        chunk.write(typed);
        CRC32 crc = new CRC32();
        crc.update(typed);
        long c = crc.getValue();
        chunk.write(new byte[] {(byte) (c >> 24), (byte) (c >> 16), (byte) (c >> 8), (byte) c});
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(png, 0, 33);
        file.write(chunk.toByteArray());
        file.write(png, 33, png.length - 33);
        Path input = dir.resolve("marked.png");
        Files.write(input, file.toByteArray());
        List<Path> written = PhotoSplitter.split(input, dir.resolve("out"), PhotoSplitter.Options.defaults());
        assertEquals(2, written.size());
    }

    @Test
    void sliverPdfPageOverThePixelLimitIsRefusedBeforeItIsDrawn() throws Exception {
        Path pdf = dir.resolve("sliver.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(new PDRectangle(54_000_000f, 0.001f)));
            doc.save(pdf.toFile());
        }
        long bytes = allocated(() -> {
            IOException refused = assertThrows(IOException.class,
                    () -> PhotoSplitter.split(pdf, dir.resolve("out"), PhotoSplitter.Options.defaults()));
            assertTrue(refused.getMessage().contains("the limit is " + PhotoSplitter.MAX_PIXELS), refused.getMessage());
        });
        assertTrue(bytes < 64L << 20, "allocated " + (bytes >> 20) + " MB before refusing");
    }

    @Test
    void corruptImageHeaderIsReportedAsAnIOException() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(scan(), "bmp", out);
        byte[] bmp = out.toByteArray();
        bmp[13] = (byte) 0xF4;
        Path input = dir.resolve("broken.bmp");
        Files.write(input, bmp);
        assertThrows(IOException.class, () -> PhotoSplitter.split(input, dir.resolve("out"), PhotoSplitter.Options.defaults()));
    }

    @Test
    void everyPageOfAMultiPageTiffIsSplitWithNumberingCarriedOn() throws Exception {
        Path input = dir.resolve("album.tif");
        ImageWriter writer = ImageIO.getImageWritersByFormatName("tiff").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(input.toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (int page = 0; page < 3; page++) {
                writer.writeToSequence(new IIOImage(scan(), null, null), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        List<Path> written = PhotoSplitter.split(input, dir.resolve("out"), PhotoSplitter.Options.defaults());
        assertEquals(6, written.size());
        for (int i = 0; i < 6; i++) {
            assertEquals("album_" + (i + 1) + ".png", written.get(i).getFileName().toString());
        }
    }

    @Test
    void tiffWithMorePagesThanTheCapIsRefused() throws Exception {
        int maxPages = PhotoSplitter.maxPages;
        PhotoSplitter.maxPages = 20;
        try {
            Path input = dir.resolve("many.tif");
            BufferedImage blank = new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY);
            ImageWriter writer = ImageIO.getImageWritersByFormatName("tiff").next();
            try (ImageOutputStream out = ImageIO.createImageOutputStream(input.toFile())) {
                writer.setOutput(out);
                writer.prepareWriteSequence(null);
                for (int page = 0; page <= PhotoSplitter.maxPages; page++) {
                    writer.writeToSequence(new IIOImage(blank, null, null), null);
                }
                writer.endWriteSequence();
            } finally {
                writer.dispose();
            }
            IOException refused = assertThrows(IOException.class,
                    () -> PhotoSplitter.split(input, dir.resolve("out"), PhotoSplitter.Options.defaults()));
            assertTrue(refused.getMessage().contains("more than 20 pages"), refused.getMessage());
        } finally {
            PhotoSplitter.maxPages = maxPages;
        }
    }

    @Test
    void aPageTreeWhoseKidsDisagreeWithItsCountIsSplitByCount() throws Exception {
        Path input = dir.resolve("tree.pdf");
        try (PDDocument doc = new PDDocument()) {
            COSDictionary root = doc.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.PAGES);
            COSDictionary middle = new COSDictionary();
            middle.setItem(COSName.TYPE, COSName.PAGES);
            middle.setInt(COSName.COUNT, 0);
            middle.setItem(COSName.PARENT, root);
            PDPage small = new PDPage(new PDRectangle(10, 10));
            small.getCOSObject().setItem(COSName.PARENT, middle);
            COSArray hidden = new COSArray();
            hidden.add(small.getCOSObject());
            middle.setItem(COSName.KIDS, hidden);
            PDPage big = new PDPage(new PDRectangle(200, 200));
            big.getCOSObject().setItem(COSName.PARENT, root);
            COSArray kids = new COSArray();
            kids.add(middle);
            kids.add(big.getCOSObject());
            root.setItem(COSName.KIDS, kids);
            root.setInt(COSName.COUNT, 1);
            doc.save(input.toFile());
        }
        assertDoesNotThrow(() -> PhotoSplitter.split(input, dir.resolve("out"), PhotoSplitter.Options.defaults()));
    }

    @Test
    void longPixelLoopsStopWhenInterrupted() {
        int n = 240;
        byte[] lShape = new byte[n * n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                lShape[y * n + x] = x < n / 2 || y >= n / 2 ? (byte) 1 : 0;
            }
        }
        Region region = new Region(0, 0, n - 1, n - 1, lShape.clone());
        int[] px = new int[n * n];
        for (int i = 0; i < px.length; i++) {
            px[i] = lShape[i] == 1 ? 0x3050A0 : 0xF6F6F3;
        }
        ScanImage scan = new ScanImage(n, n, px);
        PhotoSplitter.Options options = PhotoSplitter.Options.defaults().minArea(100).minContourArea(10);
        Bed bed = Bed.fit(scan, px, 30);
        assertStops("hough voting", () -> HoughLines.thetas(new byte[1], n, n, 200));
        assertStops("dilate", () -> Morphology.dilate(lShape.clone(), n, n, 2));
        assertStops("contours", () -> OuterContours.find(lShape.clone(), n, n));
        assertStops("label", () -> Region.label(lShape.clone(), n, n));
        assertStops("bed fit", () -> Bed.fit(scan, px, 30));
        assertStops("bed foreground", () -> bed.foreground(px, n, n, 10, new byte[n * n]));
        assertStops("pieces", () -> Pieces.separate(region, options));
        assertStops("seams", () -> Seams.split(List.of(region), new byte[n * n], scan, options));
        assertStops("steps", () -> Steps.cut(region, scan, options));
        assertStops("edge fit", () -> EdgeFit.refine(scan, new RotatedRect(n / 2.0, n / 2.0, 200, 200, 0)));
    }

    private static void assertStops(String what, Executable work) {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, work, what);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void maxPixelsRefusesAnImageOverTheCallersLimitAndAcceptsOneAtIt() throws Exception {
        Path input = dir.resolve("scan.png");
        ImageIO.write(scan(), "png", input.toFile());
        PhotoSplitter.Options defaults = PhotoSplitter.Options.defaults();
        IOException refused = assertThrows(IOException.class,
                () -> PhotoSplitter.split(input, dir.resolve("out"), defaults.maxPixels(629_999)));
        assertTrue(refused.getMessage().contains("has 630000 pixels; the limit is 629999"), refused.getMessage());
        assertEquals(2, PhotoSplitter.split(input, dir.resolve("out"), defaults.maxPixels(630_000)).size());
        assertThrows(IllegalArgumentException.class, () -> PhotoSplitter.split(scan(), defaults.maxPixels(629_999)));
        assertEquals(2, PhotoSplitter.split(scan(), defaults.maxPixels(630_000)).size());
    }

    @Test
    void maxPixelsAppliesToEveryPageOfAMultiPageTiff() throws Exception {
        Path input = dir.resolve("album.tif");
        BufferedImage small = new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("tiff").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(input.toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            writer.writeToSequence(new IIOImage(small, null, null), null);
            writer.writeToSequence(new IIOImage(scan(), null, null), null);
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        IOException refused = assertThrows(IOException.class, () -> PhotoSplitter.split(input, dir.resolve("out"),
                PhotoSplitter.Options.defaults().maxPixels(1000)));
        assertTrue(refused.getMessage().contains("album.tif page 2 has 630000 pixels; the limit is 1000"),
                refused.getMessage());
    }

    @Test
    void maxPixelsRefusesAPdfPageThatCannotBeDrawnWithinItAndAcceptsOneThatCan() throws Exception {
        Path sliver = dir.resolve("sliver.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(new PDRectangle(200, 0.01f)));
            doc.save(sliver.toFile());
        }
        IOException refused = assertThrows(IOException.class, () -> PhotoSplitter.split(sliver, dir.resolve("out"),
                PhotoSplitter.Options.defaults().maxPixels(500)));
        assertTrue(refused.getMessage().contains("sliver.pdf page 1 would be drawn with"), refused.getMessage());
        assertTrue(refused.getMessage().endsWith("the limit is 500"), refused.getMessage());

        Path scans = dir.resolve("scans.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(450, 350));
            doc.addPage(page);
            PDImageXObject image = LosslessFactory.createFromImage(doc, scan());
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image, 0, 0, 450, 350);
            }
            doc.save(scans.toFile());
        }
        List<Path> written = PhotoSplitter.split(scans, dir.resolve("pdf"),
                PhotoSplitter.Options.defaults().dpi(144).maxPixels(630_000));
        assertEquals(2, written.size());
    }

    @Test
    void pdfPagesAreDrawnSmallEnoughForTheCallersLimit() {
        PDRectangle letter = PDRectangle.LETTER;
        float dpi = PhotoSplitter.pageDpi(letter, 300, 1_000_000);
        long pixels = (long) Math.ceil(letter.getWidth() / 72.0 * dpi) * (long) Math.ceil(letter.getHeight() / 72.0 * dpi);
        assertTrue(pixels <= 1_000_000, pixels + " pixels at " + dpi + " dpi");
        assertTrue(pixels > 990_000, pixels + " pixels at " + dpi + " dpi");
        assertEquals(300f, PhotoSplitter.pageDpi(letter, 300, PhotoSplitter.MAX_PIXELS));
    }

    @Test
    void ppmScanSplitsWithJaiImageIoOnTheClasspath() throws Exception {
        assertTrue(readerClasses("ppm").stream().anyMatch(name -> name.startsWith("com.github.jaiimageio.")),
                readerClasses("ppm").toString());
        Path input = dir.resolve("scan.ppm");
        Files.write(input, ppm(scan(), ""));
        List<Path> written = PhotoSplitter.split(input, dir.resolve("out"), PhotoSplitter.Options.defaults());
        assertEquals(2, written.size());
        IOException refused = assertThrows(IOException.class, () -> PhotoSplitter.split(input, dir.resolve("small"),
                PhotoSplitter.Options.defaults().maxPixels(1000)));
        assertTrue(refused.getMessage().contains("the limit is 1000"), refused.getMessage());
    }

    @Test
    void ppmHeaderThatOverflowsJaiImageIoIsReportedAsAnIOException() throws Exception {
        Path input = dir.resolve("blank.ppm");
        Files.write(input, ppm(scan(), "\n".repeat(1_000_000)));
        IOException refused = assertThrows(IOException.class,
                () -> PhotoSplitter.split(input, dir.resolve("out"), PhotoSplitter.Options.defaults()));
        assertTrue(refused.getMessage().contains("StackOverflowError"), refused.getMessage());
    }

    @Test
    void readersAreTriedJdkFirstAndJaiImageIoLast() {
        for (String format : new String[] {"tiff", "bmp"}) {
            List<ImageReader> readers = PhotoSplitter.readerOrder(ImageIO.getImageReadersByFormatName(format));
            try {
                assertTrue(readers.size() >= 2, format + " " + readers);
                assertTrue(readers.getFirst().getClass().getName().startsWith("com.sun.imageio.plugins."),
                        format + " " + readers);
                assertTrue(readers.getLast().getClass().getName().startsWith("com.github.jaiimageio."),
                        format + " " + readers);
            } finally {
                readers.forEach(ImageReader::dispose);
            }
        }
    }

    @Test
    void aReaderThatOverflowsGivesWayToTheNextReader() throws Exception {
        OverflowingReaderSpi overflowing = new OverflowingReaderSpi();
        IIORegistry registry = IIORegistry.getDefaultInstance();
        registry.registerServiceProvider(overflowing, ImageReaderSpi.class);
        try {
            Path input = dir.resolve("scan.ppm");
            Files.write(input, ppm(scan(), ""));
            List<Path> written = PhotoSplitter.split(input, dir.resolve("out"), PhotoSplitter.Options.defaults());
            assertEquals(2, written.size());
            assertTrue(overflowing.attempts.get() > 0);
        } finally {
            registry.deregisterServiceProvider(overflowing, ImageReaderSpi.class);
        }
    }

    private static List<String> readerClasses(String suffix) {
        List<String> names = new ArrayList<>();
        Iterator<ImageReader> readers = ImageIO.getImageReadersBySuffix(suffix);
        while (readers.hasNext()) {
            ImageReader reader = readers.next();
            names.add(reader.getClass().getName());
            reader.dispose();
        }
        return names;
    }

    private static byte[] ppm(BufferedImage image, String padding) {
        int w = image.getWidth();
        int h = image.getHeight();
        byte[] header = ("P6\n" + padding + w + " " + h + "\n255\n").getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = new byte[header.length + 3 * w * h];
        System.arraycopy(header, 0, out, 0, header.length);
        int i = header.length;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = image.getRGB(x, y);
                out[i++] = (byte) (rgb >> 16);
                out[i++] = (byte) (rgb >> 8);
                out[i++] = (byte) rgb;
            }
        }
        return out;
    }

    private static final class OverflowingReaderSpi extends ImageReaderSpi {

        final AtomicInteger attempts = new AtomicInteger();

        OverflowingReaderSpi() {
            super("test", "1", new String[] {"ppm-test"}, new String[] {"ppm"}, new String[] {"image/x-portable-pixmap"},
                    OverflowingReader.class.getName(), new Class<?>[] {ImageInputStream.class}, null, false, null,
                    null, null, null, false, null, null, null, null);
        }

        @Override
        public boolean canDecodeInput(Object source) throws IOException {
            ImageInputStream stream = (ImageInputStream) source;
            stream.mark();
            try {
                return stream.read() == 'P' && stream.read() == '6';
            } finally {
                stream.reset();
            }
        }

        @Override
        public ImageReader createReaderInstance(Object extension) {
            return new OverflowingReader(this);
        }

        @Override
        public String getDescription(Locale locale) {
            return "overflowing test reader";
        }
    }

    private static final class OverflowingReader extends ImageReader {

        private final OverflowingReaderSpi spi;

        OverflowingReader(OverflowingReaderSpi spi) {
            super(spi);
            this.spi = spi;
        }

        private StackOverflowError overflow() {
            spi.attempts.incrementAndGet();
            return new StackOverflowError();
        }

        @Override
        public int getNumImages(boolean allowSearch) {
            throw overflow();
        }

        @Override
        public int getWidth(int imageIndex) {
            throw overflow();
        }

        @Override
        public int getHeight(int imageIndex) {
            throw overflow();
        }

        @Override
        public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) {
            throw overflow();
        }

        @Override
        public IIOMetadata getStreamMetadata() {
            throw overflow();
        }

        @Override
        public IIOMetadata getImageMetadata(int imageIndex) {
            throw overflow();
        }

        @Override
        public BufferedImage read(int imageIndex, ImageReadParam param) {
            throw overflow();
        }
    }

    private static long allocated(Runnable work) {
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        work.run();
        return threads.getCurrentThreadAllocatedBytes() - before;
    }
}
