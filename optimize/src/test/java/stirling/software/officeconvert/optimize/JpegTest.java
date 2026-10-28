package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Node;

class JpegTest {

    private static BufferedImage picture(int w, int h, int type) {
        BufferedImage img = new BufferedImage(w, h, type);
        Random r = new Random(w * 31L + h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = (x * 3 + y * 2) & 0xFF;
                img.setRGB(x, y, (v << 16) | ((255 - v) << 8) | (r.nextInt(64) + (x ^ y) & 0xFF));
            }
        }
        return img;
    }

    private static byte[] jpeg(BufferedImage img, float quality, int restartInterval) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        IIOMetadata meta = writer.getDefaultImageMetadata(new ImageTypeSpecifier(img), param);
        if (restartInterval > 0) {
            IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree("javax_imageio_jpeg_image_1.0");
            Node sequence = root.getElementsByTagName("markerSequence").item(0);
            IIOMetadataNode dri = new IIOMetadataNode("dri");
            dri.setAttribute("interval", Integer.toString(restartInterval));
            sequence.insertBefore(dri, sequence.getFirstChild());
            meta.setFromTree("javax_imageio_jpeg_image_1.0", root);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, meta), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static int[] pixels(byte[] jpeg) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }

    @Test
    void huffmanTablesShrinkWithoutChangingAPixel() throws Exception {
        for (int restart : new int[] {0, 3}) {
            for (int type : new int[] {BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_BYTE_GRAY}) {
                byte[] in = jpeg(picture(131, 77, type), 0.8f, restart);
                byte[] out = Jpeg.optimize(in);
                assertNotNull(out, "restart " + restart + " type " + type);
                assertTrue(out.length < in.length, out.length + " vs " + in.length);
                assertArrayEquals(pixels(in), pixels(out));
            }
        }
    }

    @Test
    void anythingItDoesNotFullyReadIsLeftAlone() throws Exception {
        byte[] in = jpeg(picture(64, 64, BufferedImage.TYPE_INT_RGB), 0.9f, 0);
        assertNull(Jpeg.optimize(java.util.Arrays.copyOf(in, in.length / 2)));
        assertNull(Jpeg.optimize(new byte[] {1, 2, 3}));
        byte[] broken = in.clone();
        for (int i = broken.length / 2; i < broken.length / 2 + 40; i++) {
            broken[i] = (byte) 0xFF;
        }
        byte[] out = Jpeg.optimize(broken);
        if (out != null) {
            assertArrayEquals(pixels(broken), pixels(out));
        }
    }
}
