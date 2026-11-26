package stirling.software.officeconvert.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.NoImageCache;

class MediaBytesCacheTest {

    @Test
    void gifIsRedrawnWithoutImageIoDiskCache() throws Exception {
        ByteArrayOutputStream gif = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "gif", gif);
        MediaBytes media = NoImageCache.run(() -> MediaBytes.of(gif.toByteArray()));
        assertNotNull(media);
        assertEquals("png", media.ext());
        assertEquals(40, media.pixelWidth());
    }
}
