package stirling.software.officeconvert.optimize;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.Restore;
import org.apache.pdfbox.contentstream.operator.state.Save;
import org.apache.pdfbox.contentstream.operator.state.SetMatrix;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceN;
import org.apache.pdfbox.pdmodel.graphics.color.PDIndexed;
import org.apache.pdfbox.pdmodel.graphics.color.PDSeparation;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

import stirling.software.officeconvert.extract.BrotliBudget;
import stirling.software.officeconvert.extract.ImageBudget;
import stirling.software.officeconvert.extract.PdfFiles;

final class Downsample {

    private static final int MIN_PIXELS = 48 * 48;

    private static final double RESAMPLE_HEADROOM = 1.15;

    private static final int MIN_COLOURS_FOR_JPEG = 128;

    private static final int SAMPLE_GRID = 128;

    private static final float TRIAL_QUALITY = 0.75f;

    private static final int MAX_JPEG_EDGE_ERROR = 32;

    private static final String JDK_PLUGINS = "com.sun.imageio.plugins.";

    private static final double MIN_SAVING = 0.92;

    private static final float GRAY_QUALITY = 0.9f;

    private static final COSName MATTE = COSName.getPDFName("Matte");

    record Applied(List<COSStream> images, int failed, int colourKeyed) {}

    private final PdfOptimizer.Images settings;

    private final boolean gray;

    private final Fresh fresh;

    private final List<COSStream> masks = new ArrayList<>();

    private COSArray srgb;

    private int colourKeyed;

    private Downsample(PdfOptimizer.Images settings, boolean gray, Fresh fresh) {
        this.settings = settings;
        this.gray = gray;
        this.fresh = fresh;
    }

    static Applied apply(PDDocument doc, PdfOptimizer.Images settings, boolean gray, Set<COSStream> more,
            Fresh fresh) throws IOException {
        Downsample run = new Downsample(settings, gray, fresh);
        Map<COSObjectKey, Double> needed = new LinkedHashMap<>();
        Map<COSObjectKey, COSStream> streams = new HashMap<>();
        if (settings != null) {
            Placements placements = placements(doc);
            needed.putAll(placements.needed);
            streams.putAll(placements.streams);
        }
        for (COSStream stream : more) {
            if (stream.getKey() != null && streams.putIfAbsent(stream.getKey(), stream) == null) {
                needed.put(stream.getKey(), Double.POSITIVE_INFINITY);
            }
        }
        List<COSStream> changed = new ArrayList<>();
        int failed = 0;
        for (Map.Entry<COSObjectKey, Double> e : needed.entrySet()) {
            PdfFiles.stopIfInterrupted();
            COSStream stream = streams.get(e.getKey());
            try {
                if (run.rewrite(stream, e.getValue())) {
                    changed.add(stream);
                }
            } catch (IOException | RuntimeException ex) {
                if (ex instanceof java.io.InterruptedIOException interrupted) {
                    throw interrupted;
                }
                failed++;
            }
        }
        changed.addAll(run.masks);
        return new Applied(changed, failed, run.colourKeyed);
    }

    private static Placements placements(PDDocument doc) throws IOException {
        Placements engine = new Placements(BrotliBudget.of(doc));
        for (PDPage page : doc.getPages()) {
            PdfFiles.stopIfInterrupted();
            try {
                if (engine.decodable(page.getCOSObject().getDictionaryObject(COSName.CONTENTS))) {
                    engine.processPage(page);
                }
            } catch (IOException | RuntimeException e) {
                if (e instanceof java.io.InterruptedIOException interrupted) {
                    throw interrupted;
                }
            }
        }
        return engine;
    }

    private static final class Placements extends PDFStreamEngine {

        private final Map<COSObjectKey, Double> needed = new LinkedHashMap<>();

        private final Map<COSObjectKey, COSStream> streams = new HashMap<>();

        private final Set<COSStream> forms = new HashSet<>();

        private final BrotliBudget budget;

        Placements(BrotliBudget budget) {
            this.budget = budget;
            addOperator(new Concatenate(this));
            addOperator(new Save(this));
            addOperator(new Restore(this));
            addOperator(new SetMatrix(this));
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            if (!"Do".equals(operator.getName())) {
                super.processOperator(operator, operands);
                return;
            }
            PDResources resources = getResources();
            if (operands.isEmpty() || !(operands.getFirst() instanceof COSName name) || resources == null) {
                return;
            }
            PDXObject xobject = resources.getXObject(name);
            if (xobject instanceof PDImageXObject image) {
                record(image);
            } else if (xobject instanceof PDFormXObject form && decodable(form.getCOSObject())
                    && forms.add(form.getCOSObject())) {
                try {
                    showForm(form);
                } finally {
                    forms.remove(form.getCOSObject());
                }
            }
        }

        boolean decodable(COSBase contents) throws IOException {
            if (contents instanceof COSStream stream) {
                return Recoder.decoded(stream, budget) != null;
            }
            if (contents instanceof COSArray array) {
                for (int i = 0; i < array.size(); i++) {
                    if (!decodable(array.getObject(i))) {
                        return false;
                    }
                }
            }
            return true;
        }

        private void record(PDImageXObject image) {
            COSObjectKey key = image.getCOSObject().getKey();
            if (key == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return;
            }
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            Point2D.Float origin = ctm.transformPoint(0, 0);
            double widthPt = origin.distance(ctm.transformPoint(1, 0));
            double heightPt = origin.distance(ctm.transformPoint(0, 1));
            double inchesPerPixel = Math.max(widthPt / 72 / image.getWidth(), heightPt / 72 / image.getHeight());
            needed.merge(key, inchesPerPixel, Math::max);
            streams.putIfAbsent(key, image.getCOSObject());
        }
    }

    private boolean rewrite(COSStream stream, double inchesPerPixel) throws IOException {
        PDImageXObject image = new PDImageXObject(new PDStream(stream), null);
        if (image.isStencil()) {
            return false;
        }
        long pixels = (long) image.getWidth() * image.getHeight();
        PDColorSpace colorSpace = image.getColorSpace();
        int components = colorSpace.getNumberOfComponents();
        String family = colorSpace.getName();
        boolean special = "Indexed".equals(family) || "Separation".equals(family) || "DeviceN".equals(family)
                || "Lab".equals(family);
        boolean toGray = gray && !rendersGray(colorSpace);
        boolean colourKey = stream.getDictionaryObject(COSName.MASK) instanceof COSArray
                || stream.getInt(COSName.SMASK_IN_DATA, 0) != 0
                || hasFilter(stream, COSName.JPX_DECODE) && !onlyJpx(stream);
        if (toGray && colourKey) {
            colourKeyed++;
            return false;
        }
        if (!toGray && (settings == null || image.getBitsPerComponent() < 8 || pixels < MIN_PIXELS || colourKey
                || components != 1 && components != 3 && components != 4 || special)) {
            return false;
        }

        double scale = settings == null ? 1.0 : Math.min(1.0, inchesPerPixel * settings.targetDpi());
        boolean resample = scale * RESAMPLE_HEADROOM < 1.0 && !hasMattedMask(stream);
        if (!resample) {
            scale = 1.0;
        }
        int subsampling = Math.max(1, (int) Math.floor(1.0 / scale / 1.5));
        long sampled = (long) ((image.getWidth() + subsampling - 1) / subsampling)
                * ((image.getHeight() + subsampling - 1) / subsampling);
        if (sampled * 4 > Recoder.MAX_DECODED_BYTES) {
            return false;
        }
        PDImage source = onlyJpx(stream) && ImageBudget.affordable(image) ? ImageBudget.fitted(image) : image;
        BufferedImage decoded = source != image ? source.getImage(null, subsampling)
                : image.getOpaqueImage(null, subsampling);
        if (decoded == null) {
            return false;
        }
        boolean lossySource = hasFilter(stream, COSName.DCT_DECODE) || hasFilter(stream, COSName.JPX_DECODE);
        boolean manyColours = colours(decoded) >= MIN_COLOURS_FOR_JPEG;
        if (!lossySource && !resample && !gray && !manyColours) {
            return false;
        }

        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        boolean grayOut = gray || components == 1;
        BufferedImage resized = resize(decoded, width, height, grayOut);
        boolean photographic = lossySource || manyColours && survivesJpeg(resized);
        if (!photographic && !resample && !gray) {
            return false;
        }
        float quality = settings == null ? GRAY_QUALITY : settings.quality();
        byte[] encoded = photographic ? jpeg(resized, quality) : flate(resized);
        if (!toGray && encoded.length >= stream.getLength() * MIN_SAVING) {
            return false;
        }
        if (toGray) {
            grayMatte(stream, colorSpace);
        }

        try (OutputStream out = stream.createRawOutputStream()) {
            out.write(encoded);
        }
        stream.setItem(COSName.FILTER, photographic ? COSName.DCT_DECODE : COSName.FLATE_DECODE);
        stream.removeItem(COSName.DECODE_PARMS);
        stream.removeItem(COSName.DECODE);
        stream.setInt(COSName.WIDTH, width);
        stream.setInt(COSName.HEIGHT, height);
        stream.setInt(COSName.BITS_PER_COMPONENT, 8);
        stream.setItem(COSName.COLORSPACE, grayOut ? COSName.DEVICEGRAY : srgb());
        return true;
    }

    private void grayMatte(COSStream stream, PDColorSpace colorSpace) throws IOException {
        if (!(stream.getDictionaryObject(COSName.SMASK) instanceof COSStream mask)
                || !(mask.getDictionaryObject(MATTE) instanceof COSArray matte) || mask.getKey() == null) {
            return;
        }
        float[] values = new float[matte.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = matte.getObject(i) instanceof org.apache.pdfbox.cos.COSNumber n ? n.floatValue() : 0;
        }
        float[] rgb = values.length == colorSpace.getNumberOfComponents() ? colorSpace.toRGB(values) : new float[3];
        COSArray grayMatte = new COSArray();
        grayMatte.add(new org.apache.pdfbox.cos.COSFloat(0.3f * rgb[0] + 0.59f * rgb[1] + 0.11f * rgb[2]));
        mask.setItem(MATTE, grayMatte);
        masks.add(mask);
    }

    private COSArray srgb() throws IOException {
        if (srgb == null) {
            COSStream profile = new COSStream();
            try (OutputStream out = profile.createOutputStream(COSName.FLATE_DECODE)) {
                out.write(ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData());
            }
            profile.setInt(COSName.N, 3);
            profile.setItem(COSName.ALTERNATE, COSName.DEVICERGB);
            srgb = new COSArray();
            srgb.add(COSName.ICCBASED);
            srgb.add(fresh.add(profile));
        }
        return srgb;
    }

    private static boolean rendersGray(PDColorSpace space) throws IOException {
        if (space instanceof PDIndexed indexed) {
            return rendersGray(indexed.getBaseColorSpace());
        }
        if (space instanceof PDSeparation separation) {
            return rendersGray(separation.getAlternateColorSpace());
        }
        if (space instanceof PDDeviceN deviceN) {
            return rendersGray(deviceN.getAlternateColorSpace());
        }
        return space.getNumberOfComponents() == 1;
    }

    private static boolean hasMattedMask(COSStream stream) {
        return stream.getDictionaryObject(COSName.SMASK) instanceof COSStream mask && mask.containsKey(MATTE);
    }

    private static boolean onlyJpx(COSStream stream) {
        COSBase filter = stream.getDictionaryObject(COSName.FILTER);
        if (filter instanceof COSArray array && array.size() == 1) {
            filter = array.getObject(0);
        }
        return COSName.JPX_DECODE.equals(filter);
    }

    private static boolean hasFilter(COSStream stream, COSName filter) {
        COSBase filters = stream.getDictionaryObject(COSName.FILTER);
        if (filters instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                if (filter.equals(array.getObject(i))) {
                    return true;
                }
            }
            return false;
        }
        return filter.equals(filters);
    }

    private static int colours(BufferedImage image) {
        int stepX = Math.max(1, image.getWidth() / SAMPLE_GRID);
        int stepY = Math.max(1, image.getHeight() / SAMPLE_GRID);
        Set<Integer> colours = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y += stepY) {
            for (int x = 0; x < image.getWidth(); x += stepX) {
                colours.add(image.getRGB(x, y) & 0xFFFFFF);
                if (colours.size() >= MIN_COLOURS_FOR_JPEG) {
                    return colours.size();
                }
            }
        }
        return colours.size();
    }

    private static boolean survivesJpeg(BufferedImage image) throws IOException {
        BufferedImage back = decodeJpeg(jpeg(image, TRIAL_QUALITY));
        if (back == null || back.getWidth() != image.getWidth() || back.getHeight() != image.getHeight()) {
            return false;
        }
        int stepX = Math.max(1, image.getWidth() / (SAMPLE_GRID * 2));
        int stepY = Math.max(1, image.getHeight() / (SAMPLE_GRID * 2));
        int[] histogram = new int[256];
        int samples = 0;
        for (int y = 0; y < image.getHeight(); y += stepY) {
            for (int x = 0; x < image.getWidth(); x += stepX) {
                int a = image.getRGB(x, y);
                int b = back.getRGB(x, y);
                int error = Math.max(Math.abs((a >> 16 & 0xFF) - (b >> 16 & 0xFF)),
                        Math.max(Math.abs((a >> 8 & 0xFF) - (b >> 8 & 0xFF)), Math.abs((a & 0xFF) - (b & 0xFF))));
                histogram[error]++;
                samples++;
            }
        }
        int allowed = samples / 100;
        for (int error = 255; error > MAX_JPEG_EDGE_ERROR; error--) {
            allowed -= histogram[error];
            if (allowed < 0) {
                return false;
            }
        }
        return true;
    }

    private static BufferedImage resize(BufferedImage source, int width, int height, boolean gray) {
        BufferedImage current = rawGrayToRgb(source);
        int w = current.getWidth();
        int h = current.getHeight();
        while (w / 2 >= width && h / 2 >= height) {
            w /= 2;
            h /= 2;
            current = draw(current, w, h);
        }
        if (w != width || h != height || current.getType() != BufferedImage.TYPE_INT_RGB) {
            current = draw(current, width, height);
        }
        return gray ? toGray(current) : current;
    }

    private static BufferedImage rawGrayToRgb(BufferedImage source) {
        if (source.getType() != BufferedImage.TYPE_BYTE_GRAY) {
            return source;
        }
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        WritableRaster raster = source.getRaster();
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int v = raster.getSample(x, y, 0);
                rgb.setRGB(x, y, v << 16 | v << 8 | v);
            }
        }
        return rgb;
    }

    private static BufferedImage toGray(BufferedImage rgb) {
        BufferedImage gray = new BufferedImage(rgb.getWidth(), rgb.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = gray.getRaster();
        for (int y = 0; y < rgb.getHeight(); y++) {
            for (int x = 0; x < rgb.getWidth(); x++) {
                int c = rgb.getRGB(x, y);
                raster.setSample(x, y, 0, ((c >> 16 & 0xFF) * 299 + (c >> 8 & 0xFF) * 587 + (c & 0xFF) * 114) / 1000);
            }
        }
        return gray;
    }

    private static BufferedImage draw(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    private static byte[] flate(BufferedImage image) {
        boolean gray = image.getType() == BufferedImage.TYPE_BYTE_GRAY;
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] samples = new byte[width * height * (gray ? 1 : 3)];
        int i = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (gray) {
                    samples[i++] = (byte) image.getRaster().getSample(x, y, 0);
                } else {
                    int rgb = image.getRGB(x, y);
                    samples[i++] = (byte) (rgb >> 16);
                    samples[i++] = (byte) (rgb >> 8);
                    samples[i++] = (byte) rgb;
                }
            }
        }
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(samples);
            deflater.finish();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(samples.length / 4 + 64);
            byte[] buffer = new byte[1 << 16];
            while (!deflater.finished()) {
                bytes.write(buffer, 0, deflater.deflate(buffer));
            }
            return bytes.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static BufferedImage decodeJpeg(byte[] data) throws IOException {
        ImageWriter writer = jpegWriter();
        ImageReader reader = ImageIO.getImageReader(writer);
        writer.dispose();
        if (reader == null) {
            return null;
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            reader.setInput(in, true, true);
            return reader.read(0);
        } finally {
            reader.dispose();
        }
    }

    private static ImageWriter jpegWriter() {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter first = writers.next();
        if (first.getClass().getName().startsWith(JDK_PLUGINS)) {
            return first;
        }
        while (writers.hasNext()) {
            ImageWriter next = writers.next();
            if (next.getClass().getName().startsWith(JDK_PLUGINS)) {
                first.dispose();
                return next;
            }
            next.dispose();
        }
        return first;
    }

    private static byte[] jpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = jpegWriter();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
