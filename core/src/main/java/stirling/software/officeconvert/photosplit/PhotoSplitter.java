package stirling.software.officeconvert.photosplit;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

public final class PhotoSplitter {

    public static final long MAX_PIXELS = 100_000_000L;

    public enum Method {
        STRAIGHTEN,
        SCRIPT
    }

    public record Options(int angleThreshold, int tolerance, int minArea, int minContourArea, int borderSize, float dpi,
            boolean overwrite, Method method) {

        public Options {
            Objects.requireNonNull(method, "method");
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
        Objects.requireNonNull(scan, "scan");
        Objects.requireNonNull(options, "options");
        if ((long) scan.getWidth() * scan.getHeight() > MAX_PIXELS) {
            throw new IllegalArgumentException("scan has " + (long) scan.getWidth() * scan.getHeight()
                    + " pixels; the limit is " + MAX_PIXELS);
        }
        ScanImage image = ScanImage.of(scan);
        return options.method() == Method.SCRIPT ? ScriptMethod.split(image, options) : StraightenMethod.split(image, options);
    }

    public static List<Path> split(Path input, Path outputDirectory, Options options) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(options, "options");
        Files.createDirectories(outputDirectory);
        String base = baseName(input);
        List<Path> written = new ArrayList<>();
        if (isPdf(input)) {
            try (PDDocument document = Loader.loadPDF(input.toFile())) {
                PDFRenderer renderer = new PDFRenderer(document);
                renderer.setSubsamplingAllowed(true);
                for (int page = 0; page < document.getNumberOfPages(); page++) {
                    float dpi = pageDpi(document.getPage(page).getCropBox(), options.dpi());
                    BufferedImage image = renderer.renderImageWithDPI(page, dpi, ImageType.RGB);
                    write(split(image, options), outputDirectory, base, written, options.overwrite());
                }
            }
        } else {
            write(split(readImage(input), options), outputDirectory, base, written, options.overwrite());
        }
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

    private static void write(List<Photo> photos, Path dir, String base, List<Path> written, boolean overwrite)
            throws IOException {
        List<Path> targets = new ArrayList<>();
        for (int i = 0; i < photos.size(); i++) {
            Path target = dir.resolve(base + "_" + (written.size() + i + 1) + ".png");
            if (!overwrite && Files.exists(target)) {
                throw new FileAlreadyExistsException(target.toString());
            }
            targets.add(target);
        }
        for (int i = 0; i < photos.size(); i++) {
            Path target = targets.get(i);
            try (OutputStream out = overwrite ? Files.newOutputStream(target)
                    : Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                if (!ImageIO.write(photos.get(i).image(), "png", out)) {
                    throw new IOException("no PNG writer available");
                }
            }
            written.add(target);
        }
    }

    private static BufferedImage readImage(Path input) throws IOException {
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
            return text.contains("%PDF-");
        }
    }

    private static String baseName(Path input) {
        String name = input.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
