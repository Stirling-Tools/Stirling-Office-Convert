package stirling.software.officeconvert.json;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

/** A picture from an untrusted document, kept as PNG or JPEG with its size read from its header; GIF and BMP are
 * redrawn as PNG. Pictures are only decoded when drawn, by the renderer's guarded decoder. */
public record MediaBytes(byte[] bytes, String ext, int pixelWidth, int pixelHeight) {

    public static final long MAX_PIXELS = 50_000_000L;

    private static final long MAX_REDRAWN_PIXELS = 25_000_000L;

    /** Null when the bytes are not a PNG, JPEG, GIF or BMP picture, or one too large to draw. */
    public static MediaBytes of(byte[] b) {
        if (b == null || b.length < 26) {
            return null;
        }
        int[] size;
        String kind;
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            kind = "png";
            size = new int[] {be32(b, 16), be32(b, 20)};
        } else if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
            kind = "jpeg";
            size = jpegSize(b);
        } else if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
            kind = "gif";
            size = gifFrame(b);
        } else if (b[0] == 'B' && b[1] == 'M') {
            kind = "bmp";
            size = wrapsJpegOrPng(b) ? null : new int[] {le32(b, 18), Math.abs(le32(b, 22))};
        } else {
            return null;
        }
        if (size == null || size[0] <= 0 || size[1] <= 0 || (long) size[0] * size[1] > MAX_PIXELS) {
            return null;
        }
        if (kind.equals("png") || kind.equals("jpeg")) {
            return new MediaBytes(b, kind, size[0], size[1]);
        }
        return (long) size[0] * size[1] > MAX_REDRAWN_PIXELS ? null : redrawn(b);
    }

    // GIF and BMP have no place in a Word package, so they are drawn once into a PNG
    private static MediaBytes redrawn(byte[] b) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(b));
            if (img == null) {
                return null;
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (!ImageIO.write(img, "png", png)) {
                return null;
            }
            return new MediaBytes(png.toByteArray(), "png", img.getWidth(), img.getHeight());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // The first frame is what gets decoded and its own size, not the screen size, is what gets allocated
    private static int[] gifFrame(byte[] b) {
        int i = 13;
        if ((b[10] & 0x80) != 0) {
            i += 3 << ((b[10] & 7) + 1);
        }
        while (i < b.length) {
            int block = b[i] & 0xFF;
            if (block == 0x2C) {
                return i + 9 <= b.length ? new int[] {le16(b, i + 5), le16(b, i + 7)} : null;
            }
            if (block != 0x21 || i + 2 >= b.length) {
                return null;
            }
            i += 2;
            while (i < b.length && (b[i] & 0xFF) != 0) {
                i += (b[i] & 0xFF) + 1;
            }
            i++;
        }
        return null;
    }

    private static boolean wrapsJpegOrPng(byte[] b) {
        if (le32(b, 14) < 40) {
            return false;
        }
        if (b.length < 34) {
            return true;
        }
        int compression = le32(b, 30);
        return compression == 4 || compression == 5;
    }

    private static int[] jpegSize(byte[] b) {
        int i = 2;
        while (i + 9 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) {
                return null;
            }
            int marker = b[i + 1] & 0xFF;
            if (marker == 0xFF) {
                i++;
                continue;
            }
            int len = (b[i + 2] & 0xFF) << 8 | b[i + 3] & 0xFF;
            boolean frame = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (frame) {
                return new int[] {(b[i + 7] & 0xFF) << 8 | b[i + 8] & 0xFF, (b[i + 5] & 0xFF) << 8 | b[i + 6] & 0xFF};
            }
            if (len < 2) {
                return null;
            }
            i += 2 + len;
        }
        return null;
    }

    private static int be32(byte[] b, int at) {
        return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | b[at + 3] & 0xFF;
    }

    private static int le16(byte[] b, int at) {
        return b[at] & 0xFF | (b[at + 1] & 0xFF) << 8;
    }

    private static int le32(byte[] b, int at) {
        return le16(b, at) | le16(b, at + 2) << 16;
    }
}
