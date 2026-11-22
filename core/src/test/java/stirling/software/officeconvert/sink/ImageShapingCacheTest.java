package stirling.software.officeconvert.sink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.NoImageCache;
import stirling.software.officeconvert.model.Picture;

class ImageShapingCacheTest {

    @Test
    void croppedPictureIsShapedWithoutImageIoDiskCache() throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", png);
        for (String type : new String[] {"image/png", "image/jpeg"}) {
            Picture pic = new Picture(new Picture.MediaRef("m", type, 40, 30), 40, 30);
            pic.rotation = 90;
            ImageShaping.Result shaped = NoImageCache.run(() -> ImageShaping.apply(png.toByteArray(), pic, false));
            assertNotNull(shaped);
            assertEquals(30, shaped.width());
        }
    }
}
