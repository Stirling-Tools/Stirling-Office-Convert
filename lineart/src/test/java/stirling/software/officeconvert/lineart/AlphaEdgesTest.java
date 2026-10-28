package stirling.software.officeconvert.lineart;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AlphaEdgesTest {

    private static final String[] EXPECTED = {
        "c9d2efa7cc8c3a9c2dcabd33a1fd872d5aef1fe21428f4aa29c159a6d6adad62",
        "b922ac3a88bba61ff8dabb65c8837e2dd75e6b8c33f225680949934f1461e75c",
        "09b3a2bb402bfc157569a3bed602470f6f5519a92239d5e192bdae27c649c3c2"
    };

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void transparentEdgesMatchGeneratedImageMagickMasks(int level) throws Exception {
        for (int type : new int[] {BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_4BYTE_ABGR,
                BufferedImage.TYPE_INT_ARGB_PRE}) {
            BufferedImage parent = new BufferedImage(30, 22, type);
            BufferedImage image = parent.getSubimage(3, 2, 24, 16);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 24; x++) {
                    int gray = (x / 3 + y / 2) % 3 == 0 ? 0 : 255;
                    int alpha = x >= 4 && x < 20 && y >= 3 && y < 13 ? 255 : 0;
                    image.setRGB(x, y, alpha << 24 | gray << 16 | gray << 8 | gray);
                }
            }
            Bitmap bitmap = LineArt.binarize(image, LineArt.Options.imageMagickEdges(level));
            StringBuilder bits = new StringBuilder();
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 24; x++) {
                    bits.append(bitmap.ink(x, y) ? '#' : '.');
                }
            }
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(bits.toString().getBytes(StandardCharsets.US_ASCII)));
            assertEquals(EXPECTED[level - 1], digest, "image type " + type);
        }
    }
}
