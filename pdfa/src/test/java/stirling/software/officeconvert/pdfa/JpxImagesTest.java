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
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.jpx.JpxDecoder;
import stirling.software.officeconvert.jpx.JpxEncoder;
import stirling.software.officeconvert.jpx.JpxRaster;

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
    void aBareCodestreamIsKeptInsideAJp2Container() throws Exception {
        byte[] codestream = codestream(unsigned(jpx(photo())));
        assertTrue(JpxHeader.parse(codestream).bare(), "bare");
        assertFalse(JpxHeader.parse(codestream).allowedInPdfA(true));
        for (PdfALevel level : new PdfALevel[] {PdfALevel.A2B, PdfALevel.A4}) {
            Path in = page(codestream, 160, 120, false);
            Path out = dir.resolve("bare-" + level + ".pdf");
            PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
            assertTrue(hasJpx(out), "kept");
            VeraPdf.assertCompliant(out, level);
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                COSStream image = d.getPage(0).getResources().getXObject(COSName.getPDFName("Im0"))
                        .getCOSObject();
                byte[] raw;
                try (java.io.InputStream i = image.createRawInputStream()) {
                    raw = i.readAllBytes();
                }
                JpxHeader h = JpxHeader.parse(raw);
                assertFalse(h.bare());
                assertEquals(3, h.channels());
                assertEquals(8, h.bitDepth());
                assertTrue(h.allowedInPdfA(false), "allowed");
            }
            assertTrue(meanDifference(in, out) < 0.5, level.toString());
        }
        byte[] signed = codestream(jpx(photo()));
        signed[42] |= (byte) 0x80;
        Path in = page(signed, 160, 120, false);
        Path out = dir.resolve("bare-signed.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        assertFalse(hasJpx(out));
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
    }

    private static byte[] codestream(byte[] jp2) {
        for (int i = 0; i + 8 < jp2.length; i++) {
            if (jp2[i] == 'j' && jp2[i + 1] == 'p' && jp2[i + 2] == '2' && jp2[i + 3] == 'c') {
                byte[] c = java.util.Arrays.copyOfRange(jp2, i + 4, jp2.length);
                int components = (c[40] & 0xFF) << 8 | c[41] & 0xFF;
                for (int k = 0; k < components; k++) {
                    c[42 + 3 * k] &= 0x7F;
                }
                return c;
            }
        }
        throw new IllegalStateException("no codestream");
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

    private static JpxEncoder.Planes photoWithAlpha(int w, int h, boolean colour) {
        java.util.Random rnd = new java.util.Random(5);
        int n = colour ? 4 : 2;
        int[][] s = new int[n][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                for (int c = 0; c < n - 1; c++) {
                    double v = 128 + 90 * Math.sin(x * (0.02 + 0.01 * c) + y * 0.015) * Math.cos(y * 0.025 - c);
                    s[c][i] = Math.clamp(Math.round(v + rnd.nextGaussian() * 3), 0, 255);
                }
                double dx = (x - w / 2.0) / (w / 2.0);
                double dy = (y - h / 2.0) / (h / 2.0);
                s[n - 1][i] = Math.clamp(Math.round(255 * (1.3 - dx * dx - dy * dy)), 0, 255);
            }
        }
        int[] depths = new int[n];
        java.util.Arrays.fill(depths, 8);
        return new JpxEncoder.Planes(w, h, s, depths, null, colour ? JpxEncoder.Colour.SRGB : JpxEncoder.Colour.GREY,
                n - 1);
    }

    private static COSStream largestImage(PDDocument d) {
        COSStream best = null;
        for (COSObjectKey key : d.getDocument().getXrefTable().keySet()) {
            if (d.getDocument().getObjectFromPool(key).getObject() instanceof COSStream s
                    && COSName.IMAGE.equals(s.getCOSName(COSName.SUBTYPE))
                    && s.getDictionaryObject(COSName.SMASK) instanceof COSStream
                    && (best == null || s.getInt(COSName.WIDTH) > best.getInt(COSName.WIDTH))) {
                best = s;
            }
        }
        return best;
    }

    @Test
    void partTwoStoresAPhotoAsJpegTwoThousandWhenThatIsSmallerThanJpeg() throws Exception {
        JpxEncoder.Planes source = photoWithAlpha(400, 300, true);
        byte[] encoded = JpxEncoder.encode(source, JpxEncoder.Options.rate(5));
        Path in = page(encoded, 400, 300, true);
        Path out = dir.resolve("2b-photo.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
        JpxRaster decoded = JpxDecoder.decode(encoded).raster();
        BufferedImage colours = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 300; y++) {
            for (int x = 0; x < 400; x++) {
                int rgb = decoded.sample(0, x, y) << 16 | decoded.sample(1, x, y) << 8 | decoded.sample(2, x, y);
                colours.setRGB(x, y, rgb);
            }
        }
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSStream jpx = largestImage(d);
            long jpeg = JPEGFactory.createFromImage(d, colours, 0.92f).getCOSObject().getLength();
            assertEquals(COSName.JPX_DECODE, jpx.getDictionaryObject(COSName.FILTER));
            assertTrue(jpx.getLength() < jpeg, jpx.getLength() + " >= " + jpeg);
            assertTrue(JpxHeader.parse(samples(jpx, true)).allowedInPdfA(true));
            assertEquals(COSName.DEVICERGB, jpx.getDictionaryObject(COSName.COLORSPACE));
            JpxRaster kept = JpxDecoder.decode(samples(jpx, true)).raster();
            double se = 0;
            for (int c = 0; c < 3; c++) {
                for (int y = 0; y < 300; y++) {
                    for (int x = 0; x < 400; x++) {
                        double e = kept.sample(c, x, y) - decoded.sample(c, x, y);
                        se += e * e;
                    }
                }
            }
            double psnr = 10 * Math.log10(255.0 * 255.0 * 3 * 400 * 300 / se);
            assertTrue(psnr > 38, "psnr " + psnr);
        }
    }

    @Test
    void partTwoSplitsColourAndAlphaOutOfTheCodestreamWithoutReencoding() throws Exception {
        for (boolean colour : new boolean[] {true, false}) {
            JpxEncoder.Planes source = photoWithAlpha(400, 300, colour);
            byte[] encoded = JpxEncoder.encode(source, JpxEncoder.Options.rate(0.5).withLayers(3));
            Path in = page(encoded, 400, 300, true);
            Path out = dir.resolve("2b-split-" + colour + ".pdf");
            PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
            VeraPdf.assertCompliant(out, PdfALevel.A2B);
            JpxRaster original = JpxDecoder.decode(encoded).raster();
            int colours = colour ? 3 : 1;
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                COSStream image = largestImage(d);
                COSStream mask = (COSStream) image.getDictionaryObject(COSName.SMASK);
                assertEquals(COSName.JPX_DECODE, image.getDictionaryObject(COSName.FILTER));
                assertEquals(COSName.JPX_DECODE, mask.getDictionaryObject(COSName.FILTER));
                assertTrue(image.getLength() + mask.getLength() < encoded.length + 200,
                        image.getLength() + mask.getLength() + " vs " + encoded.length);
                JpxRaster kept = JpxDecoder.decode(samples(image, true)).raster();
                JpxRaster alpha = JpxDecoder.decode(samples(mask, true)).raster();
                assertEquals(colours, kept.components());
                for (int y = 0; y < 300; y++) {
                    for (int x = 0; x < 400; x++) {
                        for (int c = 0; c < colours; c++) {
                            assertEquals(original.sample(c, x, y), kept.sample(c, x, y));
                        }
                        assertEquals(original.sample(colours, x, y), alpha.sample(0, x, y));
                    }
                }
            }
        }
    }

    private static JpxEncoder.Planes texturedWithAlpha(int[] depths, int[] values) {
        int w = 320;
        int h = 240;
        java.util.Random rnd = new java.util.Random(3);
        int[][] s = new int[4][w * h];
        for (int c = 0; c < 4; c++) {
            int max = (1 << depths[c]) - 1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    double base = c == 3 ? (x < w / 2 ? max : max / 2.0) : values[c] * max / 255.0;
                    double v = base + max * 0.08 * Math.sin(x * 0.11 + y * 0.07 + c) + rnd.nextGaussian() * max * 0.03;
                    s[c][y * w + x] = Math.clamp(Math.round(v), 0, max);
                }
            }
        }
        return new JpxEncoder.Planes(w, h, s, depths, null, JpxEncoder.Colour.SRGB, 3);
    }

    @Test
    void anAlphaSplitIsNotUsedWhenTheColoursHaveMixedDepths() throws Exception {
        JpxEncoder.Planes source = texturedWithAlpha(new int[] {8, 8, 4, 8}, new int[] {30, 120, 150});
        Path in = page(JpxEncoder.encode(source, JpxEncoder.Options.rate(0.4)), 320, 240, true);
        Path out = dir.resolve("2b-mixed.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSStream image = largestImage(d);
            if (COSName.JPX_DECODE.equals(image.getDictionaryObject(COSName.FILTER))) {
                JpxRaster kept = JpxDecoder.decode(samples(image, true)).raster();
                for (int c = 1; c < kept.components(); c++) {
                    assertEquals(kept.depth(0), kept.depth(c));
                }
            }
        }
    }

    @Test
    void anAlphaSplitKeepsTheChannelOrderTheFileDefines() throws Exception {
        JpxEncoder.Planes source = texturedWithAlpha(new int[] {8, 8, 8, 8}, new int[] {30, 120, 220});
        byte[] jp2 = JpxEncoder.encode(source, JpxEncoder.Options.rate(0.4));
        int at = new String(jp2, java.nio.charset.StandardCharsets.ISO_8859_1).indexOf("cdef") + 6;
        jp2[at + 5] = 3;
        jp2[at + 6 * 2 + 5] = 1;
        Path in = page(jp2, 320, 240, true);
        Path out = dir.resolve("2b-order.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSStream image = largestImage(d);
            BufferedImage img = new PDImageXObject(new PDStream(image), null).getImage();
            long red = 0;
            long blue = 0;
            for (int y = 0; y < 240; y += 8) {
                for (int x = 0; x < 320; x += 8) {
                    red += img.getRGB(x, y) >> 16 & 0xFF;
                    blue += img.getRGB(x, y) & 0xFF;
                }
            }
            assertTrue(red > blue * 3, red + " vs " + blue);
        }
    }

    @Test
    void partTwoStoresGreyAndAlphaAsLosslessJpegTwoThousand() throws Exception {
        JpxEncoder.Planes source = photoWithAlpha(400, 300, false);
        Path in = page(JpxEncoder.encode(source, JpxEncoder.Options.exact()), 400, 300, true);
        Path out = dir.resolve("2b-grey-lossless.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSStream image = largestImage(d);
            COSStream mask = (COSStream) image.getDictionaryObject(COSName.SMASK);
            assertEquals(COSName.JPX_DECODE, image.getDictionaryObject(COSName.FILTER));
            int[] grey = JpxDecoder.decode(samples(image, true)).raster().width() == 400 ? decodedPlane(image) : null;
            assertNotNull(grey);
            assertTrue(java.util.Arrays.equals(source.samples()[0], grey), "grey samples");
            byte[] alpha = mask.getDictionaryObject(COSName.FILTER) == COSName.JPX_DECODE ? null : samples(mask);
            int[] alphaPlane = alpha == null ? decodedPlane(mask) : toInts(alpha);
            assertTrue(java.util.Arrays.equals(source.samples()[1], alphaPlane), "alpha samples");
        }
    }

    private static int[] decodedPlane(COSStream s) throws Exception {
        JpxRaster r = JpxDecoder.decode(samples(s, true)).raster();
        int[] out = new int[r.width() * r.height()];
        for (int y = 0; y < r.height(); y++) {
            for (int x = 0; x < r.width(); x++) {
                out[y * r.width() + x] = r.sample(0, x, y);
            }
        }
        return out;
    }

    private static int[] toInts(byte[] b) {
        int[] out = new int[b.length];
        for (int i = 0; i < b.length; i++) {
            out[i] = b[i] & 0xFF;
        }
        return out;
    }

    private static byte[] samples(COSStream s, boolean raw) throws Exception {
        try (java.io.InputStream in = raw ? s.createRawInputStream() : s.createInputStream()) {
            return in.readAllBytes();
        }
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
