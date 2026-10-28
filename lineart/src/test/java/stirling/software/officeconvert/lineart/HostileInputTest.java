package stirling.software.officeconvert.lineart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.DeflaterOutputStream;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

class HostileInputTest {

    @Test
    void anImageWithRowsTooWideForTheBandBudgetIsLeftWithAWarning() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            COSStream stream = greyImage(doc, 2_000_000, 3);
            LineArt.Result result = LineArt.convert(doc, LineArt.Options.defaults().method(LineArt.Method.FIXED));
            assertEquals(0, result.converted(), result.toString());
            assertEquals(1, result.warnings().size(), result.toString());
            assertTrue(result.warnings().get(0).contains("2000000x3"), result.toString());
            assertEquals(COSName.FLATE_DECODE, stream.getDictionaryObject(COSName.FILTER));
            assertEquals(8, stream.getInt(COSName.BITS_PER_COMPONENT));
        }
    }

    @Test
    void anInlineImageOverTheBandBudgetIsLeftWithAWarning() throws IOException {
        byte[] samples = new byte[2_000_000 * 3];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (byte) (i % 7 == 0 ? 0 : 255);
        }
        try (PDDocument doc = new PDDocument()) {
            COSStream content = inlinePage(doc, "BI /W 2000000 /H 3 /CS /G /BPC 8 /F [/AHx /Fl] ID "
                    + HexFormat.of().formatHex(deflate(samples)) + ">\nEI Q\n");
            String before = read(content);
            LineArt.Result result = LineArt.convert(doc, LineArt.Options.defaults().method(LineArt.Method.FIXED));
            assertEquals(1, result.images(), result.toString());
            assertEquals(0, result.converted(), result.toString());
            assertEquals(1, result.warnings().size(), result.toString());
            assertEquals(before, read(content));
        }
    }

    @Test
    void anInlineImageWhoseDataInflatesFarPastItsSizeIsLeftWithAWarning() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            COSStream content = inlinePage(doc, "BI /W 10 /H 10 /CS /G /BPC 8 /F [/AHx /Fl] ID "
                    + HexFormat.of().formatHex(deflate(new byte[64 << 20])) + ">\nEI Q\n");
            String before = read(content);
            LineArt.Result result = LineArt.convert(doc, LineArt.Options.defaults().method(LineArt.Method.FIXED));
            assertEquals(0, result.converted(), result.toString());
            assertEquals(1, result.warnings().size(), result.toString());
            assertEquals(before, read(content));
        }
    }

    @Test
    void aContentStreamOverTheSizeCapIsLeftWithAWarning() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            COSStream content = doc.getDocument().createCOSStream();
            try (OutputStream os = content.createOutputStream(COSName.FLATE_DECODE)) {
                byte[] spaces = new byte[1 << 20];
                Arrays.fill(spaces, (byte) ' ');
                for (int i = 0; i < 40; i++) {
                    os.write(spaces);
                }
                os.write("q 8 0 0 8 0 0 cm\nBI /W 8 /H 8 /CS /G /BPC 8 ID ".getBytes(StandardCharsets.ISO_8859_1));
                for (int i = 0; i < 64; i++) {
                    os.write(i % 2 == 0 ? 0 : 255);
                }
                os.write("\nEI Q\n".getBytes(StandardCharsets.ISO_8859_1));
            }
            PDPage page = new PDPage(new PDRectangle(8, 8));
            doc.addPage(page);
            page.setResources(new PDResources());
            page.getCOSObject().setItem(COSName.CONTENTS, content);
            LineArt.Result result = LineArt.convert(doc, LineArt.Options.defaults().method(LineArt.Method.FIXED));
            assertEquals(0, result.converted(), result.toString());
            assertEquals(1, result.warnings().size(), result.toString());
            assertTrue(result.warnings().get(0).contains("content stream"), result.toString());
        }
    }

    @Test
    void aDecoderFailureInABandedImageIsAWarningNotABlackImage() throws IOException {
        COSStream failing = new COSStream() {
            @Override
            public InputStream createRawInputStream() throws IOException {
                InputStream raw = super.createRawInputStream();
                long fail = getLength() / 3;
                return new FilterInputStream(raw) {
                    private long served;

                    @Override
                    public int read() throws IOException {
                        byte[] one = new byte[1];
                        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
                    }

                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        if (served >= fail) {
                            throw new IllegalStateException("decoder failed");
                        }
                        int n = super.read(b, off, (int) Math.min(len, fail - served));
                        served += Math.max(0, n);
                        return n;
                    }
                };
            }
        };
        try (PDDocument doc = new PDDocument()) {
            COSStream stream = greyImage(doc, failing, 2_500, 2_000);
            LineArt.Result result = LineArt.convert(doc, LineArt.Options.defaults().method(LineArt.Method.FIXED));
            assertEquals(0, result.converted(), result.toString());
            assertEquals(1, result.warnings().size(), result.toString());
            assertTrue(result.warnings().get(0).contains("decoder failed"), result.toString());
            assertEquals(8, stream.getInt(COSName.BITS_PER_COMPONENT));
        }
    }

    @Test
    void aDecoderFailureIsSeenEvenWhenClosingTheSourceIsSlow() throws IOException {
        COSStream failing = new COSStream() {
            @Override
            public InputStream createRawInputStream() throws IOException {
                InputStream raw = super.createRawInputStream();
                long fail = getLength() / 3;
                return new FilterInputStream(raw) {
                    private long served;

                    @Override
                    public int read() throws IOException {
                        byte[] one = new byte[1];
                        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
                    }

                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        if (served >= fail) {
                            throw new IllegalStateException("decoder failed");
                        }
                        int n = super.read(b, off, (int) Math.min(len, fail - served));
                        served += Math.max(0, n);
                        return n;
                    }

                    @Override
                    public void close() throws IOException {
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        super.close();
                    }
                };
            }
        };
        try (PDDocument doc = new PDDocument()) {
            greyImage(doc, failing, 2_500, 2_000);
            LineArt.Result result = LineArt.convert(doc, LineArt.Options.defaults().method(LineArt.Method.FIXED));
            assertEquals(0, result.converted(), result.toString());
            assertTrue(result.warnings().get(0).contains("decoder failed"), result.toString());
        }
    }

    @Test
    void anImageWhoseFilterChainInflatesFarPastItsSizeIsLeftWithoutDecodingItAll() throws IOException {
        byte[] runs = new byte[1 << 21];
        for (int i = 0; i < runs.length; i += 2) {
            runs[i] = (byte) 0x81;
        }
        try (PDDocument doc = new PDDocument()) {
            COSStream stream = greyImage(doc, 64, 64);
            chain(stream, deflate(runs), COSName.RUN_LENGTH_DECODE, null);
            long allocated = allocatedDuring(() -> {
                LineArt.Result result = LineArt.convert(doc, LineArt.Options.defaults().method(LineArt.Method.FIXED));
                assertEquals(0, result.converted(), result.toString());
                assertEquals(1, result.warnings().size(), result.toString());
                assertTrue(result.warnings().get(0).contains("64x64"), result.toString());
                assertTrue(result.warnings().get(0).contains("decodes to far more data"), result.toString());
            });
            assertTrue(allocated < 32 << 20, "allocated " + allocated + " bytes decoding a 4 KB picture");
            assertEquals(8, stream.getInt(COSName.BITS_PER_COMPONENT));
        }
    }

    @Test
    void aFaxImageBehindAnInflatingFilterIsDecodedWithoutBufferingThePadding() throws IOException {
        int w = 640;
        int h = 480;
        Bitmap bitmap = Bitmap.blank(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((x / 9 + y / 7) % 5 == 0) {
                    bitmap.set(x, y);
                }
            }
        }
        Codecs.Encoded g4 = Codecs.g4(bitmap);
        byte[] padded = Arrays.copyOf(g4.data(), g4.data().length + (128 << 20));
        try (PDDocument doc = new PDDocument()) {
            COSStream stream = greyImage(doc, w, h);
            stream.setInt(COSName.BITS_PER_COMPONENT, 1);
            chain(stream, deflate(padded), COSName.CCITTFAX_DECODE, g4.parameters());
            long allocated = allocatedDuring(() -> {
                LineArt.Result result = LineArt.convert(doc, LineArt.Options.defaults().method(LineArt.Method.FIXED));
                assertTrue(result.warnings().isEmpty(), result.toString());
            });
            assertTrue(allocated < 48 << 20, "allocated " + allocated + " bytes decoding a 38 KB picture");
        }
    }

    @Test
    void aJbig2ImageDeclaringAHugePageIsLeftWithoutAllocatingIt() throws IOException {
        ByteArrayOutputStream jbig2 = new ByteArrayOutputStream();
        jbig2.writeBytes(new byte[] {0, 0, 0, 0, 0x30, 0, 1, 0, 0, 0, 19});
        jbig2.writeBytes(new byte[] {0, 0, (byte) 0xEA, 0x60, 0, 0, (byte) 0xEA, 0x60});
        jbig2.writeBytes(new byte[11]);
        jbig2.writeBytes(new byte[] {0, 0, 0, 1, 0x31, 0, 1, 0, 0, 0, 0});
        try (PDDocument doc = new PDDocument()) {
            COSStream stream = greyImage(doc, 64, 64);
            stream.setInt(COSName.BITS_PER_COMPONENT, 1);
            try (OutputStream os = stream.createRawOutputStream()) {
                os.write(jbig2.toByteArray());
            }
            stream.setItem(COSName.FILTER, COSName.JBIG2_DECODE);
            long allocated = allocatedDuring(() -> {
                LineArt.Result result = LineArt.convert(doc, LineArt.Options.defaults().method(LineArt.Method.FIXED));
                assertEquals(0, result.converted(), result.toString());
                assertEquals(1, result.warnings().size(), result.toString());
                assertTrue(result.warnings().get(0).contains("decodes to far more data"), result.toString());
            });
            assertTrue(allocated < 32 << 20, "allocated " + allocated + " bytes decoding a 512-byte picture");
        }
    }

    private static COSStream greyImage(PDDocument doc, int w, int h) throws IOException {
        return greyImage(doc, doc.getDocument().createCOSStream(), w, h);
    }

    private static COSStream greyImage(PDDocument doc, COSStream stream, int w, int h) throws IOException {
        stream.setItem(COSName.TYPE, COSName.XOBJECT);
        stream.setItem(COSName.SUBTYPE, COSName.IMAGE);
        stream.setInt(COSName.WIDTH, w);
        stream.setInt(COSName.HEIGHT, h);
        stream.setInt(COSName.BITS_PER_COMPONENT, 8);
        stream.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
        byte[] row = new byte[w];
        try (OutputStream os = stream.createOutputStream(COSName.FLATE_DECODE)) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    row[x] = (byte) ((x / 13 + y) % 17 == 0 ? 20 : 230);
                }
                os.write(row);
            }
        }
        PDPage page = new PDPage(new PDRectangle(200, 200));
        doc.addPage(page);
        PDResources resources = new PDResources();
        COSDictionary xobjects = new COSDictionary();
        xobjects.setItem(COSName.getPDFName("Im0"), stream);
        resources.getCOSObject().setItem(COSName.XOBJECT, xobjects);
        page.setResources(resources);
        COSStream content = new COSStream();
        try (OutputStream os = content.createOutputStream()) {
            os.write("q 200 0 0 200 0 0 cm /Im0 Do Q".getBytes(StandardCharsets.ISO_8859_1));
        }
        page.getCOSObject().setItem(COSName.CONTENTS, content);
        return stream;
    }

    private static COSStream inlinePage(PDDocument doc, String image) throws IOException {
        COSStream content = doc.getDocument().createCOSStream();
        try (OutputStream os = content.createOutputStream()) {
            os.write(("q 100 0 0 100 0 0 cm\n" + image).getBytes(StandardCharsets.ISO_8859_1));
        }
        PDPage page = new PDPage(new PDRectangle(100, 100));
        doc.addPage(page);
        page.setResources(new PDResources());
        page.getCOSObject().setItem(COSName.CONTENTS, content);
        return content;
    }

    private interface Run {
        void run() throws IOException;
    }

    private static long allocatedDuring(Run run) throws IOException {
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getTotalThreadAllocatedBytes();
        run.run();
        return threads.getTotalThreadAllocatedBytes() - before;
    }

    private static void chain(COSStream stream, byte[] raw, COSName second, COSDictionary parameters)
            throws IOException {
        try (OutputStream os = stream.createRawOutputStream()) {
            os.write(raw);
        }
        COSArray filters = new COSArray();
        filters.add(COSName.FLATE_DECODE);
        filters.add(second);
        stream.setItem(COSName.FILTER, filters);
        if (parameters != null) {
            COSArray parms = new COSArray();
            parms.add(COSNull.NULL);
            parms.add(parameters);
            stream.setItem(COSName.DECODE_PARMS, parms);
        }
    }

    private static byte[] deflate(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStream os = new DeflaterOutputStream(out)) {
            os.write(data);
        }
        return out.toByteArray();
    }

    private static String read(COSStream stream) throws IOException {
        try (InputStream in = stream.createInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
