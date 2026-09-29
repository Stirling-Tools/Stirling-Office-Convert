package stirling.software.officeconvert.topdf.io;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.event.IIOReadProgressListener;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

public final class PictureDecoder {

    public enum Kind {
        PNG,
        JPEG,
        GIF,
        BMP,
        TIFF,
        EMF,
        WMF,
        SVG,
        UNKNOWN
    }

    public static final long MAX_PIXELS = 100_000_000L;

    public static final long DECODE_PIXELS = 32_000_000L;

    public static final int MAX_METAFILE_BYTES = 32 << 20;

    public static final float PIXELS_PER_INCH = 96f;

    private static final String JDK_READERS = "com.sun.imageio.plugins.";

    private PictureDecoder() {}

    public static Kind sniff(byte[] data) {
        Objects.requireNonNull(data, "data");
        int n = data.length;
        if (n >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return Kind.PNG;
        }
        if (n >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return Kind.JPEG;
        }
        if (n >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8'
                && (data[4] == '7' || data[4] == '9') && data[5] == 'a') {
            return Kind.GIF;
        }
        if (n >= 14 && data[0] == 'B' && data[1] == 'M') {
            return Kind.BMP;
        }
        if (n >= 8 && ((data[0] == 'I' && data[1] == 'I' && data[2] == 42 && data[3] == 0)
                || (data[0] == 'M' && data[1] == 'M' && data[2] == 0 && data[3] == 42))) {
            return Kind.TIFF;
        }
        if (n >= 44 && le32(data, 0) == 1 && data[40] == ' ' && data[41] == 'E' && data[42] == 'M' && data[43] == 'F') {
            return Kind.EMF;
        }
        if (n >= 22 && le32(data, 0) == 0x9AC6CDD7) {
            return Kind.WMF;
        }
        if (n >= 18 && (le16(data, 0) == 1 || le16(data, 0) == 2) && le16(data, 2) == 9) {
            return Kind.WMF;
        }
        String head = new String(data, 0, Math.min(n, 2048), StandardCharsets.ISO_8859_1).stripLeading();
        if (head.startsWith("ï»¿")) {
            head = head.substring(3).stripLeading();
        }
        String lower = head.toLowerCase(Locale.ROOT);
        if (lower.startsWith("<svg") || (lower.startsWith("<?xml") || lower.startsWith("<!--")) && lower.contains("<svg")) {
            return Kind.SVG;
        }
        return Kind.UNKNOWN;
    }

    public static DecodedPicture decode(PDDocument doc, byte[] data) throws IOException {
        Objects.requireNonNull(doc, "doc");
        Objects.requireNonNull(data, "data");
        stopIfInterrupted();
        byte[] bytes = gunzip(data);
        Kind kind = sniff(bytes);
        switch (kind) {
            case EMF, WMF -> {
                return Metafiles.render(doc, bytes, kind);
            }
            case SVG -> throw new IOException("SVG pictures are not supported; use the PNG copy the document keeps");
            case UNKNOWN -> throw new IOException("The picture is in a format that cannot be read");
            default -> {
                return raster(doc, bytes, kind);
            }
        }
    }

    public static DecodedPicture fromImage(PDDocument doc, BufferedImage image) throws IOException {
        Objects.requireNonNull(doc, "doc");
        Objects.requireNonNull(image, "image");
        if ((long) image.getWidth() * image.getHeight() > MAX_PIXELS) {
            throw new IOException("The picture is too large: " + image.getWidth() + "x" + image.getHeight() + " pixels");
        }
        PDImageXObject x = FlateImages.encode(doc, image);
        return DecodedPicture.raster(Kind.PNG, x, points(image.getWidth()), points(image.getHeight()));
    }

    public static Dimension pixelSize(byte[] data) throws IOException {
        Objects.requireNonNull(data, "data");
        Kind kind = sniff(data);
        if (kind == Kind.PNG && data.length >= 24 && chunk(data, 12, "IHDR")) {
            int w = be32(data, 16);
            int h = be32(data, 20);
            if (w > 0 && h > 0) {
                return new Dimension(w, h);
            }
        }
        JpegFrame frame = kind == Kind.JPEG ? JpegFrame.of(data) : null;
        if (frame != null && frame.width() > 0 && frame.height() > 0) {
            return new Dimension(frame.width(), frame.height());
        }
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            ImageReader reader = reader(kind);
            try {
                reader.setInput(in, true, true);
                return new Dimension(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        } catch (RuntimeException e) {
            throw new IOException("The picture's header cannot be read", e);
        }
    }

    public static BufferedImage readRaster(byte[] data, long maxPixels) throws IOException {
        Objects.requireNonNull(data, "data");
        if (maxPixels < 1) {
            throw new IllegalArgumentException("maxPixels must be positive, was " + maxPixels);
        }
        stopIfInterrupted();
        Kind kind = sniff(data);
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            ImageReader reader = reader(kind);
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                long pixels = (long) w * h;
                if (w <= 0 || h <= 0) {
                    throw new IOException("The picture has no size");
                }
                if (pixels > MAX_PIXELS) {
                    throw new IOException("The picture is too large: " + w + "x" + h + " pixels");
                }
                ImageReadParam param = reader.getDefaultReadParam();
                if (pixels > maxPixels) {
                    int step = (int) Math.ceil(Math.sqrt((double) pixels / maxPixels));
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                reader.addIIOReadProgressListener(new AbortOnInterrupt());
                BufferedImage image = reader.read(0, param);
                stopIfInterrupted();
                return image;
            } finally {
                reader.dispose();
            }
        } catch (RuntimeException e) {
            throw new IOException("The picture could not be decoded: " + e.getMessage(), e);
        }
    }

    private static DecodedPicture raster(PDDocument doc, byte[] data, Kind kind) throws IOException {
        JpegFrame frame = kind == Kind.JPEG ? JpegFrame.of(data) : null;
        Dimension size = frame != null ? new Dimension(frame.width(), frame.height()) : pixelSize(data);
        long pixels = (long) size.width * size.height;
        if (size.width <= 0 || size.height <= 0) {
            throw new IOException("The picture has no size");
        }
        if (pixels > MAX_PIXELS) {
            throw new IOException("The picture is too large: " + size.width + "x" + size.height + " pixels");
        }
        PDImageXObject image = null;
        if (frame != null && frame.passesThrough()) {
            image = dct(doc, data, frame);
        } else if (kind == Kind.JPEG) {
            image = quietly(() -> JPEGFactory.createFromByteArray(doc, data));
        } else if (kind == Kind.PNG && pixels <= DECODE_PIXELS) {
            if (passesThrough(data)) {
                image = quietly(() -> PDImageXObject.createFromByteArray(doc, data, "picture.png"));
            }
            if (image == null) {
                image = quietly(() -> PngStream.encode(doc, data));
            }
        }
        if (image == null) {
            image = FlateImages.encode(doc, readRaster(data, DECODE_PIXELS));
        }
        return DecodedPicture.raster(kind, image, points(size.width), points(size.height));
    }

    // The JPEG goes into the PDF as it is, as JPEGFactory would put it, without asking ImageIO about it first
    private static PDImageXObject dct(PDDocument doc, byte[] data, JpegFrame frame) throws IOException {
        PDColorSpace cs = switch (frame.components()) {
            case 1 -> PDDeviceGray.INSTANCE;
            case 3 -> PDDeviceRGB.INSTANCE;
            default -> PDDeviceCMYK.INSTANCE;
        };
        PDImageXObject x = new PDImageXObject(doc, new ByteArrayInputStream(data), COSName.DCT_DECODE, frame.width(),
                frame.height(), 8, cs);
        if (frame.components() == 4) {
            COSArray decode = new COSArray();
            for (int i = 0; i < 4; i++) {
                decode.add(COSInteger.ONE);
                decode.add(COSInteger.ZERO);
            }
            x.setDecode(decode);
        }
        return x;
    }

    // The size and layout of a JPEG from its frame header (SOFn), read without a decoder
    record JpegFrame(int marker, int precision, int width, int height, int components) {

        // Baseline, extended and progressive Huffman frames of 8-bit samples, which every PDF reader decodes
        boolean passesThrough() {
            return marker <= 0xC2 && precision == 8 && width > 0 && height > 0
                    && (components == 1 || components == 3 || components == 4);
        }

        static JpegFrame of(byte[] d) {
            int i = 2;
            for (int n = 0; n < 10_000 && i + 4 <= d.length; n++) {
                if ((d[i] & 0xFF) != 0xFF) {
                    return null;
                }
                int m = d[i + 1] & 0xFF;
                if (m == 0xFF) {
                    i++;
                    continue;
                }
                if (m == 0x01 || m >= 0xD0 && m <= 0xD8) {
                    i += 2;
                    continue;
                }
                if (m == 0xD9 || m == 0xDA) {
                    return null;
                }
                int length = (d[i + 2] & 0xFF) << 8 | (d[i + 3] & 0xFF);
                if (length < 2) {
                    return null;
                }
                boolean frame = m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC;
                if (frame) {
                    if (length < 8 || i + 10 > d.length) {
                        return null;
                    }
                    return new JpegFrame(m, d[i + 4] & 0xFF, (d[i + 7] & 0xFF) << 8 | (d[i + 8] & 0xFF),
                            (d[i + 5] & 0xFF) << 8 | (d[i + 6] & 0xFF), d[i + 9] & 0xFF);
                }
                i += 2 + length;
            }
            return null;
        }
    }

    // PDFBox copies the rows of plain RGB and opaque palette PNGs as they are; others it decodes and re-encodes slowly
    static boolean passesThrough(byte[] png) {
        if (png.length < 33 || be32(png, 8) != 13 || !chunk(png, 12, "IHDR")) {
            return false;
        }
        int depth = png[24] & 0xFF;
        int type = png[25] & 0xFF;
        if (png[28] != 0 || !(type == 2 && depth == 8 || type == 3 && depth <= 8)) {
            return false;
        }
        long at = 8;
        for (int n = 0; at + 12 <= png.length && n < 10_000; n++) {
            int i = (int) at;
            long length = be32(png, i) & 0xFFFFFFFFL;
            if (chunk(png, i + 4, "IDAT")) {
                return true;
            }
            if (chunk(png, i + 4, "tRNS") || chunk(png, i + 4, "cHRM")
                    || chunk(png, i + 4, "gAMA") && (length != 4 || be32(png, i + 8) != 45455)) {
                return false;
            }
            at += 12 + length;
        }
        return false;
    }

    private static boolean chunk(byte[] d, int at, String type) {
        return at + 4 <= d.length && d[at] == type.charAt(0) && d[at + 1] == type.charAt(1)
                && d[at + 2] == type.charAt(2) && d[at + 3] == type.charAt(3);
    }

    private static int be32(byte[] d, int i) {
        return (d[i] & 0xFF) << 24 | (d[i + 1] & 0xFF) << 16 | (d[i + 2] & 0xFF) << 8 | (d[i + 3] & 0xFF);
    }

    @FunctionalInterface
    private interface Attempt {
        PDImageXObject make() throws IOException;
    }

    private static PDImageXObject quietly(Attempt attempt) throws InterruptedIOException {
        try {
            return attempt.make();
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            stopIfInterrupted();
            return null;
        }
    }

    private static ImageReader reader(Kind kind) throws IOException {
        String format = switch (kind) {
            case PNG -> "png";
            case JPEG -> "jpeg";
            case GIF -> "gif";
            case BMP -> "bmp";
            case TIFF -> "tiff";
            default -> null;
        };
        if (format != null) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
            while (readers.hasNext()) {
                ImageReader r = readers.next();
                if (r.getOriginatingProvider() != null
                        && r.getOriginatingProvider().getClass().getName().startsWith(JDK_READERS)) {
                    return r;
                }
                r.dispose();
            }
        }
        throw new IOException("The picture is in a format that cannot be read");
    }

    static byte[] gunzip(byte[] data) throws IOException {
        if (data.length < 2 || (data[0] & 0xFF) != 0x1F || (data[1] & 0xFF) != 0x8B) {
            return data;
        }
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            byte[] out = in.readNBytes(MAX_METAFILE_BYTES + 1);
            if (out.length > MAX_METAFILE_BYTES) {
                throw new IOException("The compressed picture inflates past " + (MAX_METAFILE_BYTES >> 20) + " MB");
            }
            return out;
        }
    }

    private static float points(int pixels) {
        return pixels * 72f / PIXELS_PER_INCH;
    }

    private static void stopIfInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Conversion interrupted");
        }
    }

    private static int le16(byte[] d, int i) {
        return (d[i] & 0xFF) | (d[i + 1] & 0xFF) << 8;
    }

    private static int le32(byte[] d, int i) {
        return (d[i] & 0xFF) | (d[i + 1] & 0xFF) << 8 | (d[i + 2] & 0xFF) << 16 | (d[i + 3] & 0xFF) << 24;
    }

    private static final class AbortOnInterrupt implements IIOReadProgressListener {

        @Override
        public void imageProgress(ImageReader source, float percentageDone) {
            if (Thread.currentThread().isInterrupted()) {
                source.abort();
            }
        }

        @Override
        public void sequenceStarted(ImageReader source, int minIndex) {}

        @Override
        public void sequenceComplete(ImageReader source) {}

        @Override
        public void imageStarted(ImageReader source, int imageIndex) {}

        @Override
        public void imageComplete(ImageReader source) {}

        @Override
        public void thumbnailStarted(ImageReader source, int imageIndex, int thumbnailIndex) {}

        @Override
        public void thumbnailProgress(ImageReader source, float percentageDone) {}

        @Override
        public void thumbnailComplete(ImageReader source) {}

        @Override
        public void readAborted(ImageReader source) {}
    }
}
