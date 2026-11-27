package stirling.software.officeconvert.jpx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

class BlockEncoderTest {

    private static int[] decode(EncodedBlock e, int w, int h, int orient, int passes, int length) {
        CodeBlock cb = new CodeBlock(0, 0, w, h);
        Segment seg = cb.segment(0, 0);
        seg.passes = passes;
        seg.add(0, length);
        cb.passes = passes;
        BlockDecoder d = new BlockDecoder();
        d.decode(cb, orient, 0, e.planes, e.data);
        return Arrays.copyOf(d.data(), w * h);
    }

    @Test
    void everyTruncationDecodesLikeTheFullStream() {
        Random rnd = new Random(42);
        BlockEncoder coder = new BlockEncoder();
        int checked = 0;
        long saved = 0;
        for (int trial = 0; trial < 400; trial++) {
            int w = 1 + rnd.nextInt(64);
            int h = 1 + rnd.nextInt(64);
            int orient = rnd.nextInt(4);
            double scale = Math.exp(rnd.nextDouble() * 8);
            int[] v = new int[w * h];
            for (int i = 0; i < v.length; i++) {
                double x = -Math.log(1 - rnd.nextDouble()) * scale * (rnd.nextInt(5) == 0 ? 4 : 0.3);
                v[i] = (int) x * (rnd.nextBoolean() ? 1 : -1);
            }
            EncodedBlock e = coder.encode(v.clone(), w, h, orient, 0, true);
            for (int p = 1; p <= e.passes(); p++) {
                int[] full = decode(e, w, h, orient, p, e.data.length);
                int[] cut = decode(e, w, h, orient, p, e.length(p));
                assertArrayEquals(full, cut, "trial " + trial + " pass " + p);
                checked++;
                saved += e.data.length - e.length(p);
            }
            if (e.passes() > 0) {
                int[] all = decode(e, w, h, orient, e.passes(), e.data.length);
                for (int i = 0; i < v.length; i++) {
                    assertTrue(Math.abs(all[i] / 2) == Math.abs(v[i]), "lossless value " + i);
                }
            }
        }
        assertTrue(checked > 1000 && saved > 0);
    }
}
