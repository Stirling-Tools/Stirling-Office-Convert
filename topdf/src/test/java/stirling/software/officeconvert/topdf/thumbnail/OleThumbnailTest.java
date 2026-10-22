package stirling.software.officeconvert.topdf.thumbnail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.hpsf.Property;
import org.apache.poi.hpsf.PropertySetFactory;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.hpsf.Variant;
import org.apache.poi.hpsf.WritingNotSupportedException;
import org.apache.poi.hpsf.wellknown.PropertyIDMap;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.io.PictureDecoder;

class OleThumbnailTest {

    private static final int RED = 0x0000C0;

    @TempDir
    Path dir;

    @Test
    void aPublicationIsDrawnFromItsMetafilePreviewOnThePaperItsShapeMatches() throws IOException {
        byte[] wmf = wmf(1000, 1414);
        byte[] clip = clip(3, concat(le(8).putShort((short) 8).putShort((short) 2100).putShort((short) 2970)
                .putShort((short) 0).array(), wmf));
        Path in = Files.write(dir.resolve("flyer.pub"), ole("Quill", clip));
        OfficeToPdf.Result r = OfficeToPdf.convert(in, dir.resolve("flyer.pdf"));
        assertEquals(1, r.pages());
        assertTrue(r.truncated());
        assertTrue(r.warnings().getFirst().startsWith("Only the first page's preview could be converted"), r.warnings()
                .toString());
        assertTrue(r.warnings().getFirst().contains("Publisher"));
        try (PDDocument pdf = Loader.loadPDF(dir.resolve("flyer.pdf").toFile())) {
            PDPage page = pdf.getPage(0);
            assertEquals(595.28f, page.getMediaBox().getWidth(), 0.5f);
            assertEquals(841.89f, page.getMediaBox().getHeight(), 0.5f);
            assertTrue(hasForm(page));
            BufferedImage img = new PDFRenderer(pdf).renderImageWithDPI(0, 36);
            assertTrue(red(img.getRGB(img.getWidth() / 2, img.getHeight() / 2)));
        }
        assertTrue(OfficeToPdf.memoryEstimate(in) > 0);
    }

    @Test
    void aDrawingIsDrawnFromItsEnhancedMetafileOnTheWholeFrameOfThePage() throws IOException {
        byte[] emf = emf(new int[] {100, 100, 300, 200}, new int[] {0, 0, 21590, 27940}, true);
        Path in = Files.write(dir.resolve("plan.vsd"), ole("VisioDocument", clip(14, emf)));
        OfficeToPdf.Result r = OfficeToPdf.convert(in, dir.resolve("plan.pdf"));
        assertEquals(1, r.pages());
        assertTrue(r.warnings().getFirst().contains("Visio"));
        try (PDDocument pdf = Loader.loadPDF(dir.resolve("plan.pdf").toFile())) {
            PDPage page = pdf.getPage(0);
            assertEquals(612, page.getMediaBox().getWidth(), 0.5f);
            assertEquals(792, page.getMediaBox().getHeight(), 0.5f);
            BufferedImage img = new PDFRenderer(pdf).renderImageWithDPI(0, 72);
            assertTrue(red(img.getRGB(177, 133)));
            assertFalse(red(img.getRGB(400, 600)));
            assertFalse(red(img.getRGB(40, 40)));
        }
    }

    @Test
    void aBitmapPreviewIsDrawnAsAPicture() throws IOException {
        Path in = Files.write(dir.resolve("card.pub"), ole("Quill", clip(8, dib())));
        OfficeToPdf.convert(in, dir.resolve("card.pdf"));
        try (PDDocument pdf = Loader.loadPDF(dir.resolve("card.pdf").toFile())) {
            PDPage page = pdf.getPage(0);
            assertEquals(480, page.getMediaBox().getWidth(), 0.5f);
            assertEquals(360, page.getMediaBox().getHeight(), 0.5f);
            BufferedImage img = new PDFRenderer(pdf).renderImageWithDPI(0, 36);
            assertTrue(red(img.getRGB(img.getWidth() / 2, img.getHeight() / 2)));
        }
    }

    @Test
    void aMetafileHoldingOnlyABitmapIsDrawnAsThatBitmapOnItsFrame() throws IOException {
        byte[] dib = dib();
        int fields = 80;
        ByteBuffer record = le(fields + dib.length).putInt(81).putInt(fields + dib.length).putInt(0).putInt(0)
                .putInt(1).putInt(1).putInt(0).putInt(0).putInt(0).putInt(0).putInt(2).putInt(2).putInt(fields)
                .putInt(48).putInt(fields + 48).putInt(dib.length - 48).putInt(0).putInt(0x00CC0020).putInt(2)
                .putInt(2).put(dib);
        byte[] header = emf(new int[] {0, 0, 1, 1}, new int[] {0, 0, 2100, 2970}, false);
        byte[] emf = concat(concat(Arrays.copyOf(header, 88), record.array()), Arrays.copyOfRange(header, 88,
                header.length));
        assertTrue(PictureDecoder.sniff(OleThumbnail.soleBitmap(emf)) == PictureDecoder.Kind.BMP);
        Path in = Files.write(dir.resolve("raster.vsd"), ole("VisioDocument", clip(14, emf)));
        OfficeToPdf.convert(in, dir.resolve("raster.pdf"));
        try (PDDocument pdf = Loader.loadPDF(dir.resolve("raster.pdf").toFile())) {
            PDPage page = pdf.getPage(0);
            assertEquals(595.28f, page.getMediaBox().getWidth(), 0.5f);
            BufferedImage img = new PDFRenderer(pdf).renderImageWithDPI(0, 36);
            assertTrue(red(img.getRGB(img.getWidth() / 2, img.getHeight() / 2)));
        }
    }

    @Test
    void aBlankOrMissingPreviewIsRefusedWithTheReason() throws IOException {
        byte[] blank = emf(new int[] {0, 0, -1, -1}, new int[] {0, 0, 21590, 27940}, false);
        Path empty = Files.write(dir.resolve("empty.vsd"), ole("VisioDocument", clip(14, blank)));
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(empty, dir.resolve("empty.pdf")));
        assertTrue(e.getMessage().contains("holds no preview picture"), e.getMessage());
        Path none = Files.write(dir.resolve("none.pub"), ole("Quill", null));
        e = assertThrows(IOException.class, () -> OfficeToPdf.convert(none, dir.resolve("none.pdf")));
        assertTrue(e.getMessage().contains("Publisher") && e.getMessage().contains("no preview"), e.getMessage());
        Path thumb = Files.write(dir.resolve("thumb.pub"), ole("Quill", clip(8, dib(100, 140))));
        e = assertThrows(IOException.class, () -> OfficeToPdf.convert(thumb, dir.resolve("thumb.pdf")));
        assertTrue(e.getMessage().contains("thumbnail too small"), e.getMessage());
        Path junk = Files.write(dir.resolve("junk.vsd"), new byte[] {1, 2, 3});
        e = assertThrows(IOException.class, () -> OfficeToPdf.memoryEstimate(junk));
        assertTrue(e.getMessage().contains("Visio") && !e.getMessage().contains("not supported"), e.getMessage());
        Path wordPerfect = Files.write(dir.resolve("letter.pub"), new byte[] {(byte) 0xFF, 'W', 'P', 'C', 16, 0, 0, 0,
            1, 10});
        e = assertThrows(IOException.class, () -> OfficeToPdf.memoryEstimate(wordPerfect));
        assertTrue(e.getMessage().contains("WordPerfect"), e.getMessage());
    }

    @Test
    void aMetafileThatOnlyStretchesABitmapIsMeasuredByThatBitmap() {
        byte[] dib = dib(100, 140);
        ByteArrayOutputStream records = new ByteArrayOutputStream();
        record(records, 0x020C, 140, 100);
        int[] words = new int[11 + dib.length / 2];
        words[0] = 0x0020;
        words[1] = 0x00CC;
        System.arraycopy(new int[] {0, 140, 100, 0, 0, 140, 100, 0, 0}, 0, words, 2, 9);
        ByteBuffer d = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < dib.length / 2; i++) {
            words[11 + i] = d.getShort(2 * i);
        }
        record(records, 0x0F43, words);
        record(records, 0x0000);
        byte[] body = records.toByteArray();
        byte[] wmf = le(18 + body.length).putShort((short) 1).putShort((short) 9).putShort((short) 0x0300)
                .putInt((18 + body.length) / 2).putShort((short) 0).putInt(0).putShort((short) 0).put(body).array();
        assertEquals(140, OleThumbnail.bitmapSide(wmf));
        assertEquals(-1, OleThumbnail.bitmapSide(wmf(100, 140)));
    }

    @Test
    void theClipboardFormatsAreUnwrapped() {
        assertNull(OleThumbnail.clipboard(new byte[] {1, 2, 3}));
        assertNull(OleThumbnail.clipboard(clip(3, new byte[30])));
        OleThumbnail.Preview p = OleThumbnail.clipboard(clip(3, concat(le(8).putShort((short) 8)
                .putShort((short) 21590).putShort((short) 2794).putShort((short) 0).array(), wmf(10, 10))));
        assertEquals(612, p.widthPoints(), 0.5f);
        assertEquals(79.2f, p.heightPoints(), 0.5f);
    }

    private static byte[] dib() {
        return dib(640, 480);
    }

    private static byte[] dib(int width, int height) {
        int stride = (width + 31) / 32 * 4;
        return le(40 + 8 + stride * height).putInt(40).putInt(width).putInt(height).putShort((short) 1)
                .putShort((short) 1).putInt(0).putInt(stride * height).putInt(2835).putInt(2835).putInt(2).putInt(0)
                .putInt(0x00C00000).putInt(0x00FFFFFF).array();
    }

    private static boolean hasForm(PDPage page) throws IOException {
        for (COSName n : page.getResources().getXObjectNames()) {
            if (page.getResources().getXObject(n) instanceof PDFormXObject) {
                return true;
            }
        }
        return false;
    }

    private static boolean red(int rgb) {
        Color c = new Color(rgb);
        return c.getRed() > 150 && c.getGreen() < 80 && c.getBlue() < 80;
    }

    private static byte[] ole(String main, byte[] clip) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            fs.createDocument(new ByteArrayInputStream(new byte[64]), main);
            if (clip != null) {
                SummaryInformation si = PropertySetFactory.newSummaryInformation();
                si.getFirstSection().setProperty(new Property(PropertyIDMap.PID_THUMBNAIL, Variant.VT_CF, clip));
                try {
                    si.write(fs.getRoot(), SummaryInformation.DEFAULT_STREAM_NAME);
                } catch (WritingNotSupportedException e) {
                    throw new IOException(e);
                }
            }
            fs.writeFilesystem(out);
        }
        return out.toByteArray();
    }

    private static byte[] clip(int format, byte[] data) {
        return le(12 + data.length).putInt(8 + data.length).putInt(-1).putInt(format).put(data).array();
    }

    private static byte[] wmf(int width, int height) {
        ByteArrayOutputStream records = new ByteArrayOutputStream();
        record(records, 0x020B, 0, 0);
        record(records, 0x020C, height, width);
        record(records, 0x02FC, 0, RED & 0xFFFF, RED >>> 16, 0);
        record(records, 0x012D, 0);
        record(records, 0x061D, 0x0021, 0x00F0, height, width, 0, 0);
        record(records, 0x0000);
        byte[] body = records.toByteArray();
        ByteBuffer b = le(18 + body.length);
        b.putShort((short) 1).putShort((short) 9).putShort((short) 0x0300).putInt((18 + body.length) / 2)
                .putShort((short) 1).putInt(9).putShort((short) 0).put(body);
        return b.array();
    }

    private static void record(ByteArrayOutputStream out, int function, int... words) {
        ByteBuffer b = le(6 + 2 * words.length).putInt(3 + words.length).putShort((short) function);
        for (int w : words) {
            b.putShort((short) w);
        }
        out.writeBytes(b.array());
    }

    private static byte[] emf(int[] bounds, int[] frame, boolean rectangle) {
        int body = rectangle ? 24 + 12 + 24 : 0;
        int total = 88 + body + 20;
        ByteBuffer b = le(total);
        b.putInt(1).putInt(88);
        for (int v : bounds) {
            b.putInt(v);
        }
        for (int v : frame) {
            b.putInt(v);
        }
        b.putInt(0x464D4520).putInt(0x10000).putInt(total).putInt(rectangle ? 5 : 2).putShort((short) 2)
                .putShort((short) 0).putInt(0).putInt(0).putInt(0).putInt(1024).putInt(768).putInt(320).putInt(240);
        if (rectangle) {
            b.putInt(39).putInt(24).putInt(1).putInt(0).putInt(RED).putInt(0);
            b.putInt(37).putInt(12).putInt(1);
            b.putInt(43).putInt(24);
            for (int v : bounds) {
                b.putInt(v);
            }
        }
        b.putInt(14).putInt(20).putInt(0).putInt(16).putInt(20);
        return b.array();
    }

    private static ByteBuffer le(int n) {
        return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
