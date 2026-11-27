package stirling.software.officeconvert.svg;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.color.PDICCBased;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import stirling.software.officeconvert.build.RgbPixels;

final class Pictures {

    static final int MAX_JPEG_BYTES = 64 << 20;

    private Pictures() {}

    record Encoded(String mime, byte[] bytes) {
        String uri() {
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
        }
    }

    static Encoded jpeg(PDImageXObject image) {
        try {
            List<COSName> filters = image.getStream().getFilters();
            if (filters.size() != 1 || !COSName.DCT_DECODE.equals(filters.get(0)) || image.getColorKeyMask() != null) {
                return null;
            }
            COSBase decode = image.getCOSObject().getDictionaryObject(COSName.DECODE, COSName.D);
            if (decode instanceof COSArray a && a.size() > 0 && !defaultDecode(a)) {
                return null;
            }
            PDColorSpace cs = image.getColorSpace();
            boolean plain = cs instanceof PDDeviceRGB || cs instanceof PDDeviceGray
                    || cs instanceof PDICCBased icc && (icc.getNumberOfComponents() == 3 || icc.getNumberOfComponents() == 1);
            if (!plain || image.getBitsPerComponent() != 8) {
                return null;
            }
            byte[] bytes;
            try (InputStream in = image.getCOSObject().createRawInputStream()) {
                bytes = in.readNBytes(MAX_JPEG_BYTES + 1);
            }
            if (bytes.length > MAX_JPEG_BYTES || bytes.length < 4 || (bytes[0] & 0xFF) != 0xFF || (bytes[1] & 0xFF) != 0xD8
                    || adobeTransform(bytes)) {
                return null;
            }
            return new Encoded("image/jpeg", bytes);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean defaultDecode(COSArray a) {
        for (int i = 0; i < a.size(); i++) {
            float v = a.get(i) instanceof org.apache.pdfbox.cos.COSNumber n ? n.floatValue() : -1;
            if (v != (i % 2 == 0 ? 0 : 1)) {
                return false;
            }
        }
        return true;
    }

    private static boolean adobeTransform(byte[] b) {
        int i = 2;
        while (i + 4 < b.length && (b[i] & 0xFF) == 0xFF) {
            int marker = b[i + 1] & 0xFF;
            int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (marker == 0xDA || len < 2) {
                return false;
            }
            if (marker == 0xEE && i + 15 < b.length && b[i + 4] == 'A' && b[i + 5] == 'd' && b[i + 6] == 'o') {
                return false;
            }
            if (marker >= 0xC0 && marker <= 0xC3 && i + 9 < b.length) {
                int components = b[i + 9] & 0xFF;
                if (components == 4) {
                    return true;
                }
            }
            i += 2 + len;
        }
        return false;
    }

    static Encoded encode(BufferedImage image) throws IOException {
        boolean alpha = image.getColorModel().hasAlpha() && !opaque(image);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(alpha ? image : RgbPixels.forJpeg(image), "png", png);
        byte[] best = png.toByteArray();
        if (!alpha && best.length > 100_000) {
            byte[] jpeg = jpegOf(image);
            if (jpeg != null && jpeg.length < best.length * 0.5) {
                return new Encoded("image/jpeg", jpeg);
            }
        }
        return new Encoded("image/png", best);
    }

    static boolean opaque(BufferedImage image) {
        if (!image.getColorModel().hasAlpha()) {
            return true;
        }
        int w = image.getWidth();
        int[] row = new int[w];
        for (int y = 0; y < image.getHeight(); y++) {
            image.getRGB(0, y, w, 1, row, 0, w);
            for (int p : row) {
                if ((p >>> 24) != 0xFF) {
                    return false;
                }
            }
        }
        return true;
    }

    static byte[] jpegOf(BufferedImage image) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return null;
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.88f);
            writer.write(null, new IIOImage(RgbPixels.forJpeg(image), null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    static BufferedImage gray(BufferedImage image) {
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        var g = out.createGraphics();
        try {
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
