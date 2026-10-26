package stirling.software.officeconvert.lineart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.function.IntBinaryOperator;
import java.util.stream.Stream;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
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
import org.apache.pdfbox.pdmodel.graphics.image.CCITTFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class VariantsTest {

    private static final int W = 67;
    private static final int H = 45;

    record Kind(String name, COSBase colourSpace, int components, int bits, double[] decode, Sampler sampler) {
        @Override
        public String toString() {
            return name;
        }
    }

    interface Sampler {
        int[] sample(double darkness, int max);
    }

    static double darkness(int x, int y) {
        boolean ink = (x / 5 + y / 4) % 4 == 0 || Math.abs(x - 2 * y) < 2 || x == 0 || y == H - 1;
        return ink ? 0.92 : 0.05 + 0.25 * x / W;
    }

    static Stream<Arguments> kinds() throws IOException {
        Sampler grey = (d, max) -> new int[] {(int) Math.round((1 - d) * max)};
        Sampler rgb = (d, max) -> new int[] {(int) Math.round((1 - d) * max), (int) Math.round((1 - d) * max),
            (int) Math.round((1 - d * 0.9) * max)};
        Sampler cmyk = (d, max) -> new int[] {0, 0, (int) Math.round(d * 0.2 * max), (int) Math.round(d * max)};
        Sampler tint = (d, max) -> new int[] {(int) Math.round(d * max)};
        Sampler two = (d, max) -> new int[] {(int) Math.round(d * max), (int) Math.round(d * max)};
        Sampler lab = (d, max) -> new int[] {(int) Math.round((1 - d) * max), max / 2, max / 2};
        List<Arguments> list = new java.util.ArrayList<>();
        for (int bits : new int[] {1, 2, 4, 8, 16}) {
            list.add(Arguments.of(new Kind("DeviceGray " + bits, COSName.DEVICEGRAY, 1, bits, null, grey)));
        }
        for (int bits : new int[] {4, 8, 16}) {
            list.add(Arguments.of(new Kind("DeviceRGB " + bits, COSName.DEVICERGB, 3, bits, null, rgb)));
        }
        list.add(Arguments.of(new Kind("DeviceCMYK 8", COSName.DEVICECMYK, 4, 8, null, cmyk)));
        list.add(Arguments.of(new Kind("DeviceCMYK 16", COSName.DEVICECMYK, 4, 16, null, cmyk)));
        list.add(Arguments.of(new Kind("DeviceGray Decode [1 0]", COSName.DEVICEGRAY, 1, 8, new double[] {1, 0},
                (d, max) -> new int[] {(int) Math.round(d * max)})));
        list.add(Arguments.of(new Kind("DeviceRGB Decode [1 0 1 0 1 0]", COSName.DEVICERGB, 3, 8,
                new double[] {1, 0, 1, 0, 1, 0}, (d, max) -> new int[] {(int) Math.round(d * max),
                    (int) Math.round(d * max), (int) Math.round(d * max)})));
        for (int bits : new int[] {1, 2, 4, 8}) {
            int hival = (1 << bits) - 1;
            byte[] palette = new byte[(hival + 1) * 3];
            for (int i = 0; i <= hival; i++) {
                int v = 255 - i * 255 / hival;
                palette[i * 3] = (byte) v;
                palette[i * 3 + 1] = (byte) v;
                palette[i * 3 + 2] = (byte) Math.min(255, v + 20);
            }
            list.add(Arguments.of(new Kind("Indexed " + bits, array(COSName.INDEXED, COSName.DEVICERGB,
                    COSInteger.get(hival), new COSString(palette)), 1, bits, null,
                    (d, max) -> new int[] {(int) Math.round(d * max)})));
        }
        list.add(Arguments.of(new Kind("Indexed 4 Decode [15 0]", array(COSName.INDEXED, COSName.DEVICEGRAY,
                COSInteger.get(15), new COSString(greyRamp(16))), 1, 4, new double[] {15, 0},
                (d, max) -> new int[] {(int) Math.round((1 - d) * max)})));
        list.add(Arguments.of(new Kind("ICCBased sRGB", icc(ColorSpace.CS_sRGB, 3), 3, 8, null, rgb)));
        list.add(Arguments.of(new Kind("ICCBased grey", icc(ColorSpace.CS_GRAY, 1), 1, 8, null, grey)));
        COSDictionary cal = new COSDictionary();
        cal.setItem(COSName.WHITE_POINT, numbers(0.9505, 1, 1.089));
        cal.setItem(COSName.GAMMA, numbers(2.2, 2.2, 2.2));
        list.add(Arguments.of(new Kind("CalRGB", array(COSName.CALRGB, cal), 3, 8, null, rgb)));
        COSDictionary calGrey = new COSDictionary();
        calGrey.setItem(COSName.WHITE_POINT, numbers(0.9505, 1, 1.089));
        calGrey.setItem(COSName.GAMMA, new COSFloat(2.2f));
        list.add(Arguments.of(new Kind("CalGray", array(COSName.CALGRAY, calGrey), 1, 8, null, grey)));
        COSDictionary labParameters = new COSDictionary();
        labParameters.setItem(COSName.WHITE_POINT, numbers(0.9505, 1, 1.089));
        labParameters.setItem(COSName.RANGE, numbers(-100, 100, -100, 100));
        list.add(Arguments.of(new Kind("Lab", array(COSName.LAB, labParameters), 3, 8,
                new double[] {0, 100, -100, 100, -100, 100}, lab)));
        list.add(Arguments.of(new Kind("Separation", separation(), 1, 8, null, tint)));
        list.add(Arguments.of(new Kind("DeviceN", deviceN(), 2, 8, null, two)));
        return list.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("kinds")
    void everyColourSpaceAndDepthMatchesPdfBoxsOwnDecoding(Kind kind) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            COSStream stream = sampled(doc, kind);
            assertConvertsLikePdfBox(doc, stream, LineArt.Options.defaults().method(LineArt.Method.FIXED), false);
        }
        try (PDDocument doc = new PDDocument()) {
            COSStream stream = sampled(doc, kind);
            assertConvertsLikePdfBox(doc, stream, LineArt.Options.defaults().inkColour(true), false);
        }
    }

    @Test
    void preBlendedSoftMaskIsUnblendedAndGetsItsOwnMaskWithoutMatte() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            COSStream soft = doc.getDocument().createCOSStream();
            soft.setItem(COSName.TYPE, COSName.XOBJECT);
            soft.setItem(COSName.SUBTYPE, COSName.IMAGE);
            soft.setInt(COSName.WIDTH, W);
            soft.setInt(COSName.HEIGHT, H);
            soft.setInt(COSName.BITS_PER_COMPONENT, 8);
            soft.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
            soft.setItem(COSName.MATTE, numbers(1, 1, 1));
            byte[] alpha = new byte[W * H];
            byte[] colour = new byte[W * H * 3];
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    double a = 0.35 + 0.65 * y / H;
                    alpha[y * W + x] = (byte) Math.round(a * 255);
                    double c = 1 - darkness(x, y);
                    int v = (int) Math.round((1 + a * (c - 1)) * 255);
                    colour[(y * W + x) * 3] = (byte) v;
                    colour[(y * W + x) * 3 + 1] = (byte) v;
                    colour[(y * W + x) * 3 + 2] = (byte) v;
                }
            }
            write(soft, alpha);
            COSStream image = image(doc, COSName.DEVICERGB, 8, colour);
            image.setItem(COSName.SMASK, soft);
            assertConvertsLikePdfBox(doc, image, LineArt.Options.defaults().method(LineArt.Method.FIXED), true);
            COSStream newSoft = (COSStream) image.getDictionaryObject(COSName.SMASK);
            assertNotSame(soft, newSoft);
            assertNull(newSoft.getDictionaryObject(COSName.MATTE));
            assertNotNull(soft.getDictionaryObject(COSName.MATTE));
        }
    }

    @Test
    void jpegTwoThousandInputConverts() throws IOException {
        BufferedImage picture = picture(BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg2000").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(picture);
        }
        try (PDDocument doc = new PDDocument()) {
            COSStream stream = doc.getDocument().createCOSStream();
            stream.setItem(COSName.TYPE, COSName.XOBJECT);
            stream.setItem(COSName.SUBTYPE, COSName.IMAGE);
            stream.setInt(COSName.WIDTH, W);
            stream.setInt(COSName.HEIGHT, H);
            stream.setInt(COSName.BITS_PER_COMPONENT, 8);
            stream.setItem(COSName.FILTER, COSName.JPX_DECODE);
            try (OutputStream os = stream.createRawOutputStream()) {
                os.write(bytes.toByteArray());
            }
            assertConvertsLikePdfBox(doc, stream, LineArt.Options.defaults().method(LineArt.Method.OTSU), false);
            assertEquals(COSName.DEVICEGRAY, stream.getDictionaryObject(COSName.COLORSPACE));
        }
    }

    @Test
    void cmykJpegWithAdobeInversionConverts() throws IOException {
        WritableRaster raster = java.awt.image.Raster.createInterleavedRaster(java.awt.image.DataBuffer.TYPE_BYTE, W, H,
                4, null);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int k = (int) Math.round(darkness(x, y) * 255);
                raster.setPixel(x, y, new int[] {255, 255, 255 - k / 5, 255 - k});
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(raster, null, null), null);
        }
        try (PDDocument doc = new PDDocument()) {
            COSStream stream = doc.getDocument().createCOSStream();
            stream.setItem(COSName.TYPE, COSName.XOBJECT);
            stream.setItem(COSName.SUBTYPE, COSName.IMAGE);
            stream.setInt(COSName.WIDTH, W);
            stream.setInt(COSName.HEIGHT, H);
            stream.setInt(COSName.BITS_PER_COMPONENT, 8);
            stream.setItem(COSName.COLORSPACE, COSName.DEVICECMYK);
            stream.setItem(COSName.DECODE, numbers(1, 0, 1, 0, 1, 0, 1, 0));
            stream.setItem(COSName.FILTER, COSName.DCT_DECODE);
            try (OutputStream os = stream.createRawOutputStream()) {
                os.write(bytes.toByteArray());
            }
            assertConvertsLikePdfBox(doc, stream, LineArt.Options.defaults().method(LineArt.Method.FIXED), false);
        }
    }

    @Test
    void groupFourAndJbig2InputsConvertThroughTheStreamedPath() throws IOException {
        Bitmap bits = LineArt.binarize(picture(BufferedImage.TYPE_INT_RGB), LineArt.Options.defaults().method(LineArt.Method.FIXED));
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject g4 = CCITTFactory.createFromImage(doc, bits.toImage());
            assertConvertsLikePdfBox(doc, g4.getCOSObject(), LineArt.Options.imageMagickEdges(1), false);
        }
        try (PDDocument doc = new PDDocument()) {
            COSStream stream = doc.getDocument().createCOSStream();
            stream.setItem(COSName.TYPE, COSName.XOBJECT);
            stream.setItem(COSName.SUBTYPE, COSName.IMAGE);
            stream.setInt(COSName.WIDTH, W);
            stream.setInt(COSName.HEIGHT, H);
            stream.setInt(COSName.BITS_PER_COMPONENT, 1);
            stream.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
            stream.setItem(COSName.FILTER, COSName.JBIG2_DECODE);
            try (OutputStream os = stream.createRawOutputStream()) {
                os.write(Jbig2Generic.encode(bits));
            }
            assertConvertsLikePdfBox(doc, stream, LineArt.Options.imageMagickEdges(2), false);
        }
    }

    @Test
    void bandedStreamsMatchForEveryDepth() throws IOException {
        for (Arguments a : kinds().toList()) {
            Kind kind = (Kind) a.get()[0];
            try (PDDocument doc = new PDDocument()) {
                COSStream stream = sampled(doc, kind);
                PDImageXObject image = new PDImageXObject(new PDStream(stream), null);
                for (LineArt.Method method : LineArt.Method.values()) {
                    LineArt.Options o = LineArt.Options.defaults().method(method).window(15);
                    Bitmap whole = Binarizer.binarize(Source.of(image, null, false), o, Long.MAX_VALUE);
                    Bitmap banded = Binarizer.binarize(Source.of(image, null, false), o, W * 4L);
                    assertEquals(whole, banded, kind + " " + method);
                }
            }
        }
    }

    static void assertConvertsLikePdfBox(PDDocument doc, COSStream stream, LineArt.Options options, boolean unblend)
            throws IOException {
        PDImageXObject before = new PDImageXObject(new PDStream(stream), null);
        BufferedImage original = unblend ? before.getImage() : before.getOpaqueImage(null, 1);
        Bitmap expected = LineArt.binarize(original, options);
        long ink = expected.inkCount();
        assertTrue(ink > 0 && ink < (long) expected.width() * expected.height(), "expected both ink and paper");
        PDPage page = new PDPage(new PDRectangle(W, H));
        doc.addPage(page);
        PDResources resources = new PDResources();
        resources.getCOSObject().setItem(COSName.XOBJECT, new COSDictionary());
        resources.getCOSObject().getCOSDictionary(COSName.XOBJECT).setItem(COSName.getPDFName("Im0"), stream);
        page.setResources(resources);
        LineArt.Result result = LineArt.convert(doc, options);
        assertEquals(1, result.converted(), result.toString());
        PDImageXObject after = new PDImageXObject(new PDStream(stream), null);
        assertEquals(1, after.getBitsPerComponent());
        BufferedImage decoded = after.getOpaqueImage(null, 1);
        for (int y = 0; y < expected.height(); y++) {
            for (int x = 0; x < expected.width(); x++) {
                int rgb = decoded.getRGB(x, y) & 0xFFFFFF;
                boolean dark = options.inkColour() ? rgb != 0xFFFFFF : Pictures.dark(rgb);
                assertEquals(expected.ink(x, y), dark, "at " + x + "," + y);
            }
        }
    }

    private static COSStream sampled(PDDocument doc, Kind kind) throws IOException {
        int max = (1 << kind.bits()) - 1;
        int rowBits = W * kind.components() * kind.bits();
        byte[] data = new byte[(rowBits + 7) / 8 * H];
        int stride = (rowBits + 7) / 8;
        for (int y = 0; y < H; y++) {
            long bit = (long) y * stride * 8;
            for (int x = 0; x < W; x++) {
                int[] v = kind.sampler().sample(darkness(x, y), max);
                for (int c = 0; c < kind.components(); c++) {
                    put(data, bit, kind.bits(), v[c]);
                    bit += kind.bits();
                }
            }
        }
        COSStream stream = image(doc, kind.colourSpace(), kind.bits(), data);
        if (kind.decode() != null) {
            stream.setItem(COSName.DECODE, numbers(kind.decode()));
        }
        return stream;
    }

    private static void put(byte[] data, long bit, int bits, int value) {
        for (int i = bits - 1; i >= 0; i--) {
            if (((value >>> i) & 1) != 0) {
                data[(int) (bit >>> 3)] |= (byte) (0x80 >>> (bit & 7));
            }
            bit++;
        }
    }

    private static COSStream image(PDDocument doc, COSBase colourSpace, int bits, byte[] data) throws IOException {
        COSStream stream = doc.getDocument().createCOSStream();
        stream.setItem(COSName.TYPE, COSName.XOBJECT);
        stream.setItem(COSName.SUBTYPE, COSName.IMAGE);
        stream.setInt(COSName.WIDTH, W);
        stream.setInt(COSName.HEIGHT, H);
        stream.setInt(COSName.BITS_PER_COMPONENT, bits);
        stream.setItem(COSName.COLORSPACE, colourSpace);
        write(stream, data);
        return stream;
    }

    private static void write(COSStream stream, byte[] data) throws IOException {
        try (OutputStream os = stream.createOutputStream(COSName.FLATE_DECODE)) {
            os.write(data);
        }
    }

    static BufferedImage picture(int type) {
        BufferedImage image = new BufferedImage(W, H, type);
        IntBinaryOperator tone = (x, y) -> (int) Math.round((1 - darkness(x, y)) * 255);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int v = tone.applyAsInt(x, y);
                image.setRGB(x, y, v << 16 | v << 8 | v);
            }
        }
        return image;
    }

    private static byte[] greyRamp(int n) {
        byte[] ramp = new byte[n];
        for (int i = 0; i < n; i++) {
            ramp[i] = (byte) (i * 255 / (n - 1));
        }
        return ramp;
    }

    static COSArray array(COSBase... items) {
        COSArray a = new COSArray();
        for (COSBase b : items) {
            a.add(b);
        }
        return a;
    }

    static COSArray numbers(double... values) {
        COSArray a = new COSArray();
        for (double v : values) {
            a.add(new COSFloat((float) v));
        }
        return a;
    }

    private static COSArray icc(int space, int n) throws IOException {
        COSStream profile = new COSStream();
        profile.setInt(COSName.N, n);
        try (OutputStream os = profile.createOutputStream(COSName.FLATE_DECODE)) {
            os.write(ICC_Profile.getInstance(space).getData());
        }
        return array(COSName.ICCBASED, profile);
    }

    private static COSArray separation() {
        COSDictionary tint = new COSDictionary();
        tint.setInt(COSName.FUNCTION_TYPE, 2);
        tint.setItem(COSName.DOMAIN, numbers(0, 1));
        tint.setItem(COSName.C0, numbers(0, 0, 0, 0));
        tint.setItem(COSName.C1, numbers(0.9, 0.6, 0, 0.3));
        tint.setInt(COSName.N, 1);
        return array(COSName.SEPARATION, COSName.getPDFName("Spot"), COSName.DEVICECMYK, tint);
    }

    private static COSArray deviceN() throws IOException {
        COSStream function = new COSStream();
        function.setInt(COSName.FUNCTION_TYPE, 4);
        function.setItem(COSName.DOMAIN, numbers(0, 1, 0, 1));
        function.setItem(COSName.RANGE, numbers(0, 1, 0, 1, 0, 1, 0, 1));
        try (OutputStream os = function.createOutputStream()) {
            os.write("{ 0 exch 0 exch }".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        return array(COSName.DEVICEN, array(COSName.getPDFName("Cyan"), COSName.getPDFName("Black")), COSName.DEVICECMYK, function);
    }
}
