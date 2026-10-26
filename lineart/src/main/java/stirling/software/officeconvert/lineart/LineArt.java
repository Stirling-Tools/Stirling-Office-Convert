package stirling.software.officeconvert.lineart;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDSeparation;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import stirling.software.officeconvert.extract.PdfFiles;
import stirling.software.officeconvert.jpx.JpxImageIO;

public final class LineArt {

    public static final long DEFAULT_MAX_PIXELS = 50_000_000L;

    public static final long MAX_INPUT_BYTES = 1L << 30;

    public enum Method {
        EDGES,
        FIXED,
        OTSU,
        SAUVOLA
    }

    public enum Codec {
        SMALLEST,
        JBIG2,
        CCITT_G4,
        FLATE
    }

    public record Options(Method method, double threshold, int edgeLevel, int window, double k, Codec codec,
            boolean inkColour, long maxPixels, String password) {

        public Options {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(codec, "codec");
            if (!(threshold >= 0 && threshold <= 100)) {
                throw new IllegalArgumentException("threshold must be from 0 to 100, was " + threshold);
            }
            if (edgeLevel < 1 || edgeLevel > 3) {
                throw new IllegalArgumentException("edgeLevel must be 1, 2 or 3, was " + edgeLevel);
            }
            if (window != 0 && (window < 3 || window > 1001)) {
                throw new IllegalArgumentException("window must be 0 (automatic) or from 3 to 1001 pixels, was " + window);
            }
            if (!(k >= 0 && k <= 1)) {
                throw new IllegalArgumentException("k must be from 0 to 1, was " + k);
            }
            if (maxPixels < 1) {
                throw new IllegalArgumentException("maxPixels must be 1 or more, was " + maxPixels);
            }
        }

        public static Options defaults() {
            return new Options(Method.SAUVOLA, 50, 1, 0, 0.5, Codec.SMALLEST, false, DEFAULT_MAX_PIXELS, null);
        }

        public static Options imageMagickEdges(int edgeLevel) {
            return defaults().method(Method.EDGES).edgeLevel(edgeLevel);
        }

        public Options method(Method to) {
            return new Options(to, threshold, edgeLevel, window, k, codec, inkColour, maxPixels, password);
        }

        public Options threshold(double percent) {
            return new Options(method, percent, edgeLevel, window, k, codec, inkColour, maxPixels, password);
        }

        public Options edgeLevel(int level) {
            return new Options(method, threshold, level, window, k, codec, inkColour, maxPixels, password);
        }

        public Options window(int pixels) {
            return new Options(method, threshold, edgeLevel, pixels, k, codec, inkColour, maxPixels, password);
        }

        public Options k(double k) {
            return new Options(method, threshold, edgeLevel, window, k, codec, inkColour, maxPixels, password);
        }

        public Options codec(Codec to) {
            return new Options(method, threshold, edgeLevel, window, k, to, inkColour, maxPixels, password);
        }

        public Options inkColour(boolean keep) {
            return new Options(method, threshold, edgeLevel, window, k, codec, keep, maxPixels, password);
        }

        public Options maxPixels(long limit) {
            return new Options(method, threshold, edgeLevel, window, k, codec, inkColour, limit, password);
        }

        public Options password(String secret) {
            return new Options(method, threshold, edgeLevel, window, k, codec, inkColour, maxPixels, secret);
        }

        @Override
        public String toString() {
            return "Options[method=" + method + ", threshold=" + threshold + ", edgeLevel=" + edgeLevel + ", window="
                    + window + ", k=" + k + ", codec=" + codec + ", inkColour=" + inkColour
                    + ", maxPixels=" + maxPixels + ", password=" + (password == null ? "none" : "given") + "]";
        }
    }

    public record Result(int images, int converted, long bytesBefore, long bytesAfter, Map<Codec, Integer> codecs,
            List<String> warnings) {

        public Result {
            codecs = Collections.unmodifiableMap(new EnumMap<>(codecs));
            warnings = List.copyOf(warnings);
        }

        public int skipped() {
            return images - converted;
        }
    }

    private static final COSName DL = COSName.getPDFName("DL");

    private static final COSName[] STALE = {COSName.FILTER, COSName.DECODE_PARMS, COSName.DECODE, COSName.F,
        COSName.F_FILTER, COSName.F_DECODE_PARMS, DL, COSName.SMASK_IN_DATA};

    private LineArt() {}

    public static Bitmap binarize(BufferedImage image, Options options) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(options, "options");
        return Binarizer.binarize(image, options);
    }

    public static Result convert(Path in, Path out) throws IOException {
        return convert(in, out, Options.defaults());
    }

    public static Result convert(Path in, Path out, Options options) throws IOException {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(options, "options");
        if (Files.size(in) > MAX_INPUT_BYTES) {
            throw new IOException("The PDF is larger than " + (MAX_INPUT_BYTES >> 20) + " MB");
        }
        Path part = Files.createTempFile(PdfFiles.outputFolder(out), ".lineart-", ".part");
        try {
            Result result;
            try (PDDocument doc = PdfFiles.open(in, options.password())) {
                result = convert(doc, options);
                try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(part), 1 << 16)) {
                    doc.save(os);
                }
            }
            PdfFiles.stopIfInterrupted();
            move(part, out);
            return result;
        } catch (IOException e) {
            throw PdfFiles.interrupted(e);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    public static Result convert(PDDocument document, Options options) throws IOException {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(options, "options");
        PdfFiles.checkOpen(document);
        JpxImageIO.install();
        List<ImageWalk.Found> found = ImageWalk.images(document);
        Map<Codec, Integer> codecs = new EnumMap<>(Codec.class);
        List<String> warnings = new ArrayList<>();
        long before = 0;
        long after = 0;
        int converted = 0;
        boolean jbig2 = false;
        for (ImageWalk.Found f : found) {
            PdfFiles.stopIfInterrupted();
            COSStream stream = f.stream();
            long length = stream.getLength();
            Codecs.Encoded encoded;
            try {
                encoded = convert(document, f, options, warnings);
            } catch (IOException | RuntimeException e) {
                warnings.add("Page " + f.page() + ": an image could not be read and was left as it was (" + e.getMessage() + ")");
                continue;
            }
            if (encoded == null) {
                continue;
            }
            converted++;
            before += length;
            after += encoded.data().length;
            codecs.merge(encoded.codec(), 1, Integer::sum);
            jbig2 |= encoded.codec() == Codec.JBIG2;
        }
        if (jbig2 && document.getVersion() < 1.4f) {
            document.setVersion(1.4f);
        }
        return new Result(found.size(), converted, before, after, codecs, warnings);
    }

    private static Codecs.Encoded convert(PDDocument document, ImageWalk.Found f, Options options, List<String> warnings)
            throws IOException {
        COSStream stream = f.stream();
        if (stream.getBoolean(COSName.IMAGE_MASK, false) || stream.getDictionaryObject(COSName.F) != null) {
            return null;
        }
        PDImageXObject image = new PDImageXObject(new PDStream(stream), f.resources());
        long pixels = (long) image.getWidth() * image.getHeight();
        if (image.getWidth() <= 0 || image.getHeight() <= 0) {
            return null;
        }
        if (pixels > options.maxPixels()) {
            warnings.add("Page " + f.page() + ": a " + image.getWidth() + "x" + image.getHeight()
                    + " image is larger than " + options.maxPixels() + " pixels and was left as it was");
            return null;
        }
        if (stream.getDictionaryObject(COSName.SMASK) instanceof COSStream soft
                && soft.getDictionaryObject(COSName.MATTE) != null) {
            warnings.add("Page " + f.page() + ": an image with a pre-blended soft mask was left as it was");
            return null;
        }
        PDColorSpace colourSpace = image.getColorSpace();
        boolean alreadyBilevel = image.getBitsPerComponent() == 1 && colourSpace.getNumberOfComponents() == 1
                && options.method() != Method.EDGES;
        BufferedImage source = image.getOpaqueImage(null, 1);
        Bitmap bitmap = Binarizer.binarize(source, options);
        source = null;
        Codecs.Encoded encoded = Codecs.encode(bitmap, options.codec());
        if (alreadyBilevel && encoded.data().length >= stream.getLength()) {
            return null;
        }
        COSStream keyMask = stream.getDictionaryObject(COSName.MASK) instanceof COSArray
                ? keyMask(document, image, options.codec()) : null;
        rewrite(stream, encoded, colour(encoded, bitmap, colourSpace, options.inkColour()),
                encoded.inkSample() == 1 && !options.inkColour() ? inverted() : null);
        if (keyMask != null) {
            stream.setItem(COSName.MASK, keyMask);
        }
        return encoded;
    }

    private static COSStream keyMask(PDDocument document, PDImageXObject image, Codec codec) throws IOException {
        BufferedImage keyed = image.getImage();
        Raster alpha = keyed.getAlphaRaster();
        int w = image.getWidth();
        int h = image.getHeight();
        Bitmap masked = Bitmap.blank(w, h);
        if (alpha != null) {
            byte[] bits = masked.packed();
            int stride = masked.stride();
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                alpha.getSamples(0, y, w, 1, 0, row);
                for (int x = 0; x < w; x++) {
                    if (row[x] == 0) {
                        bits[y * stride + (x >>> 3)] |= (byte) (0x80 >>> (x & 7));
                    }
                }
            }
        }
        Codecs.Encoded encoded = Codecs.encode(masked, codec);
        COSStream mask = document.getDocument().createCOSStream();
        mask.setItem(COSName.TYPE, COSName.XOBJECT);
        mask.setItem(COSName.SUBTYPE, COSName.IMAGE);
        mask.setInt(COSName.WIDTH, w);
        mask.setInt(COSName.HEIGHT, h);
        mask.setBoolean(COSName.IMAGE_MASK, true);
        rewrite(mask, encoded, null, encoded.inkSample() == 0 ? inverted() : null);
        mask.removeItem(COSName.COLORSPACE);
        return mask;
    }

    private static COSBase colour(Codecs.Encoded encoded, Bitmap bitmap, PDColorSpace source, boolean keepInk) {
        if (!keepInk) {
            return COSName.DEVICEGRAY;
        }
        byte[] palette;
        COSBase base;
        if (source instanceof PDSeparation) {
            base = source.getCOSObject();
            palette = new byte[2];
            palette[encoded.inkSample()] = (byte) 0xFF;
        } else {
            int rgb = bitmap.inkRgb();
            int r = (rgb >>> 16) & 0xFF;
            int g = (rgb >>> 8) & 0xFF;
            int b = rgb & 0xFF;
            boolean neutral = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 32;
            base = COSName.DEVICERGB;
            palette = new byte[6];
            int ink = encoded.inkSample() * 3;
            int paper = (1 - encoded.inkSample()) * 3;
            palette[paper] = palette[paper + 1] = palette[paper + 2] = (byte) 0xFF;
            if (!neutral) {
                palette[ink] = (byte) r;
                palette[ink + 1] = (byte) g;
                palette[ink + 2] = (byte) b;
            }
        }
        COSArray indexed = new COSArray();
        indexed.add(COSName.INDEXED);
        indexed.add(base);
        indexed.add(COSInteger.ONE);
        indexed.add(new COSString(palette));
        return indexed;
    }

    private static COSArray inverted() {
        COSArray decode = new COSArray();
        decode.add(COSInteger.ONE);
        decode.add(COSInteger.ZERO);
        return decode;
    }

    private static void rewrite(COSStream stream, Codecs.Encoded encoded, COSBase colourSpace, COSArray decode)
            throws IOException {
        for (COSName key : STALE) {
            stream.removeItem(key);
        }
        try (OutputStream os = stream.createRawOutputStream()) {
            os.write(encoded.data());
        }
        stream.setItem(COSName.FILTER, encoded.filter());
        COSDictionary parameters = encoded.parameters();
        if (parameters != null && parameters.size() > 0) {
            stream.setItem(COSName.DECODE_PARMS, parameters);
        }
        stream.setInt(COSName.BITS_PER_COMPONENT, 1);
        if (colourSpace != null) {
            stream.setItem(COSName.COLORSPACE, colourSpace);
        }
        if (decode != null) {
            stream.setItem(COSName.DECODE, decode);
        }
    }

    private static void move(Path part, Path out) throws IOException {
        try {
            Files.move(part, out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, out, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
