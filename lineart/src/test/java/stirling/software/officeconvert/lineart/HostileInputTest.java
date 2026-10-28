package stirling.software.officeconvert.lineart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.DeflaterOutputStream;

import org.apache.pdfbox.cos.COSDictionary;
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
