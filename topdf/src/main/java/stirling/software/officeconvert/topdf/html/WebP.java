package stirling.software.officeconvert.topdf.html;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;

import stirling.software.officeconvert.json.MediaBytes;

/** WebP pictures, which pages mostly serve their photos as, redrawn as JPEG or PNG. The size is read from the header
 * and checked before anything is decoded; the decoder is called directly, never looked up in the ImageIO registry. */
final class WebP {

    static final long MAX_PIXELS = 25_000_000L;

    private WebP() {}

    static boolean is(byte[] b) {
        return b.length >= 30 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E'
                && b[10] == 'B' && b[11] == 'P';
    }

    /** Null when the picture cannot be read or is larger than {@link #MAX_PIXELS}. */
    static MediaBytes redraw(byte[] b) {
        int[] size = size(b);
        if (size == null || size[0] <= 0 || size[1] <= 0 || (long) size[0] * size[1] > MAX_PIXELS) {
            return null;
        }
        ImageReader reader = new WebPImageReaderSpi().createReaderInstance(null);
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(b))) {
            reader.setInput(in, true, true);
            if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS) {
                return null;
            }
            BufferedImage img = reader.read(0);
            boolean opaque = !img.getColorModel().hasAlpha();
            BufferedImage out = img;
            if (opaque && img.getType() != BufferedImage.TYPE_INT_RGB) {
                out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                out.getGraphics().drawImage(img, 0, 0, null);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            String format = opaque ? "jpeg" : "png";
            if (!ImageIO.write(out, format, bytes)) {
                return null;
            }
            return new MediaBytes(bytes.toByteArray(), format, img.getWidth(), img.getHeight());
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            reader.dispose();
        }
    }

    // The canvas size from the first chunk: VP8X (extended), VP8L (lossless) or VP8 (lossy)
    static int[] size(byte[] b) {
        if (!is(b)) {
            return null;
        }
        String chunk = new String(b, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        return switch (chunk) {
            case "VP8X" -> new int[] {u24(b, 24) + 1, u24(b, 27) + 1};
            case "VP8L" -> {
                int bits = (b[21] & 0xFF) | (b[22] & 0xFF) << 8 | (b[23] & 0xFF) << 16 | (b[24] & 0xFF) << 24;
                yield new int[] {(bits & 0x3FFF) + 1, (bits >>> 14 & 0x3FFF) + 1};
            }
            case "VP8 " -> new int[] {((b[26] & 0xFF) | (b[27] & 0xFF) << 8) & 0x3FFF,
                    ((b[28] & 0xFF) | (b[29] & 0xFF) << 8) & 0x3FFF};
            default -> null;
        };
    }

    private static int u24(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16;
    }
}
