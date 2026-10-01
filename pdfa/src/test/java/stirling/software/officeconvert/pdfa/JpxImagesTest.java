package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JpxImagesTest {

    @TempDir
    Path dir;

    private static byte[] jpx(BufferedImage img) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg2000").next();
        try (ImageOutputStream o = ImageIO.createImageOutputStream(b)) {
            w.setOutput(o);
            w.write(img);
        }
        return b.toByteArray();
    }

    private static BufferedImage photo() {
        BufferedImage img = new BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 120; y++) {
            for (int x = 0; x < 160; x++) {
                img.setRGB(x, y, (x * 255 / 160) << 16 | (y * 255 / 120) << 8 | (x * y) % 256);
            }
        }
        return img;
    }

    private static BufferedImage grayWithAlpha() {
        ComponentColorModel cm = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), true, false,
                Transparency.TRANSLUCENT, DataBuffer.TYPE_BYTE);
        WritableRaster r = cm.createCompatibleWritableRaster(80, 60);
        for (int y = 0; y < 60; y++) {
            for (int x = 0; x < 80; x++) {
                r.setPixel(x, y, new int[] {x * 3, x < 40 ? 255 : 128});
            }
        }
        return new BufferedImage(cm, r, false, null);
    }

    private Path page(byte[] jpx, int width, int height, boolean smaskInData) throws Exception {
        Path in = dir.resolve("jpx.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage p = Samples.page(d);
            COSStream img = d.getDocument().createCOSStream();
            try (OutputStream o = img.createRawOutputStream()) {
                o.write(jpx);
            }
            img.setItem(COSName.TYPE, COSName.XOBJECT);
            img.setItem(COSName.SUBTYPE, COSName.IMAGE);
            img.setItem(COSName.FILTER, COSName.JPX_DECODE);
            img.setInt(COSName.WIDTH, width);
            img.setInt(COSName.HEIGHT, height);
            if (smaskInData) {
                img.setInt(COSName.getPDFName("SMaskInData"), 1);
            }
            COSDictionary xobjects = new COSDictionary();
            xobjects.setItem(COSName.getPDFName("Im0"), img);
            COSDictionary res = new COSDictionary();
            res.setItem(COSName.XOBJECT, xobjects);
            p.getCOSObject().setItem(COSName.RESOURCES, res);
            PDStream s = new PDStream(d);
            try (OutputStream o = s.createOutputStream(COSName.FLATE_DECODE)) {
                o.write("q 320 0 0 240 100 400 cm /Im0 Do Q".getBytes(StandardCharsets.US_ASCII));
            }
            p.setContents(s);
            d.save(in.toFile());
        }
        return in;
    }

    private static byte[] unsigned(byte[] jp2) {
        byte[] d = jp2.clone();
        for (int i = 0; i + 14 < d.length; i++) {
            if (d[i] == 'i' && d[i + 1] == 'h' && d[i + 2] == 'd' && d[i + 3] == 'r') {
                d[i + 14] &= 0x7F;
                return d;
            }
        }
        return d;
    }

    private static boolean hasJpx(Path pdf) throws Exception {
        return new String(Files.readAllBytes(pdf), StandardCharsets.ISO_8859_1).contains("JPXDecode");
    }

    private static double meanDifference(Path a, Path b) throws Exception {
        try (PDDocument x = Loader.loadPDF(a.toFile()); PDDocument y = Loader.loadPDF(b.toFile())) {
            BufferedImage i = new PDFRenderer(x).renderImage(0);
            BufferedImage j = new PDFRenderer(y).renderImage(0);
            long sum = 0;
            for (int yy = 0; yy < i.getHeight(); yy++) {
                for (int xx = 0; xx < i.getWidth(); xx++) {
                    int p = i.getRGB(xx, yy);
                    int q = j.getRGB(xx, yy);
                    for (int s = 0; s < 24; s += 8) {
                        sum += Math.abs((p >> s & 0xFF) - (q >> s & 0xFF));
                    }
                }
            }
            return sum / (3.0 * i.getWidth() * i.getHeight());
        }
    }

    @Test
    void partOneDecodesJpegTwoThousandImages() throws Exception {
        Path in = page(jpx(photo()), 160, 120, false);
        Path out = dir.resolve("1b.pdf");
        PdfToPdfA.Result r = PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        assertFalse(hasJpx(out));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("JPEG 2000")));
        double d = meanDifference(in, out);
        assertTrue(d < 2.5, "mean difference " + d);
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
    }

    @Test
    void partOneKeepsTheAlphaOfAJpegTwoThousandImage() throws Exception {
        Path in = page(jpx(grayWithAlpha()), 80, 60, true);
        Path out = dir.resolve("1b-alpha.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        assertFalse(hasJpx(out));
        assertTrue(meanDifference(in, out) < 3);
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
    }

    @Test
    void partTwoKeepsAllowedJpegTwoThousandImages() throws Exception {
        byte[] data = unsigned(jpx(photo()));
        assertTrue(JpxHeader.parse(data).allowedInPdfA(false));
        Path in = page(data, 160, 120, false);
        Path out = dir.resolve("2b.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        assertTrue(hasJpx(out));
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
    }

    @Test
    void partTwoConvertsJpegTwoThousandWithTwoChannels() throws Exception {
        byte[] data = jpx(grayWithAlpha());
        JpxHeader h = JpxHeader.parse(data);
        assertNotNull(h);
        assertEquals(2, h.channels());
        assertFalse(h.allowedInPdfA(true));
        Path in = page(data, 80, 60, true);
        Path out = dir.resolve("2b-two-channels.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        assertFalse(hasJpx(out));
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
    }

    @Test
    void headerRulesFollowPartTwo() {
        assertTrue(header(3, 7, null, new int[] {1, 0, 16}).allowedInPdfA(false));
        assertFalse(header(2, 7, null, new int[] {1, 0, 16}).allowedInPdfA(true));
        assertFalse(header(3, 7, "bpcc", new int[] {1, 0, 16}).allowedInPdfA(true));
        assertFalse(header(3, 7, null, new int[] {1, 0, 19}).allowedInPdfA(false));
        assertTrue(header(3, 7, null, new int[] {1, 0, 19}).allowedInPdfA(true));
        assertFalse(header(3, 7, null, new int[] {4, 0, 0}).allowedInPdfA(false));
        assertFalse(header(3, 7, null, new int[] {1, 0, 16}, new int[] {2, 0, 0}).allowedInPdfA(false));
        assertTrue(header(3, 7, null, new int[] {1, 1, 16}, new int[] {2, 0, 0}).allowedInPdfA(false));
    }

    private static JpxHeader header(int channels, int bpc, String extra, int[]... colr) {
        ByteArrayOutputStream jp2h = new ByteArrayOutputStream();
        box(jp2h, "ihdr", new byte[] {0, 0, 0, 10, 0, 0, 0, 10, 0, (byte) channels, (byte) bpc, 7, 0, 0});
        if (extra != null) {
            box(jp2h, extra, new byte[] {(byte) bpc, (byte) bpc, (byte) bpc});
        }
        for (int[] c : colr) {
            box(jp2h, "colr", new byte[] {(byte) c[0], 0, (byte) c[1], 0, 0, 0, (byte) c[2]});
        }
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        box(file, "jP  ", new byte[] {0x0D, 0x0A, (byte) 0x87, 0x0A});
        box(file, "ftyp", "jp2 \0\0\0\0jp2 ".getBytes(StandardCharsets.ISO_8859_1));
        box(file, "jp2h", jp2h.toByteArray());
        return JpxHeader.parse(file.toByteArray());
    }

    private static void box(ByteArrayOutputStream out, String type, byte[] body) {
        int len = 8 + body.length;
        out.writeBytes(new byte[] {(byte) (len >> 24), (byte) (len >> 16), (byte) (len >> 8), (byte) len});
        out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(body);
    }
}
