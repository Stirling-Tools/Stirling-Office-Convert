package stirling.software.officeconvert.slides;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import stirling.software.officeconvert.build.PlacedContent;
import stirling.software.officeconvert.model.Picture;

final class SlideMedia implements PlacedContent.MediaStore {

    private static final int MIN_BYTES = 256 * 1024;

    private static final double PHOTO_BYTES_PER_PIXEL = 0.75;

    private static final float QUALITY = 0.9f;

    private final PlacedContent.MediaStore store;
    private final boolean lossless;
    private final Map<String, Picture.MediaRef> byContent = new HashMap<>();
    private final Map<Object, Picture.MediaRef> byKey = new HashMap<>();

    SlideMedia(PlacedContent.MediaStore store, boolean lossless) {
        this.store = store;
        this.lossless = lossless;
    }

    @Override
    public Picture.MediaRef media(Object key) {
        Picture.MediaRef ref = byKey.get(key);
        return ref != null ? ref : store.media(key);
    }

    @Override
    public Picture.MediaRef media(byte[] bytes, String ext, int pixelWidth, int pixelHeight, Object key)
            throws IOException {
        String digest = ext + ":" + digest(bytes);
        Picture.MediaRef ref = byContent.get(digest);
        if (ref == null) {
            byte[] jpeg = !lossless && photo(bytes, ext, pixelWidth, pixelHeight) ? jpeg(bytes) : null;
            ref = jpeg != null && jpeg.length < 0.7 * bytes.length
                    ? store.media(jpeg, "jpeg", pixelWidth, pixelHeight, key)
                    : store.media(bytes, ext, pixelWidth, pixelHeight, key);
            byContent.put(digest, ref);
        }
        byKey.put(key, ref);
        return ref;
    }

    private static boolean photo(byte[] bytes, String ext, int pixelWidth, int pixelHeight) {
        return "png".equals(ext) && bytes.length >= MIN_BYTES
                && bytes.length >= PHOTO_BYTES_PER_PIXEL * pixelWidth * pixelHeight;
    }

    private static byte[] jpeg(byte[] png) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null || !opaque(img)) {
            return null;
        }
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(img, 0, 0, Color.WHITE, null);
        g.dispose();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return null;
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(QUALITY);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static boolean opaque(BufferedImage img) {
        WritableRaster alpha = img.getAlphaRaster();
        if (alpha == null) {
            return true;
        }
        int full = (1 << alpha.getSampleModel().getSampleSize(0)) - 1;
        int[] row = new int[alpha.getWidth()];
        for (int y = 0; y < alpha.getHeight(); y++) {
            alpha.getSamples(0, y, alpha.getWidth(), 1, 0, row);
            for (int a : row) {
                if (a != full) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
