package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.Deflater;

import org.junit.jupiter.api.Test;

class SqueezeTest {

    private static final Squeeze.Settings THOROUGH = new Squeeze.Settings(10, 4096, 258, 15);

    @Test
    void everyShapeOfDataInflatesBack() throws Exception {
        Random r = new Random(9);
        byte[] noise = new byte[70_000];
        r.nextBytes(noise);
        StringBuilder text = new StringBuilder();
        while (text.length() < 300_000) {
            text.append("BT /F1 ").append(r.nextInt(30)).append(" Tf ").append(r.nextInt(600)).append(' ')
                    .append(r.nextInt(800)).append(" Td (word").append(r.nextInt(50)).append(") Tj ET\n");
        }
        byte[] mixed = new byte[1_300_000];
        for (int i = 0; i < mixed.length; i++) {
            mixed[i] = (byte) (i % 7000 < 3500 ? i / 13 : r.nextInt(4));
        }
        byte[][] samples = {new byte[0], new byte[] {42}, new byte[100_000], noise,
                text.toString().getBytes(StandardCharsets.ISO_8859_1), mixed, "abcabcabcabcabcabcabcabc".getBytes(StandardCharsets.ISO_8859_1)};
        for (byte[] s : samples) {
            byte[] z = Squeeze.zlib(s, THOROUGH);
            assertArrayEquals(s, Filters.inflate(z, Long.MAX_VALUE), "length " + s.length);
            byte[] zlib = Deflate.zlib(s, 9, Deflater.DEFAULT_STRATEGY);
            assertTrue(z.length <= zlib.length + 8, s.length + ": " + z.length + " vs zlib " + zlib.length);
        }
    }
}
