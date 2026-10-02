package stirling.software.officeconvert.jpx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JpxLosslessTest {

    private static JpxRaster decode(byte[] data) throws IOException {
        return JpxDecoder.decode(data).raster();
    }

    private static void assertSource(JpxRaster r, int components) {
        assertEquals(components, r.components());
        for (int c = 0; c < components; c++) {
            assertEquals(JpxSamples.WIDTH, r.width(c));
            assertEquals(JpxSamples.HEIGHT, r.height(c));
            for (int y = 0; y < JpxSamples.HEIGHT; y++) {
                for (int x = 0; x < JpxSamples.WIDTH; x++) {
                    assertEquals(JpxSamples.value(x, y, c), r.sample(c, x, y), "c" + c + " at " + x + "," + y);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rgb-lossless.j2k", "rgb-lrcp.j2k", "rgb-rlcp.j2k", "rgb-rpcl.j2k", "rgb-pcrl.j2k",
            "rgb-cprl.j2k", "rgb-poc.j2k", "rgb-allmodes.j2k", "rgb-bypass.j2k", "rgb-termall.j2k",
            "rgb-markers.j2k", "rgb-sop-eph.j2k", "rgb.jp2"})
    void reversibleCodestreamsDecodeToTheSourcePixels(String name) throws IOException {
        assertSource(decode(JpxSamples.resource(name)), 3);
    }

    @Test
    void greyscaleAndRegionOfInterestDecodeExactly() throws IOException {
        assertSource(decode(JpxSamples.resource("gray-lossless.j2k")), 1);
        assertSource(decode(JpxSamples.resource("gray-roi.j2k")), 1);
    }

    @Test
    void packedPacketHeadersInTilePartsMatchInterleavedHeaders() throws IOException {
        byte[] original = JpxSamples.resource("rgb-sop-eph.j2k");
        assertSource(decode(PackedHeaders.toPpt(original)), 3);
    }

    @Test
    void packedPacketHeadersInTheMainHeaderMatchInterleavedHeaders() throws IOException {
        byte[] original = JpxSamples.resource("rgb-sop-eph.j2k");
        assertSource(decode(PackedHeaders.toPpm(original)), 3);
    }

    @Test
    void imageAndTileOffsetsPlaceEveryTile() throws IOException {
        JpxRaster r = decode(JpxSamples.resource("rgb-tiles-offset.j2k"));
        assertSource(r, 3);
    }

    @Test
    void sixteenAndTwelveBitSamplesKeepTheirPrecision() throws IOException {
        JpxRaster r16 = decode(JpxSamples.resource("gray16.j2k"));
        JpxRaster r12 = decode(JpxSamples.resource("gray12.j2k"));
        assertEquals(16, r16.depth(0));
        assertEquals(12, r12.depth(0));
        for (int y = 0; y < JpxSamples.HEIGHT; y++) {
            for (int x = 0; x < JpxSamples.WIDTH; x++) {
                assertEquals(JpxSamples.value16(x, y), r16.sample(0, x, y));
                assertEquals(JpxSamples.value12(x, y), r12.sample(0, x, y));
            }
        }
    }

    @Test
    void subsampledComponentsKeepTheirOwnSize() throws IOException {
        JpxRaster r = decode(JpxSamples.resource("yuv420.j2k"));
        assertEquals(60, r.width(0));
        assertEquals(30, r.width(1));
        assertEquals(23, r.height(2));
        for (int y = 0; y < 46; y++) {
            for (int x = 0; x < 60; x++) {
                assertEquals(JpxSamples.value(x, y, 0), r.sample(0, x, y));
                assertEquals(JpxSamples.value(x & ~1, y & ~1, 1), r.imageSample(1, x, y));
                assertEquals(JpxSamples.value(x & ~1, y & ~1, 2), r.imageSample(2, x, y));
            }
        }
    }
}
