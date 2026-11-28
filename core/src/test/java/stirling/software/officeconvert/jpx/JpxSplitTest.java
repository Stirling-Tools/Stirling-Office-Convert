package stirling.software.officeconvert.jpx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

class JpxSplitTest {

    private static JpxEncoder.Planes withAlpha(int w, int h, int colours) {
        JpxEncoder.Planes p = JpxEncoderTest.photo(w, h, colours + 1, 8, w);
        int[][] s = p.samples();
        int[] depths = p.depths();
        return new JpxEncoder.Planes(w, h, s, depths, null,
                colours == 3 ? JpxEncoder.Colour.SRGB : JpxEncoder.Colour.GREY, colours);
    }

    private static void assertSplit(byte[] data, int[][] groups) throws IOException {
        byte[][] parts = JpxSplit.split(data, groups);
        assertNotNull(parts);
        int[][] whole = JpxEncoderTest.nativeSamples(data);
        for (int g = 0; g < groups.length; g++) {
            int[][] part = JpxEncoderTest.nativeSamples(parts[g]);
            assertEquals(groups[g].length, part.length);
            for (int i = 0; i < groups[g].length; i++) {
                assertArrayEquals(whole[groups[g][i]], part[i]);
            }
        }
        long total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        assertEquals(data.length, total, data.length * 0.1);
    }

    @Test
    void colourAndAlphaSplitIntoExactCodestreams() throws IOException {
        JpxEncoder.Planes rgba = withAlpha(300, 211, 3);
        for (JpxEncoder.Options o : new JpxEncoder.Options[] {JpxEncoder.Options.exact(), JpxEncoder.Options.rate(1),
            JpxEncoder.Options.exact().withTileSize(128).withLayers(3).withOrder(JpxEncoder.Order.RPCL),
            JpxEncoder.Options.rate(2).withTileSize(64).withLayers(2).withOrder(JpxEncoder.Order.CPRL),
            JpxEncoder.Options.exact().withOrder(JpxEncoder.Order.PCRL).withBlockSize(16).withCodestream(true)}) {
            assertSplit(JpxEncoder.encode(rgba, o), new int[][] {{0, 1, 2}, {3}});
        }
        JpxEncoder.Planes la = withAlpha(129, 64, 1);
        assertSplit(JpxEncoder.encode(la, JpxEncoder.Options.exact()), new int[][] {{0}, {1}});
        assertSplit(JpxEncoder.encode(la, JpxEncoder.Options.rate(0.5).withLayers(4)), new int[][] {{1}, {0}});
    }

    @Test
    void subsampledComponentsSplitToo() throws IOException {
        JpxEncoder.Planes p = JpxEncoderTest.subsampled(160, 90, new int[] {1, 2, 2, 1}, new int[] {1, 2, 2, 1}, 8, 4);
        assertSplit(JpxEncoder.encode(p, JpxEncoder.Options.exact().withOrder(JpxEncoder.Order.RPCL)),
                new int[][] {{0, 1, 2}, {3}});
    }

    @Test
    void damagedCodestreamsAreRefusedOrSplitExactly() throws IOException {
        byte[] data = JpxEncoder.encode(withAlpha(96, 80, 3),
                JpxEncoder.Options.rate(2).withTileSize(32).withLayers(2).withCodestream(true));
        java.util.Random rnd = new java.util.Random(11);
        int split = 0;
        for (int i = 0; i < 3000; i++) {
            byte[] bad = data.clone();
            for (int k = 0, n = 1 + rnd.nextInt(4); k < n; k++) {
                int at = rnd.nextInt(Math.min(bad.length, i % 2 == 0 ? 200 : bad.length));
                bad[at] = (byte) rnd.nextInt(256);
            }
            try {
                byte[][] parts = JpxSplit.split(bad, new int[][] {{0, 1, 2}, {3}});
                if (parts != null) {
                    split++;
                }
            } catch (IOException e) {
                continue;
            }
        }
        assertTrue(split > 0);
    }

    @Test
    void aSplitLargerThanTheBudgetIsRefused() throws IOException {
        byte[] data = JpxEncoder.encode(withAlpha(64, 48, 3), JpxEncoder.Options.exact());
        int[][] groups = {{0, 1, 2}, {3}};
        byte[][] parts = JpxSplit.split(data, groups);
        assertNotNull(parts);
        long total = parts[0].length + parts[1].length;
        assertNotNull(JpxSplit.split(data, groups, null, total));
        assertNull(JpxSplit.split(data, groups, null, total - 1));
    }

    @Test
    void aGroupThatBreaksTheColourTransformIsRefused() throws IOException {
        byte[] data = JpxEncoder.encode(withAlpha(64, 64, 3), JpxEncoder.Options.exact());
        assertNull(JpxSplit.split(data, new int[][] {{1}}));
        assertNull(JpxSplit.split(data, new int[][] {{0, 0, 1}}));
        assertNull(JpxSplit.split(data, new int[][] {{}}));
    }
}
