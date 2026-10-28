package stirling.software.officeconvert.photosplit;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.MultiPixelPackedSampleModel;
import java.awt.image.SampleModel;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.ImageInputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import stirling.software.officeconvert.extract.PdfFiles;

public final class PhotoSplitter {

    public static final long MAX_PIXELS = 100_000_000L;

    private static final long ANALYSIS_PIXELS = 4_500_000L;
    private static final long ANALYSIS_BYTES_PER_PIXEL = 24;

    public enum Method {
        STRAIGHTEN,
        SCRIPT
    }

    public record Options(int angleThreshold, int tolerance, int minArea, int minContourArea, int borderSize, float dpi,
            boolean overwrite, Method method) {

        public Options {
            Objects.requireNonNull(method, "method");
            if (tolerance < 0 || tolerance > 255) {
                throw new IllegalArgumentException("tolerance must be 0 to 255, was " + tolerance);
            }
            if (minArea < 0) {
                throw new IllegalArgumentException("minArea must be 0 or more, was " + minArea);
            }
            if (minContourArea < 0) {
                throw new IllegalArgumentException("minContourArea must be 0 or more, was " + minContourArea);
            }
            if (borderSize < 0) {
                throw new IllegalArgumentException("borderSize must be 0 or more, was " + borderSize);
            }
            if (!(dpi > 0) || dpi > 2400) {
                throw new IllegalArgumentException("dpi must be above 0 and at most 2400, was " + dpi);
            }
        }

        public static Options defaults() {
            return new Options(10, 30, 10000, 500, 0, 300f, false, Method.STRAIGHTEN);
        }

        public Options angleThreshold(int degrees) {
            return new Options(degrees, tolerance, minArea, minContourArea, borderSize, dpi, overwrite, method);
        }

        public Options tolerance(int levels) {
            return new Options(angleThreshold, levels, minArea, minContourArea, borderSize, dpi, overwrite, method);
        }

        public Options minArea(int pixels) {
            return new Options(angleThreshold, tolerance, pixels, minContourArea, borderSize, dpi, overwrite, method);
        }

        public Options minContourArea(int pixels) {
            return new Options(angleThreshold, tolerance, minArea, pixels, borderSize, dpi, overwrite, method);
        }

        public Options borderSize(int pixels) {
            return new Options(angleThreshold, tolerance, minArea, minContourArea, pixels, dpi, overwrite, method);
        }

        public Options dpi(float pdfRenderDpi) {
            return new Options(angleThreshold, tolerance, minArea, minContourArea, borderSize, pdfRenderDpi, overwrite, method);
        }

        public Options overwrite(boolean replaceExisting) {
            return new Options(angleThreshold, tolerance, minArea, minContourArea, borderSize, dpi, replaceExisting, method);
        }

        public Options method(Method how) {
            return new Options(angleThreshold, tolerance, minArea, minContourArea, borderSize, dpi, overwrite, how);
        }
    }

    public record Photo(BufferedImage image, List<Point2D.Double> corners, double angle) {

        public Photo {
            Objects.requireNonNull(image, "image");
            corners = List.copyOf(corners);
        }

        static Photo of(BufferedImage image, double x, double y, double w, double h, double angle) {
            return new Photo(image, List.of(new Point2D.Double(x, y), new Point2D.Double(x + w, y),
                    new Point2D.Double(x + w, y + h), new Point2D.Double(x, y + h)), angle);
        }
    }

    private PhotoSplitter() {}

    public static List<Photo> split(BufferedImage scan, Options options) {
        Objects.requireNonNull(options, "options");
        checkpoint();
        check(scan);
        return options.method() == Method.SCRIPT ? ScriptMethod.split(Source.of(scan), options)
                : StraightenMethod.split(Source.of(scan), options);
    }

    private static void check(BufferedImage scan) {
        Objects.requireNonNull(scan, "scan");
        if ((long) scan.getWidth() * scan.getHeight() > MAX_PIXELS) {
            throw new IllegalArgumentException("scan has " + (long) scan.getWidth() * scan.getHeight()
                    + " pixels; the limit is " + MAX_PIXELS);
        }
    }

    public static List<Path> split(Path input, Path outputDirectory, Options options) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(options, "options");
        try {
            checkpoint();
            return splitFile(input, outputDirectory, options);
        } catch (CancellationException stopped) {
            InterruptedIOException error = new InterruptedIOException("Photo splitting interrupted");
            error.initCause(stopped);
            throw error;
        }
    }

    static void checkpoint() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Photo splitting interrupted");
        }
    }

    private static List<Path> splitFile(Path input, Path outputDirectory, Options options) throws IOException {
        Files.createDirectories(outputDirectory);
        String base = baseName(input);
        List<Path> written = new ArrayList<>();
        if (isPdf(input)) {
            try (PDDocument document = PdfFiles.open(input, null)) {
                PDFRenderer renderer = new PDFRenderer(document);
                renderer.setSubsamplingAllowed(true);
                for (int page = 0; page < document.getNumberOfPages(); page++) {
                    checkpoint();
                    PDRectangle box = document.getPage(page).getCropBox();
                    float dpi = pageDpi(box, options.dpi());
                    long pixels = (long) Math.ceil(box.getWidth() / 72.0 * dpi) * (long) Math.ceil(box.getHeight() / 72.0 * dpi);
                    String name = input.getFileName() + " page " + (page + 1);
                    if (pixels > MAX_PIXELS) {
                        throw new IOException(name + " would be drawn with " + pixels + " pixels; the limit is " + MAX_PIXELS);
                    }
                    budget(name, peakBytes(pixels, 4 * pixels, false, options.method()));
                    write(source(renderer.renderImageWithDPI(page, dpi, ImageType.RGB)), options, outputDirectory, base, written);
                }
            }
        } else {
            write(source(readImage(input, options.method())), options, outputDirectory, base, written);
        }
        checkpoint();
        return written;
    }

    static float pageDpi(PDRectangle box, float dpi) {
        double w = Math.ceil(box.getWidth() / 72.0 * dpi);
        double h = Math.ceil(box.getHeight() / 72.0 * dpi);
        if (w * h <= MAX_PIXELS) {
            return dpi;
        }
        return (float) (dpi * Math.sqrt(MAX_PIXELS / (w * h)) * 0.999);
    }

    private static Source source(BufferedImage scan) {
        checkpoint();
        check(scan);
        return Source.of(scan);
    }

    private static void write(Source source, Options options, Path dir, String base, List<Path> written) throws IOException {
        checkpoint();
        List<Picture> photos = new ArrayList<>();
        if (options.method() == Method.SCRIPT) {
            for (ScriptMethod.Cut cut : ScriptMethod.cuts(source, options)) {
                photos.add(cut.picture());
            }
        } else {
            for (RotatedRect rect : StraightenMethod.find(source, options)) {
                photos.add(StraightenMethod.view(source, rect));
            }
        }
        List<Path> targets = new ArrayList<>();
        for (int i = 0; i < photos.size(); i++) {
            Path target = dir.resolve(base + "_" + (written.size() + i + 1) + ".png");
            if (!options.overwrite() && Files.exists(target)) {
                throw new FileAlreadyExistsException(target.toString());
            }
            targets.add(target);
        }
        for (int i = 0; i < photos.size(); i++) {
            Path target = targets.get(i);
            writeImage(photos.get(i), target, options.overwrite());
            written.add(target);
        }
    }

    static void writeImage(BufferedImage image, Path target, boolean overwrite) throws IOException {
        writeImage(Source.of(image), target, overwrite);
    }

    private static void writeImage(Picture image, Path target, boolean overwrite) throws IOException {
        checkpoint();
        Path part = Files.createTempFile(target.toAbsolutePath().getParent(), ".photo-", ".part");
        try {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(part), 1 << 16)) {
                PngWriter.write(image, out);
            }
            checkpoint();
            if (overwrite) {
                try {
                    Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Files.move(part, target);
            }
        } finally {
            Files.deleteIfExists(part);
        }
    }

    static long peakBytes(long pixels, long decoded, boolean converted, Method method) {
        long analysis = ANALYSIS_BYTES_PER_PIXEL * Math.min(pixels, ANALYSIS_PIXELS);
        long work = method == Method.SCRIPT ? Math.max(pixels, analysis) : analysis;
        if (!converted) {
            return decoded + work;
        }
        long copy = 4 * pixels;
        return Math.max(decoded + copy, copy + work);
    }

    static long decodedBytes(ImageTypeSpecifier type, long pixels) {
        SampleModel model = type.getSampleModel(1, 1);
        long bits = model instanceof MultiPixelPackedSampleModel packed ? packed.getPixelBitStride()
                : (long) model.getNumDataElements() * DataBuffer.getDataTypeSize(model.getDataType());
        return (bits * pixels + 7) / 8;
    }

    private static boolean converted(ImageTypeSpecifier type) {
        return switch (type.getBufferedImageType()) {
            case BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_3BYTE_BGR,
                    BufferedImage.TYPE_4BYTE_ABGR, BufferedImage.TYPE_BYTE_GRAY -> false;
            default -> true;
        };
    }

    static void budget(String name, long bytes) throws IOException {
        long limit = Runtime.getRuntime().maxMemory();
        if (limit != Long.MAX_VALUE && bytes > limit) {
            throw new IOException(name + " needs about " + (bytes >> 20) + " MB of memory to split; the limit is "
                    + (limit >> 20) + " MB");
        }
    }

    private static BufferedImage readImage(Path input, Method method) throws IOException {
        try (InputStream in = Files.newInputStream(input); ImageInputStream stream = ImageIO.createImageInputStream(in)) {
            if (stream == null) {
                throw new IOException("cannot read " + input);
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new IOException(input.getFileName() + " is not a PDF or an image this reader knows");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > MAX_PIXELS) {
                    throw new IOException(input.getFileName() + " has " + pixels + " pixels; the limit is " + MAX_PIXELS);
                }
                Iterator<ImageTypeSpecifier> types = reader.getImageTypes(0);
                if (types != null && types.hasNext()) {
                    ImageTypeSpecifier type = types.next();
                    budget(input.getFileName().toString(), peakBytes(pixels, decodedBytes(type, pixels), converted(type), method));
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    private static boolean isPdf(Path input) throws IOException {
        try (InputStream in = Files.newInputStream(input)) {
            byte[] head = in.readNBytes(1024);
            String text = new String(head, StandardCharsets.ISO_8859_1);
            return !isImage(text) && text.contains("%PDF-");
        }
    }

    private static boolean isImage(String head) {
        return head.startsWith("\u0089PNG\r\n\u001A\n") || head.startsWith("\u00FF\u00D8\u00FF") || head.startsWith("II*\0")
                || head.startsWith("MM\0*") || head.startsWith("GIF87a") || head.startsWith("GIF89a") || head.startsWith("BM")
                || head.startsWith("RIFF") && head.startsWith("WEBP", 8);
    }

    private static String baseName(Path input) {
        String name = input.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
