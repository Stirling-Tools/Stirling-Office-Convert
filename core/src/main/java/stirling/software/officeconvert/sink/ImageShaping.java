package stirling.software.officeconvert.sink;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import stirling.software.officeconvert.model.Picture;

final class ImageShaping {

    record Result(byte[] bytes, String ext, int width, int height) {}

    private ImageShaping() {}

    static boolean needed(Picture pic) {
        return pic.cropLeft > 0 || pic.cropTop > 0 || pic.cropRight > 0 || pic.cropBottom > 0
                || Math.floorMod(pic.rotation, 360) != 0 || pic.flipH;
    }

    static String key(Picture pic) {
        return String.format(Locale.ROOT, "%s|%.4f|%.4f|%.4f|%.4f|%d|%b", pic.media.name(), pic.cropLeft, pic.cropTop,
                pic.cropRight, pic.cropBottom, Math.floorMod(pic.rotation, 360), pic.flipH);
    }

    static Result apply(byte[] bytes, Picture pic, boolean lossless) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) {
            return null;
        }
        img = crop(img, pic);
        if (pic.flipH) {
            img = transform(img, new AffineTransform(-1, 0, 0, 1, img.getWidth(), 0), img.getWidth(), img.getHeight());
        }
        int turn = Math.floorMod(Math.round(pic.rotation / 90f) * 90, 360);
        int w = img.getWidth();
        int h = img.getHeight();
        switch (turn) {
            case 90 -> img = transform(img, new AffineTransform(0, 1, -1, 0, h, 0), h, w);
            case 180 -> img = transform(img, new AffineTransform(-1, 0, 0, -1, w, h), w, h);
            case 270 -> img = transform(img, new AffineTransform(0, -1, 1, 0, 0, w), h, w);
            default -> { }
        }
        boolean jpeg = !lossless && "image/jpeg".equals(pic.media.contentType()) && !img.getColorModel().hasAlpha();
        byte[] out = jpeg ? jpeg(img) : png(img);
        return new Result(out, jpeg ? "jpeg" : "png", img.getWidth(), img.getHeight());
    }

    private static BufferedImage crop(BufferedImage img, Picture pic) {
        int w = img.getWidth();
        int h = img.getHeight();
        int x0 = Math.round(pic.cropLeft * w);
        int y0 = Math.round(pic.cropTop * h);
        int x1 = Math.max(x0 + 1, w - Math.round(pic.cropRight * w));
        int y1 = Math.max(y0 + 1, h - Math.round(pic.cropBottom * h));
        if (x0 == 0 && y0 == 0 && x1 >= w && y1 >= h) {
            return img;
        }
        x0 = Math.min(x0, w - 1);
        y0 = Math.min(y0, h - 1);
        return copy(img.getSubimage(x0, y0, Math.min(w, x1) - x0, Math.min(h, y1) - y0));
    }

    private static BufferedImage copy(BufferedImage src) {
        return transform(src, new AffineTransform(), src.getWidth(), src.getHeight());
    }

    private static BufferedImage transform(BufferedImage src, AffineTransform t, int w, int h) {
        boolean alpha = src.getColorModel().hasAlpha();
        boolean grey = src.getType() == BufferedImage.TYPE_BYTE_GRAY;
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : grey ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_INT_RGB;
        BufferedImage out = new BufferedImage(w, h, type);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(src, t, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(img, "png", out)) {
            throw new IOException("No PNG writer");
        }
        return out.toByteArray();
    }

    private static byte[] jpeg(BufferedImage img) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return png(img);
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.92f);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
