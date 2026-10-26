package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Random;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.junit.jupiter.api.Test;

class StreamedTest {

    private static byte[] picture(int w, int h) {
        byte[] b = new byte[w * h * 3];
        Random r = new Random(4);
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) ((i / 3 % w) / 9 + r.nextInt(3));
        }
        return b;
    }

    private static COSStream image(byte[] pixels, int w, int h, boolean predicted) throws Exception {
        COSStream s = new COSStream();
        s.setItem(COSName.SUBTYPE, COSName.IMAGE);
        s.setInt(COSName.WIDTH, w);
        s.setInt(COSName.HEIGHT, h);
        s.setInt(COSName.BITS_PER_COMPONENT, 8);
        s.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
        byte[] body = pixels;
        if (predicted) {
            body = Predictors.encodePng(pixels, new Predictors.Layout(3, 8, w), Predictors.SUB);
            COSDictionary parms = new COSDictionary();
            parms.setInt(COSName.PREDICTOR, 15);
            parms.setInt(COSName.COLORS, 3);
            parms.setInt(COSName.COLUMNS, w);
            s.setItem(COSName.DECODE_PARMS, parms);
        }
        try (OutputStream o = s.createRawOutputStream()) {
            o.write(Deflate.zlib(body, 1, 0));
        }
        s.setItem(COSName.FILTER, COSName.FLATE_DECODE);
        return s;
    }

    @Test
    void aLargeImageIsRecompressedRowByRowAndDecodesTheSame() throws Exception {
        int w = 300;
        int h = 200;
        byte[] pixels = picture(w, h);
        for (boolean predicted : new boolean[] {false, true}) {
            COSStream s = image(pixels, w, h, predicted);
            try (Spill spill = new Spill()) {
                Streamed.Result r = Streamed.recode(s, Recoder.Kind.IMAGE, Roles.layout(s), h, spill,
                        MessageDigest.getInstance("SHA-256"), PdfOptimizer.Effort.DEFAULT);
                assertNotNull(r);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                spill.copy(r.spilled(), out);
                assertTrue(out.size() < s.getLength());
                byte[] inflated = Filters.inflate(out.toByteArray(), Long.MAX_VALUE);
                assertArrayEquals(pixels, Predictors.decode(inflated, (COSDictionary) r.replace().get(COSName.DECODE_PARMS),
                        Long.MAX_VALUE));
            }
        }
    }

    @Test
    void aStreamThatDoesNotInflateCleanlyIsLeftAlone() throws Exception {
        COSStream s = image(picture(100, 50), 100, 50, false);
        byte[] raw;
        try (var in = s.createRawInputStream()) {
            raw = in.readAllBytes();
        }
        COSStream cut = new COSStream();
        cut.addAll(s);
        try (OutputStream o = cut.createRawOutputStream()) {
            o.write(raw, 0, raw.length - 20);
        }
        try (Spill spill = new Spill()) {
            assertNull(Streamed.recode(cut, Recoder.Kind.IMAGE, Roles.layout(cut), 50, spill,
                    MessageDigest.getInstance("SHA-256"), PdfOptimizer.Effort.FAST));
        }
    }
}
