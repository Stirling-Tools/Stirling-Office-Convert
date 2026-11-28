package stirling.software.officeconvert.jpx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Random;

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
                int[][] groups = {{0, 1, 2}, {3}};
                byte[][] parts = JpxSplit.split(bad, groups);
                if (parts != null) {
                    int[][] whole = JpxEncoderTest.nativeSamples(bad);
                    for (int g = 0; g < groups.length; g++) {
                        int[][] part = JpxEncoderTest.nativeSamples(parts[g]);
                        for (int k = 0; k < groups[g].length; k++) {
                            assertArrayEquals(whole[groups[g][k]], part[k]);
                        }
                    }
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
    void aCodestreamWithComponentRegistrationIsRefused() throws IOException {
        byte[] data = JpxEncoder.encode(withAlpha(48, 40, 3), JpxEncoder.Options.exact().withCodestream(true));
        int at = 4 + ((data[4] & 0xFF) << 8 | data[5] & 0xFF);
        byte[] crg = new byte[4 + 4 * 4];
        crg[0] = (byte) 0xFF;
        crg[1] = 0x63;
        crg[3] = (byte) (crg.length - 2);
        crg[7] = 1;
        byte[] with = new byte[data.length + crg.length];
        System.arraycopy(data, 0, with, 0, at);
        System.arraycopy(crg, 0, with, at, crg.length);
        System.arraycopy(data, at, with, at + crg.length, data.length - at);
        assertNotNull(JpxEncoderTest.nativeSamples(with));
        assertNull(JpxSplit.split(with, new int[][] {{0, 1, 2}, {3}}));
        assertNotNull(JpxSplit.split(data, new int[][] {{0, 1, 2}, {3}}));
    }

    @Test
    void aGroupThatBreaksTheColourTransformIsRefused() throws IOException {
        byte[] data = JpxEncoder.encode(withAlpha(64, 64, 3), JpxEncoder.Options.exact());
        assertNull(JpxSplit.split(data, new int[][] {{1}}));
        assertNull(JpxSplit.split(data, new int[][] {{0, 0, 1}}));
        assertNull(JpxSplit.split(data, new int[][] {{}}));
    }

    private static Codestream codestream(byte[] data) throws IOException {
        Jp2Boxes boxes = Jp2Boxes.read(data);
        return Codestream.parse(data, boxes.codestreamStart, boxes.codestreamEnd);
    }

    @Test
    void partsAcceptedWithoutDecodingDecodeExactly() throws IOException {
        Random r = new Random(11);
        JpxEncoder.Options[] options = {JpxEncoder.Options.exact(), JpxEncoder.Options.rate(0.7),
            JpxEncoder.Options.quality(33).withTileSize(64), JpxEncoder.Options.exact().withLayers(3)
                    .withOrder(JpxEncoder.Order.RLCP).withBlockSize(16),
            JpxEncoder.Options.rate(1.5).withLayers(2).withOrder(JpxEncoder.Order.CPRL).withCodestream(true)};
        int accepted = 0;
        for (int k = 0; k < 20; k++) {
            boolean grey = k % 3 == 0;
            JpxEncoder.Planes p = withAlpha(40 + r.nextInt(150), 30 + r.nextInt(120), grey ? 1 : 3);
            byte[] data = JpxEncoder.encode(p, options[k % options.length]);
            int[][] groups = grey ? new int[][] {{0}, {1}} : new int[][] {{0, 1, 2}, {3}};
            byte[][] parts = JpxSplit.split(data, groups);
            assertNotNull(parts);
            if (JpxSplit.sameDecode(codestream(data), groups, parts)) {
                accepted++;
            }
            int[][] whole = JpxEncoderTest.nativeSamples(data);
            for (int g = 0; g < groups.length; g++) {
                int[][] part = JpxEncoderTest.nativeSamples(parts[g]);
                for (int i = 0; i < groups[g].length; i++) {
                    assertArrayEquals(whole[groups[g][i]], part[i]);
                }
            }
        }
        assertEquals(20, accepted);
    }

    @Test
    void aChangedPartIsNotAcceptedWithoutDecoding() throws IOException {
        byte[] data = JpxEncoder.encode(withAlpha(120, 90, 3), JpxEncoder.Options.rate(1));
        int[][] groups = {{0, 1, 2}, {3}};
        byte[][] parts = JpxSplit.split(data, groups);
        assertNotNull(parts);
        Codestream whole = codestream(data);
        assertTrue(JpxSplit.sameDecode(whole, groups, parts));
        for (int at : new int[] {parts[0].length - 40, parts[0].length / 2}) {
            byte[][] changed = {parts[0].clone(), parts[1]};
            changed[0][at] ^= 0x10;
            assertFalse(JpxSplit.sameDecode(whole, groups, changed));
        }
        byte[][] swapped = {parts[1], parts[0]};
        assertFalse(JpxSplit.sameDecode(whole, groups, swapped));
    }
}
