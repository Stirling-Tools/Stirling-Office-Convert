package stirling.software.officeconvert.photosplit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.SplittableRandom;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class PngWriterTest {

    @Test
    void colourAndGrayRoundTripExactly() throws Exception {
        SplittableRandom random = new SplittableRandom(9);
        for (int type : new int[] {BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_BYTE_GRAY}) {
            BufferedImage image = new BufferedImage(301, 97, type);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int v = random.nextInt(0x1000000);
                    image.setRGB(x, y, type == BufferedImage.TYPE_BYTE_GRAY ? (v & 0xFF) * 0x010101 : v);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PngWriter.write(image, out);
            BufferedImage back = ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if (type == BufferedImage.TYPE_BYTE_GRAY) {
                        assertEquals(image.getRaster().getSample(x, y, 0), back.getRaster().getSample(x, y, 0));
                    } else {
                        assertEquals(image.getRGB(x, y) & 0xFFFFFF, back.getRGB(x, y) & 0xFFFFFF);
                    }
                }
            }
        }
    }
}
