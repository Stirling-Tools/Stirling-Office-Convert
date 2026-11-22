package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.Deflater;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.BrotliBudget;

class RecoderTest {

    private static byte[] sample() {
        Random r = new Random(5);
        StringBuilder b = new StringBuilder();
        while (b.length() < 200_000) {
            b.append("BT /F1 ").append(r.nextInt(30)).append(" Tf ").append(r.nextInt(600)).append(' ')
                    .append(r.nextInt(800)).append(" Td (word").append(r.nextInt(50)).append(") Tj ET\n");
        }
        return b.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static Recoder.Plan plan(byte[] raw, COSName filter) throws Exception {
        try (COSStream s = new COSStream()) {
            if (filter != null) {
                s.setItem(COSName.FILTER, filter);
            }
            try (OutputStream out = s.createRawOutputStream()) {
                out.write(raw);
            }
            Recoder.Job job = Recoder.prepare(s, Recoder.Kind.OTHER, null, BrotliBudget.create());
            return Recoder.plan(job, PdfOptimizer.Effort.DEFAULT);
        }
    }

    @Test
    void aCodecStreamGetsOnlyThePlainZlibPassInFrontOfIt() throws Exception {
        byte[] raw = sample();
        assertTrue(Deflate.best(raw, PdfOptimizer.Effort.DEFAULT).length
                < Deflate.zlib(raw, 6, Deflater.DEFAULT_STRATEGY).length);
        Recoder.Plan p = plan(raw, COSName.DCT_DECODE);
        assertTrue(p.recoded());
        assertArrayEquals(Deflate.zlib(raw, 6, Deflater.DEFAULT_STRATEGY), p.data());
        COSArray filters = (COSArray) p.replace().get(COSName.FILTER);
        assertEquals(COSName.FLATE_DECODE, filters.get(0));
        assertEquals(COSName.DCT_DECODE, filters.get(1));
    }

    @Test
    void aPlainStreamStillGetsTheOptimalParse() throws Exception {
        byte[] raw = sample();
        Recoder.Plan p = plan(raw, null);
        assertArrayEquals(Deflate.best(raw, PdfOptimizer.Effort.DEFAULT), p.data());
    }

    @Test
    void sizedReadsMatchReadNBytesWhateverTheDeclaredLength() throws Exception {
        byte[] data = sample();
        for (int length : new int[] {0, 1, 5000, data.length}) {
            byte[] stream = java.util.Arrays.copyOf(data, length);
            for (int limit : new int[] {1, 4999, 5000, 5001, data.length + 1}) {
                for (long declared : new long[] {-1, 0, 1, length - 1L, length, length + 1L, Long.MAX_VALUE}) {
                    byte[] expected = new java.io.ByteArrayInputStream(stream).readNBytes(limit);
                    byte[] actual = Recoder.upTo(new java.io.ByteArrayInputStream(stream), limit, declared);
                    assertArrayEquals(expected, actual, length + " bytes, limit " + limit + ", declared " + declared);
                }
            }
        }
    }
}
