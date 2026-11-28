package stirling.software.officeconvert.jpx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.junit.jupiter.api.Test;

class DecodeScratchTest {

    @Test
    void reusedBuffersDecodeExactlyLikeFreshOnes() throws IOException {
        TileDecoder.Scratch scratch = new TileDecoder.Scratch();
        JpxEncoder.Options[] options = {JpxEncoder.Options.quality(34), JpxEncoder.Options.rate(0.3),
            JpxEncoder.Options.exact(), JpxEncoder.Options.rate(1).withTileSize(48), JpxEncoder.Options.quality(30)};
        for (int round = 0; round < 2; round++) {
            for (JpxEncoder.Options o : options) {
                JpxEncoder.Planes p = JpxEncoderTest.photo(round == 0 ? 130 : 97, 75, 3, 8, round);
                byte[] data = JpxEncoder.encode(p, o);
                JpxRaster a = JpxDecoder.decode(data).raster();
                JpxRaster b = JpxDecoder.decode(data, JpxOptions.defaults(), scratch).raster();
                for (int c = 0; c < a.components(); c++) {
                    for (int y = 0; y < a.height(c); y++) {
                        for (int x = 0; x < a.width(c); x++) {
                            assertEquals(a.sample(c, x, y), b.sample(c, x, y));
                        }
                    }
                }
            }
        }
    }
}
