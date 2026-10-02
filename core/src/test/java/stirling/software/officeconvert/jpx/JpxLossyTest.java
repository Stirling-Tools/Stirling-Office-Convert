package stirling.software.officeconvert.jpx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.junit.jupiter.api.Test;

class JpxLossyTest {

    private static JpxImage decode(String name, int reduce) throws IOException {
        return JpxDecoder.decode(JpxSamples.resource(name), JpxOptions.defaults().withReduce(reduce));
    }

    @Test
    void irreversibleDecodeMatchesOpenJpegBitForBit() throws IOException {
        assertEquals(0xc4f5e459L, JpxSamples.crc(decode("rgb-97.j2k", 0).raster()));
        assertEquals(0xc8f44a43L, JpxSamples.crc(decode("rgb-97-layers.j2k", 0).raster()));
    }

    @Test
    void reducedResolutionMatchesOpenJpeg() throws IOException {
        JpxImage half = decode("rgb-lossless.j2k", 1);
        assertEquals(1, half.reduce());
        assertEquals(31, half.raster().width());
        assertEquals(24, half.raster().height());
        assertEquals(0xef9c46efL, JpxSamples.crc(half.raster()));
        assertEquals(0xfc4cb95cL, JpxSamples.crc(decode("rgb-lossless.j2k", 2).raster()));
        assertEquals(0x7d406822L, JpxSamples.crc(decode("rgb-97.j2k", 1).raster()));
        assertEquals(0x1776aceaL, JpxSamples.crc(decode("rgb-tiles-offset.j2k", 1).raster()));
    }

    @Test
    void reductionStopsAtTheLowestResolution() throws IOException {
        JpxImage img = decode("rgb-lrcp.j2k", 9);
        assertEquals(2, img.reduce());
        assertEquals(16, img.raster().width());
    }
}
