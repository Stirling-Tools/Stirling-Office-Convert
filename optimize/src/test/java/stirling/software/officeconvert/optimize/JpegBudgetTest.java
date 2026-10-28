package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class JpegBudgetTest {

    private static byte[] picture() throws Exception {
        BufferedImage image = new BufferedImage(128, 96, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, (x * 2 << 16) | (y * 2 << 8) | (x ^ y));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }

    @Test
    void aSymbolBudgetDeclinesAValidImageWithoutChangingItsBytes() throws Exception {
        byte[] jpeg = picture();
        byte[] original = jpeg.clone();
        assertNotNull(Jpeg.optimize(jpeg));
        assertNull(Jpeg.optimize(jpeg, 32));
        assertArrayEquals(original, jpeg);
    }

    @Test
    void aSufficientSymbolBudgetRetainsIdenticalPixels() throws Exception {
        byte[] jpeg = picture();
        byte[] optimized = Jpeg.optimize(jpeg, 1 << 18);
        assertNotNull(optimized);
        BufferedImage a = ImageIO.read(new ByteArrayInputStream(jpeg));
        BufferedImage b = ImageIO.read(new ByteArrayInputStream(optimized));
        assertArrayEquals(a.getRGB(0, 0, 128, 96, null, 0, 128), b.getRGB(0, 0, 128, 96, null, 0, 128));
    }
}
