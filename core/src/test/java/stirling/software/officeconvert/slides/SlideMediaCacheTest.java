package stirling.software.officeconvert.slides;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.NoImageCache;
import stirling.software.officeconvert.build.PlacedContent;
import stirling.software.officeconvert.model.Picture;

class SlideMediaCacheTest {

    @Test
    void photoPngIsRecodedWithoutImageIoDiskCache() throws Exception {
        BufferedImage noise = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(1);
        for (int y = 0; y < 600; y++) {
            for (int x = 0; x < 600; x++) {
                noise.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(noise, "png", png);
        List<String> stored = new ArrayList<>();
        PlacedContent.MediaStore store = new PlacedContent.MediaStore() {
            @Override
            public Picture.MediaRef media(Object key) {
                return null;
            }

            @Override
            public Picture.MediaRef media(byte[] bytes, String ext, int w, int h, Object key) {
                stored.add(ext);
                return new Picture.MediaRef("m", "image/" + ext, w, h);
            }
        };
        SlideMedia media = new SlideMedia(store, false);
        NoImageCache.run(() -> media.media(png.toByteArray(), "png", 600, 600, "k"));
        assertEquals(List.of("jpeg"), stored);
    }
}
