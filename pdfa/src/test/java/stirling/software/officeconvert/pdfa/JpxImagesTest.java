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
import java.awt.image.IndexColorModel;
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
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
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

    private static BufferedImage colourWithAlpha() {
        ComponentColorModel cm = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), true, false,
                Transparency.TRANSLUCENT, DataBuffer.TYPE_BYTE);
        WritableRaster r = cm.createCompatibleWritableRaster(80, 60);
        for (int y = 0; y < 60; y++) {
            for (int x = 0; x < 80; x++) {
                r.setPixel(x, y, new int[] {x * 3, 100, 255 - x * 3, x < 40 ? 255 : 128});
            }
        }
        return new BufferedImage(cm, r, false, null);
    }

    private Path page(byte[] jpx, int width, int height, boolean smaskInData) throws Exception {
        return page(jpx, width, height, smaskInData, null);
    }

    private Path page(byte[] jpx, int width, int height, boolean smaskInData, COSBase colourSpace) throws Exception {
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
            if (colourSpace != null) {
                img.setItem(COSName.COLORSPACE, colourSpace);
                img.setInt(COSName.BITS_PER_COMPONENT, 8);
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
        Path in = page(jpx(colourWithAlpha()), 80, 60, true);
        Path out = dir.resolve("1b-alpha.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        assertFalse(hasJpx(out));
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            BufferedImage converted = null;
            for (COSObjectKey key : d.getDocument().getXrefTable().keySet()) {
                if (d.getDocument().getObjectFromPool(key).getObject() instanceof COSStream s
                        && COSName.IMAGE.equals(s.getCOSName(COSName.SUBTYPE)) && s.getInt(COSName.WIDTH) == 80) {
                    converted = new PDImageXObject(new PDStream(s), null).getOpaqueImage();
                }
            }
            assertNotNull(converted);
            int rgb = converted.getRGB(42, 5);
            assertEquals(126, rgb >> 16 & 0xFF, 3);
            assertEquals(100, rgb >> 8 & 0xFF, 3);
            assertEquals(129, rgb & 0xFF, 3);
        }
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
    void convertedGreyAndAlphaKeepTheirSamples() throws Exception {
        Path in = page(jpx(grayWithAlpha()), 80, 60, true);
        Path out = dir.resolve("2b-grey-samples.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSStream image = null;
            for (COSObjectKey key : d.getDocument().getXrefTable().keySet()) {
                if (d.getDocument().getObjectFromPool(key).getObject() instanceof COSStream s
                        && COSName.IMAGE.equals(s.getCOSName(COSName.SUBTYPE)) && s.getInt(COSName.WIDTH) == 80
                        && s.getDictionaryObject(COSName.SMASK) instanceof COSStream) {
                    image = s;
                }
            }
            assertNotNull(image);
            byte[] grey = samples(image);
            byte[] alpha = samples((COSStream) image.getDictionaryObject(COSName.SMASK));
            assertEquals(6, grey[2] & 0xFF, 2);
            assertEquals(150, grey[50] & 0xFF, 2);
            assertEquals(255, alpha[10] & 0xFF, 2);
            assertEquals(128, alpha[50] & 0xFF, 2);
        }
    }

    @Test
    void alphaWithoutSMaskInDataStillBecomesASoftMask() throws Exception {
        for (boolean colour : new boolean[] {true, false}) {
            Path in = page(jpx(colour ? colourWithAlpha() : grayWithAlpha()), 80, 60, false);
            for (PdfALevel level : new PdfALevel[] {PdfALevel.A1B, PdfALevel.A2B}) {
                Path out = dir.resolve("no-smid-" + colour + "-" + level + ".pdf");
                PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
                VeraPdf.assertCompliant(out, level);
                assertEquals(colour && level == PdfALevel.A2B, hasJpx(out));
                if (hasJpx(out)) {
                    continue;
                }
                try (PDDocument d = Loader.loadPDF(out.toFile())) {
                    BufferedImage page = new PDFRenderer(d).renderImage(0);
                    int[] opaque = colour ? new int[] {30, 100, 225} : new int[] {30, 30, 30};
                    int[] half = colour ? new int[] {202, 177, 180} : new int[] {202, 202, 202};
                    assertPixel(page.getRGB(100 + 10 * 4 + 2, 842 - 640 + 5 * 4 + 2), opaque, colour + " " + level);
                    assertPixel(page.getRGB(100 + 50 * 4 + 2, 842 - 640 + 5 * 4 + 2), half, colour + " " + level);
                }
            }
        }
    }

    private static void assertPixel(int[] got, int[] want, String what) {
        for (int i = 0; i < 3; i++) {
            assertEquals(want[i], got[i], 6, what + " " + java.util.Arrays.toString(got));
        }
    }

    private static void assertPixel(int rgb, int[] want, String what) {
        for (int i = 0; i < 3; i++) {
            assertEquals(want[i], rgb >> (16 - 8 * i) & 0xFF, 6, what + " " + Integer.toHexString(rgb));
        }
    }

    private static byte[] samples(COSStream s) throws Exception {
        try (java.io.InputStream in = s.createInputStream()) {
            return in.readAllBytes();
        }
    }

    private static BufferedImage palette() {
        byte[] r = {(byte) 255, 0, 0, (byte) 255};
        byte[] g = {0, (byte) 255, 0, (byte) 255};
        byte[] b = {0, 0, (byte) 255, 0};
        BufferedImage img = new BufferedImage(80, 60, BufferedImage.TYPE_BYTE_INDEXED, new IndexColorModel(8, 4, r, g, b));
        WritableRaster raster = img.getRaster();
        for (int y = 0; y < 60; y++) {
            for (int x = 0; x < 80; x++) {
                raster.setSample(x, y, 0, x / 20);
            }
        }
        return img;
    }

    private static int[] pagePixel(Path pdf, int x, int y) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            int rgb = new PDFRenderer(d).renderImage(0).getRGB(100 + x * 4 + 2, 842 - 640 + y * 4 + 2);
            return new int[] {rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF};
        }
    }

    @Test
    void anIndexedImageReadsThePaletteIndicesNotThePaletteColours() throws Exception {
        byte[] data = jpx(palette());
        assertTrue(JpxHeader.parse(data).palette());
        COSArray indexed = new COSArray();
        indexed.add(COSName.INDEXED);
        indexed.add(COSName.DEVICERGB);
        indexed.add(COSInteger.get(3));
        indexed.add(new COSString(new byte[] {0, 0, (byte) 255, (byte) 255, 0, 0, 0, (byte) 255, 0, 0, 0, 0}));
        Path in = page(data, 80, 60, false, indexed);
        Path out = dir.resolve("1b-indexed.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        assertFalse(hasJpx(out));
        assertPixel(pagePixel(out, 5, 30), new int[] {0, 0, 255}, "index 0");
        assertPixel(pagePixel(out, 25, 30), new int[] {255, 0, 0}, "index 1");
        assertPixel(pagePixel(out, 45, 30), new int[] {0, 255, 0}, "index 2");
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
    }

    @Test
    void twoComponentsInADeviceNSpaceAreBothColours() throws Exception {
        COSStream tint = new COSStream();
        try (OutputStream o = tint.createOutputStream()) {
            o.write("{ 0 }".getBytes(StandardCharsets.US_ASCII));
        }
        tint.setInt(COSName.FUNCTION_TYPE, 4);
        tint.setItem(COSName.DOMAIN, floats(0, 1, 0, 1));
        tint.setItem(COSName.RANGE, floats(0, 1, 0, 1, 0, 1));
        COSArray names = new COSArray();
        names.add(COSName.getPDFName("A"));
        names.add(COSName.getPDFName("B"));
        COSArray deviceN = new COSArray();
        deviceN.add(COSName.DEVICEN);
        deviceN.add(names);
        deviceN.add(COSName.DEVICERGB);
        deviceN.add(tint);
        Path in = page(jpx(grayWithAlpha()), 80, 60, false, deviceN);
        for (PdfALevel level : new PdfALevel[] {PdfALevel.A1B, PdfALevel.A2B}) {
            Path out = dir.resolve("devicen-" + level + ".pdf");
            PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
            assertFalse(hasJpx(out));
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                COSStream image = null;
                for (COSObjectKey key : d.getDocument().getXrefTable().keySet()) {
                    if (d.getDocument().getObjectFromPool(key).getObject() instanceof COSStream s
                            && COSName.IMAGE.equals(s.getCOSName(COSName.SUBTYPE)) && s.getInt(COSName.WIDTH) == 80) {
                        image = s;
                    }
                }
                assertNotNull(image);
                assertTrue(image.getDictionaryObject(COSName.COLORSPACE) instanceof COSArray a
                        && COSName.DEVICEN.equals(a.getObject(0)), level.toString());
                assertFalse(image.containsKey(COSName.SMASK));
                byte[] samples = samples(image);
                int left = (30 * 80 + 10) * 2;
                int right = (30 * 80 + 70) * 2;
                assertEquals(30, samples[left] & 0xFF, 2);
                assertEquals(255, samples[left + 1] & 0xFF, 2);
                assertEquals(210, samples[right] & 0xFF, 2);
                assertEquals(128, samples[right + 1] & 0xFF, 2);
            }
            VeraPdf.assertCompliant(out, level);
        }
    }

    @Test
    void partTwoKeepsTheAlphaThatSMaskInDataAsksFor() throws Exception {
        Path in = page(jpx(colourWithAlpha()), 80, 60, true);
        Path out = dir.resolve("2b-smaskindata.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        assertFalse(hasJpx(out));
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            BufferedImage page = new PDFRenderer(d).renderImage(0);
            assertPixel(page.getRGB(100 + 10 * 4 + 2, 842 - 640 + 5 * 4 + 2), new int[] {30, 100, 225}, "opaque");
            assertPixel(page.getRGB(100 + 50 * 4 + 2, 842 - 640 + 5 * 4 + 2), new int[] {202, 177, 180}, "half");
        }
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
    }

    private static COSArray floats(float... v) {
        COSArray a = new COSArray();
        for (float f : v) {
            a.add(new org.apache.pdfbox.cos.COSFloat(f));
        }
        return a;
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
