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
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
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

    private static final float LOWEST_GRAY_QUALITY = 0.5f;

    private static final float GRAY_QUALITY_STEP = 0.1f;

    private static final int PARMS_BYTES = 48;

    private static final double MAX_PALETTE_ERROR = 6;

    private static final double MAX_QUANTISED_ERROR = 2;

    private static final COSName MATTE = COSName.getPDFName("Matte");

    private static final int SRGB_PROFILE_BYTES = deflated(ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData()).length;

    record Applied(List<COSStream> images, int failed, int colourKeyed) {}

    private final PdfOptimizer.Images settings;

    private final boolean gray;

    private final Fresh fresh;

    private final BrotliBudget budget;

    private final List<COSStream> masks = new ArrayList<>();

    private final Map<COSStream, Double> waitingForProfile = new LinkedHashMap<>();

    private final Map<COSStream, Map<String, COSObject>> resampledMasks = new HashMap<>();

    private COSArray srgb;

    private int colourKeyed;

    private Downsample(PdfOptimizer.Images settings, boolean gray, Fresh fresh, BrotliBudget budget) {
        this.settings = settings;
        this.gray = gray;
        this.fresh = fresh;
        this.budget = budget;
    }

    static Applied apply(PDDocument doc, PdfOptimizer.Images settings, boolean gray, Set<COSStream> more,
            Fresh fresh) throws IOException {
        Downsample run = new Downsample(settings, gray, fresh, BrotliBudget.of(doc));
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
            failed += run.tryRewrite(streams.get(e.getKey()), e.getValue(), changed);
        }
        if (run.srgb != null) {
            for (Map.Entry<COSStream, Double> e : run.waitingForProfile.entrySet()) {
                failed += run.tryRewrite(e.getKey(), e.getValue(), changed);
            }
        }
        changed.addAll(run.masks);
        return new Applied(changed, failed, run.colourKeyed);
    }

    private int tryRewrite(COSStream stream, double inchesPerPixel, List<COSStream> changed) throws IOException {
        PdfFiles.stopIfInterrupted();
        try {
            if (rewrite(stream, inchesPerPixel)) {
                changed.add(stream);
            }
            return 0;
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof java.io.InterruptedIOException interrupted) {
                throw interrupted;
            }
            return 1;
        }
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

    private PDImageXObject withoutBrotli(COSStream stream) throws IOException {
        byte[] data = Recoder.decoded(stream, budget);
        if (data == null) {
            return null;
        }
        COSStream plain = new COSStream();
        for (Map.Entry<COSName, COSBase> entry : stream.entrySet()) {
            COSName key = entry.getKey();
            if (!COSName.FILTER.equals(key) && !COSName.DECODE_PARMS.equals(key) && !COSName.LENGTH.equals(key)) {
                plain.setItem(key, entry.getValue());
            }
        }
        try (OutputStream out = plain.createRawOutputStream()) {
            out.write(data);
        }
        return new PDImageXObject(new PDStream(plain), null);
    }

    private record Encoded(byte[] data, COSName filter, COSDictionary parms, int bitsPerComponent, int width,
            int height, Output space, int[] palette) {}

    private enum Output { GRAY, RGB, SRGB_PALETTE, SOURCE }

    private boolean rewrite(COSStream stream, double inchesPerPixel) throws IOException {
        PDImageXObject image = new PDImageXObject(new PDStream(stream), null);
        if (image.isStencil()) {
            return false;
        }
        long pixels = (long) image.getWidth() * image.getHeight();
        PDColorSpace colorSpace = image.getColorSpace();
        int components = colorSpace.getNumberOfComponents();
        String family = colorSpace.getName();
        boolean indexed = colorSpace instanceof PDIndexed;
        boolean special = indexed || "Separation".equals(family) || "DeviceN".equals(family) || "Lab".equals(family);
        boolean toGray = gray && !rendersGray(colorSpace);
        boolean colourKey = stream.getDictionaryObject(COSName.MASK) instanceof COSArray
                || stream.getInt(COSName.SMASK_IN_DATA, 0) != 0
                || hasFilter(stream, COSName.JPX_DECODE) && !onlyJpx(stream);
        if (toGray && colourKey) {
            colourKeyed++;
            return false;
        }
        boolean plainSamples = image.getBitsPerComponent() >= 8 && (components == 1 || components == 3 || components == 4);
        if (!toGray && (settings == null || pixels < MIN_PIXELS || colourKey || !special && !plainSamples)) {
            return false;
        }

        double scale = settings == null ? 1.0 : Math.min(1.0, inchesPerPixel * settings.targetDpi());
        boolean resample = scale * RESAMPLE_HEADROOM < 1.0 && !hasMattedMask(stream);
        if (!resample) {
            scale = 1.0;
        }
        if (special && (!resample && !toGray || invisible(colorSpace))) {
            return false;
        }
        int subsampling = Math.max(1, (int) Math.floor(1.0 / scale / 1.5));
        long sampled = (long) ((image.getWidth() + subsampling - 1) / subsampling)
                * ((image.getHeight() + subsampling - 1) / subsampling);
        if (sampled * 4 > Recoder.MAX_DECODED_BYTES) {
            return false;
        }
        PDImageXObject readable = Filters.brotli(stream.getItem(COSName.FILTER)) ? withoutBrotli(stream) : image;
        if (readable == null) {
            return false;
        }
        PDImage source = onlyJpx(stream) && ImageBudget.affordable(readable) ? ImageBudget.fitted(readable) : readable;
        BufferedImage decoded = source != readable ? source.getImage(null, subsampling)
                : readable.getOpaqueImage(null, subsampling);
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
        boolean grayOut = gray || (special ? neutral(decoded) : components == 1);
        Resized resized = resize(decoded, width, height, grayOut);
        boolean photographic = lossySource
                || manyColours && (survivesJpeg(resized.image) || resample && survivesJpeg(rawGrayToRgb(decoded)));
        if (!photographic && !resample && !gray) {
            return false;
        }
        float quality = settings == null ? GRAY_QUALITY : settings.quality();
        List<Encoded> options = new ArrayList<>();
        if (photographic) {
            byte[] jpeg = jpeg(resized.image, quality);
            if (toGray) {
                jpeg = noLargerThan(resized.image, quality, jpeg, stream.getLength());
            }
            options.add(new Encoded(jpeg, COSName.DCT_DECODE, null, 8, width, height,
                    grayOut ? Output.GRAY : Output.RGB, null));
            if (resample && !manyColours) {
                lossless(resized.image, grayOut, false, options);
            }
        } else {
            lossless(resized.image, grayOut, resample, options);
            if (resized.halved != null) {
                lossless(resized.halved, grayOut, true, options);
            }
        }
        int sourceBits = image.getBitsPerComponent();
        if (indexed && !toGray && !stream.containsKey(COSName.DECODE)
                && (sourceBits == 1 || sourceBits == 2 || sourceBits == 4 || sourceBits == 8)) {
            Encoded own = sourcePalette((PDIndexed) colorSpace, sourceBits, resized.image);
            if (own != null) {
                options.add(own);
            }
        }
        Encoded best = options.getFirst();
        for (Encoded option : options) {
            if (cost(option) < cost(best)) {
                best = option;
            }
        }
        long profile = profileCost(best);
        if (!toGray && cost(best) >= stream.getLength() * MIN_SAVING) {
            if (profile > 0 && cost(best) - profile < stream.getLength() * MIN_SAVING) {
                waitingForProfile.put(stream, inchesPerPixel);
            }
            return false;
        }
        if (toGray) {
            grayMatte(stream, colorSpace);
        }
        if (best.width != image.getWidth() || best.height != image.getHeight()) {
            resampleMask(stream, best.width, best.height);
        }

        try (OutputStream out = stream.createRawOutputStream()) {
            out.write(best.data);
        }
        stream.setItem(COSName.FILTER, best.filter);
        stream.setItem(COSName.DECODE_PARMS, best.parms);
        stream.removeItem(COSName.DECODE);
        stream.setInt(COSName.WIDTH, best.width);
        stream.setInt(COSName.HEIGHT, best.height);
        stream.setInt(COSName.BITS_PER_COMPONENT, best.bitsPerComponent);
        stream.setItem(COSName.COLORSPACE, switch (best.space) {
            case GRAY -> COSName.DEVICEGRAY;
            case RGB -> srgb();
            case SRGB_PALETTE -> indexedSrgb(best.palette);
            case SOURCE -> stream.getDictionaryObject(COSName.COLORSPACE);
        });
        return true;
    }

    private static void lossless(BufferedImage image, boolean grayOut, boolean quantise, List<Encoded> options) {
        int width = image.getWidth();
        int height = image.getHeight();
        options.add(flate(samples(image), grayOut ? 1 : 3, 8, width, height, grayOut ? Output.GRAY : Output.RGB, null));
        if (grayOut) {
            return;
        }
        int[] palette = Palette.exact(image);
        boolean exact = palette != null;
        if (palette == null && quantise) {
            palette = Palette.medianCut(image, Palette.MAX_COLOURS);
        }
        if (palette != null) {
            int bits = Palette.bitsFor(palette.length);
            byte[] indices = Palette.indices(image, palette);
            if (!exact && Palette.meanError(image, palette, indices) > MAX_QUANTISED_ERROR) {
                return;
            }
            byte[] packed = Palette.pack(indices, width, height, bits);
            options.add(flate(packed, 1, bits, width, height, Output.SRGB_PALETTE, palette));
        }
    }

    private long profileCost(Encoded option) {
        boolean addsProfile = option.space == Output.RGB || option.space == Output.SRGB_PALETTE;
        return !addsProfile || srgb != null ? 0 : SRGB_PROFILE_BYTES;
    }

    private long cost(Encoded option) {
        return option.data.length + profileCost(option) + (option.palette == null ? 0 : option.palette.length * 3L)
                + (option.parms == null ? 0 : PARMS_BYTES);
    }

    private static boolean neutral(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            return true;
        }
        int[] row = new int[image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            Palette.rgbRow(image, y, row);
            for (int x = 0; x < image.getWidth(); x++) {
                int c = row[x];
                int r = c >> 16 & 0xFF;
                if (r != (c >> 8 & 0xFF) || r != (c & 0xFF)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Encoded sourcePalette(PDIndexed space, int bits, BufferedImage image) throws IOException {
        int count = space.getCOSObject() instanceof COSArray array && array.getObject(2) instanceof COSNumber hival
                ? Math.min(hival.intValue() + 1, 1 << bits) : 1 << bits;
        int[] palette = new int[count];
        for (int i = 0; i < count; i++) {
            float[] rgb = space.toRGB(new float[] {i});
            palette[i] = channel(rgb[0]) << 16 | channel(rgb[1]) << 8 | channel(rgb[2]);
        }
        int width = image.getWidth();
        int height = image.getHeight();
        BufferedImage rgb = rawGrayToRgb(image);
        byte[] indices = Palette.indices(rgb, palette);
        if (Palette.meanError(rgb, palette, indices) > MAX_PALETTE_ERROR) {
            return null;
        }
        byte[] packed = Palette.pack(indices, width, height, bits);
        return flate(packed, 1, bits, width, height, Output.SOURCE, null);
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255)));
    }

    private static Encoded flate(byte[] samples, int colours, int bits, int width, int height, Output space,
            int[] palette) {
        byte[] plain = deflated(samples);
        Predictors.Layout layout = new Predictors.Layout(colours, bits, width);
        byte[] predicted = Predictors.deflatePng(samples, layout, Predictors.ADAPTIVE, Deflater.BEST_COMPRESSION);
        if (plain.length <= predicted.length) {
            return new Encoded(plain, COSName.FLATE_DECODE, null, bits, width, height, space, palette);
        }
        COSDictionary parms = new COSDictionary();
        parms.setInt(COSName.PREDICTOR, 15);
        parms.setInt(COSName.COLORS, colours);
        parms.setInt(COSName.BITS_PER_COMPONENT, bits);
        parms.setInt(COSName.COLUMNS, width);
        return new Encoded(predicted, COSName.FLATE_DECODE, parms, bits, width, height, space, palette);
    }

    private COSArray indexedSrgb(int[] palette) throws IOException {
        COSArray space = new COSArray();
        space.add(COSName.INDEXED);
        space.add(srgb());
        space.add(COSInteger.get(palette.length - 1));
        space.add(new COSString(Palette.lookup(palette)));
        return space;
    }

    private void resampleMask(COSStream stream, int width, int height) throws IOException {
        if (!(stream.getDictionaryObject(COSName.SMASK) instanceof COSStream mask) || mask.containsKey(MATTE)
                || mask.containsKey(COSName.DECODE)) {
            return;
        }
        String size = width + "x" + height;
        Map<String, COSObject> sized = resampledMasks.computeIfAbsent(mask, k -> new HashMap<>());
        COSObject done = sized.get(size);
        if (done != null) {
            stream.setItem(COSName.SMASK, done);
            return;
        }
        PDImageXObject maskImage = new PDImageXObject(new PDStream(mask), null);
        if (maskImage.getWidth() <= width || maskImage.getHeight() <= height
                || (long) maskImage.getWidth() * maskImage.getHeight() * 4 > Recoder.MAX_DECODED_BYTES) {
            return;
        }
        PDImageXObject readable = Filters.brotli(mask.getItem(COSName.FILTER)) ? withoutBrotli(mask) : maskImage;
        BufferedImage decoded = readable == null ? null : readable.getImage();
        if (decoded == null) {
            return;
        }
        BufferedImage resized = resize(decoded, width, height, true).image;
        Encoded encoded = flate(samples(resized), 1, 8, width, height, Output.GRAY, null);
        if (encoded.data.length >= mask.getLength()) {
            return;
        }
        COSStream copy = new COSStream();
        for (Map.Entry<COSName, COSBase> entry : mask.entrySet()) {
            if (!COSName.FILTER.equals(entry.getKey()) && !COSName.DECODE_PARMS.equals(entry.getKey())
                    && !COSName.LENGTH.equals(entry.getKey())) {
                copy.setItem(entry.getKey(), entry.getValue());
            }
        }
        try (OutputStream out = copy.createRawOutputStream()) {
            out.write(encoded.data);
        }
        copy.setItem(COSName.FILTER, COSName.FLATE_DECODE);
        copy.setItem(COSName.DECODE_PARMS, encoded.parms);
        copy.setInt(COSName.WIDTH, width);
        copy.setInt(COSName.HEIGHT, height);
        copy.setInt(COSName.BITS_PER_COMPONENT, 8);
        copy.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
        COSObject added = fresh.add(copy);
        sized.put(size, added);
        stream.setItem(COSName.SMASK, added);
    }

    private static byte[] noLargerThan(BufferedImage image, float quality, byte[] encoded, long limit)
            throws IOException {
        byte[] best = encoded;
        for (float q = quality - GRAY_QUALITY_STEP; best.length > limit && q >= LOWEST_GRAY_QUALITY - 1e-3f;
                q -= GRAY_QUALITY_STEP) {
            byte[] smaller = jpeg(image, q);
            if (smaller.length < best.length) {
                best = smaller;
            }
        }
        return best;
    }

    private void grayMatte(COSStream stream, PDColorSpace colorSpace) throws IOException {
        if (!(stream.getDictionaryObject(COSName.SMASK) instanceof COSStream mask)
                || !(mask.getDictionaryObject(MATTE) instanceof COSArray matte) || mask.getKey() == null) {
            return;
        }
        float[] values = new float[matte.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = matte.getObject(i) instanceof COSNumber n ? n.floatValue() : 0;
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

    private static boolean invisible(PDColorSpace space) throws IOException {
        if (space instanceof PDIndexed indexed) {
            return invisible(indexed.getBaseColorSpace());
        }
        if (space instanceof PDSeparation separation) {
            return "None".equals(separation.getColorantName());
        }
        if (space instanceof PDDeviceN deviceN) {
            return deviceN.getColorantNames().stream().allMatch("None"::equals);
        }
        return false;
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

    private record Resized(BufferedImage image, BufferedImage halved) {}

    private static Resized resize(BufferedImage source, int width, int height, boolean gray) {
        BufferedImage current = rawGrayToRgb(source);
        int w = current.getWidth();
        int h = current.getHeight();
        boolean halvedOnce = false;
        while (w / 2 >= width && h / 2 >= height) {
            w /= 2;
            h /= 2;
            current = draw(current, w, h);
            halvedOnce = true;
        }
        BufferedImage halved = halvedOnce && (w != width || h != height) ? current : null;
        if (w != width || h != height || current.getType() != BufferedImage.TYPE_INT_RGB) {
            current = draw(current, width, height);
        }
        return gray ? new Resized(toGray(current), halved == null ? null : toGray(halved))
                : new Resized(current, halved);
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
        int[] row = new int[rgb.getWidth()];
        for (int y = 0; y < rgb.getHeight(); y++) {
            Palette.rgbRow(rgb, y, row);
            for (int x = 0; x < rgb.getWidth(); x++) {
                int c = row[x];
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

    private static byte[] samples(BufferedImage image) {
        boolean gray = image.getType() == BufferedImage.TYPE_BYTE_GRAY;
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] samples = new byte[width * height * (gray ? 1 : 3)];
        int i = 0;
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            if (!gray) {
                Palette.rgbRow(image, y, row);
            }
            for (int x = 0; x < width; x++) {
                if (gray) {
                    samples[i++] = (byte) image.getRaster().getSample(x, y, 0);
                } else {
                    int rgb = row[x];
                    samples[i++] = (byte) (rgb >> 16);
                    samples[i++] = (byte) (rgb >> 8);
                    samples[i++] = (byte) rgb;
                }
            }
        }
        return samples;
    }

    private static byte[] deflated(byte[] samples) {
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
