package stirling.software.officeconvert.extract;

import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.DecodeOptions;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceN;
import org.apache.pdfbox.pdmodel.graphics.color.PDSeparation;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

public final class ImageBudget {

    public static final long MAX_DECODED_BYTES = 256L * 1024 * 1024;

    public static final long MAX_JPX_SAMPLES = 1L << 26;

    private static final int JPX_HEADER_SCAN = 1 << 20;

    private ImageBudget() {}

    public static final int MAX_SUBSAMPLING = 16;

    private static final Map<COSBase, Integer> STEPS = Collections.synchronizedMap(new WeakHashMap<>());

    public static boolean affordable(PDImage image) {
        return step(image) == 1;
    }

    public static PDImage fitted(PDImage image) {
        int step = step(image);
        if (step == 1) {
            return image;
        }
        if (step < 1 || !(image instanceof PDImageXObject x) || x.getCOSObject().containsKey(COSName.OC)) {
            return null;
        }
        return new Subsampled(x, step);
    }

    static int step(PDImage image) {
        boolean jpx = jpx(image);
        long limit = jpx ? MAX_JPX_SAMPLES : MAX_DECODED_BYTES;
        if (!(image instanceof PDImageXObject x)) {
            return codedBytes(image) <= limit && cost(image, 1, jpx, null) <= MAX_DECODED_BYTES ? 1 : 0;
        }
        Integer known = STEPS.get(x.getCOSObject());
        if (known == null) {
            Sizes sizes = sizes(x);
            int most = jpx ? 1 : MAX_SUBSAMPLING;
            int fit = 0;
            for (int s = 1; s <= most && fit == 0; s++) {
                if (cost(image, s, jpx, sizes) <= MAX_DECODED_BYTES) {
                    fit = s;
                }
            }
            known = fit > 0 && codedBytes(image) <= limit && inflatesWithin(image) ? fit : 0;
            STEPS.put(x.getCOSObject(), known);
        }
        return known;
    }

    private static long cost(PDImage image, int step, boolean jpx, Sizes sizes) {
        return add(jpx ? 0 : decodedBytes(image, step), maskedRenderBytes(image, step, sizes));
    }

    private record Subsampled(PDImageXObject image, int step) implements PDImage {

        @Override
        public BufferedImage getImage() throws IOException {
            return image.getImage(null, step);
        }

        @Override
        public BufferedImage getImage(Rectangle region, int subsampling) throws IOException {
            return image.getImage(region, Math.max(step, subsampling));
        }

        @Override
        public WritableRaster getRawRaster() throws IOException {
            throw new IOException("The image is too large to read at full size");
        }

        @Override
        public BufferedImage getRawImage() throws IOException {
            throw new IOException("The image is too large to read at full size");
        }

        @Override
        public InputStream createInputStream() throws IOException {
            return image.createInputStream();
        }

        @Override
        public InputStream createInputStream(List<String> stopFilters) throws IOException {
            return image.createInputStream(stopFilters);
        }

        @Override
        public InputStream createInputStream(DecodeOptions options) throws IOException {
            return image.createInputStream(options);
        }

        @Override
        public boolean isEmpty() {
            return image.isEmpty();
        }

        @Override
        public boolean isStencil() {
            return image.isStencil();
        }

        @Override
        public void setStencil(boolean stencil) {
            image.setStencil(stencil);
        }

        @Override
        public int getBitsPerComponent() {
            return image.getBitsPerComponent();
        }

        @Override
        public void setBitsPerComponent(int bits) {
            image.setBitsPerComponent(bits);
        }

        @Override
        public PDColorSpace getColorSpace() throws IOException {
            return image.getColorSpace();
        }

        @Override
        public void setColorSpace(PDColorSpace space) {
            image.setColorSpace(space);
        }

        @Override
        public int getHeight() {
            return image.getHeight();
        }

        @Override
        public void setHeight(int height) {
            image.setHeight(height);
        }

        @Override
        public int getWidth() {
            return image.getWidth();
        }

        @Override
        public void setWidth(int width) {
            image.setWidth(width);
        }

        @Override
        public void setDecode(COSArray decode) {
            image.setDecode(decode);
        }

        @Override
        public COSArray getDecode() {
            return image.getDecode();
        }

        @Override
        public boolean getInterpolate() {
            return image.getInterpolate();
        }

        @Override
        public void setInterpolate(boolean interpolate) {
            image.setInterpolate(interpolate);
        }

        @Override
        public String getSuffix() {
            return image.getSuffix();
        }

        @Override
        public COSStream getCOSObject() {
            return image.getCOSObject();
        }

        @Override
        public BufferedImage getStencilImage(Paint paint) throws IOException {
            BufferedImage mask = image.getImage(null, step);
            int w = mask.getWidth();
            int h = mask.getHeight();
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            try {
                g.setPaint(paint);
                g.fillRect(0, 0, w, h);
            } finally {
                g.dispose();
            }
            WritableRaster alpha = out.getAlphaRaster();
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                mask.getRGB(0, y, w, 1, row, 0, w);
                for (int x = 0; x < w; x++) {
                    row[x] = 255 - (row[x] & 0xFF);
                }
                alpha.setSamples(0, y, w, 1, 0, row);
            }
            return out;
        }
    }

    private static boolean jpx(PDImage image) {
        return "jpx".equals(image.getSuffix());
    }

    private static boolean jpx(COSStream stream) {
        List<COSName> filters = filters(stream);
        return !filters.isEmpty() && COSName.JPX_DECODE.equals(filters.get(filters.size() - 1));
    }

    private static List<COSName> filters(COSStream stream) {
        COSBase f = stream.getFilters();
        List<COSName> out = new ArrayList<>();
        if (f instanceof COSName n) {
            out.add(n);
        } else if (f instanceof COSArray a) {
            for (COSBase b : a) {
                if (b instanceof COSName n) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    private static byte[] jpxHeader(COSStream stream) throws IOException {
        List<COSName> filters = filters(stream);
        InputStream data = stream.createRawInputStream();
        try {
            for (int i = 0; i < filters.size() - 1; i++) {
                Head out = new Head();
                try {
                    FilterFactory.INSTANCE.getFilter(filters.get(i)).decode(data, out, stream, i);
                } catch (IOException | RuntimeException stopped) {
                }
                data.close();
                data = new ByteArrayInputStream(out.bytes());
            }
            return data.readNBytes(JPX_HEADER_SCAN);
        } finally {
            data.close();
        }
    }

    private static final class Head extends OutputStream {

        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            int room = JPX_HEADER_SCAN - kept.size();
            kept.write(b, off, Math.min(room, len));
            if (len >= room) {
                throw new IOException("JPX header window filled");
            }
        }

        byte[] bytes() {
            return kept.toByteArray();
        }
    }

    private static boolean inflatesWithin(PDImage image) {
        if (!(image instanceof PDImageXObject x)) {
            return true;
        }
        COSStream mask = appliedMask(x.getCOSObject());
        return StreamGuard.fits(x.getCOSObject()) && (mask == null || StreamGuard.fits(mask));
    }

    private static COSStream appliedMask(COSStream image) {
        COSStream soft = image.getCOSStream(COSName.SMASK);
        if (soft != null) {
            return soft;
        }
        COSStream mask = image.getCOSStream(COSName.MASK);
        return mask != null && mask.getBoolean(COSName.IMAGE_MASK, false) ? mask : null;
    }

    private static long[] size(COSStream stream) {
        if (!jpx(stream)) {
            return new long[] {Math.max(0, stream.getInt(COSName.WIDTH, 0)), Math.max(0, stream.getInt(COSName.HEIGHT, 0))};
        }
        try {
            return jpxSize(jpxHeader(stream));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Bytes {@code getImage()} allocates for a masked image or a stencil: it scales the base image
     * and its {@code /SMask} or stencil {@code /Mask} to a common size and builds a
     * 4-byte-per-pixel raster. {@link #decodedBytes} measures the base image only, so the mask
     * and stencil dimensions are accounted for here, as are the grey key mask and ARGB copy a
     * colour-key {@code /Mask} array adds. Returns 0 when no mask applies, leaving plain images to
     * {@code decodedBytes}.
     */
    static long maskedRenderBytes(PDImage image) {
        return maskedRenderBytes(image, 1);
    }

    static long maskedRenderBytes(PDImage image, int step) {
        return maskedRenderBytes(image, step, image instanceof PDImageXObject x ? sizes(x) : null);
    }

    private record Sizes(long[] base, COSStream mask, long[] applied) {}

    private static Sizes sizes(PDImageXObject image) {
        COSStream mask = appliedMask(image.getCOSObject());
        long[] base = size(image.getCOSObject());
        return new Sizes(base, mask, mask == null ? base : size(mask));
    }

    private static long maskedRenderBytes(PDImage image, int step, Sizes sizes) {
        boolean stencil = isStencil(image);
        if (!(image instanceof PDImageXObject x)) {
            return stencil ? samples(shrunk(image.getWidth(), step), shrunk(image.getHeight(), step), 4) : 0;
        }
        COSStream mask = sizes.mask();
        if (mask == null && !stencil) {
            return x.getCOSObject().getDictionaryObject(COSName.MASK) instanceof COSArray
                    ? colourKeyBytes(sizes.base(), components(image), step) : 0;
        }
        long[] base = sizes.base();
        long[] applied = sizes.applied();
        if (base == null || applied == null) {
            return Long.MAX_VALUE;
        }
        long composite = samples(Math.max(shrunk(base[0], step), shrunk(applied[0], step)),
                Math.max(shrunk(base[1], step), shrunk(applied[1], step)), 4);
        if (mask == null) {
            return composite;
        }
        long own = samples(shrunk(applied[0], step), shrunk(applied[1], step), 5);
        long packed = filters(mask).isEmpty() ? 0
                : packed(applied[0], applied[1], mask.getInt(COSName.BITS_PER_COMPONENT, 8), 1,
                        subsampledByFilter(mask) ? step : 1);
        return add(composite, add(own, packed));
    }

    static long decodedBytes(PDImage image) {
        return decodedBytes(image, 1);
    }

    static long decodedBytes(PDImage image, int step) {
        long unpacked = samples(shrunk(image.getWidth(), step), shrunk(image.getHeight(), step), components(image) + 4);
        if (!(image.getCOSObject() instanceof COSStream s) || filters(s).isEmpty()) {
            return unpacked;
        }
        boolean stencil = isStencil(image);
        int bits = stencil ? 1 : image.getBitsPerComponent();
        long packed = packed(image.getWidth(), image.getHeight(), bits <= 0 ? 8 : bits, stencil ? 1 : channels(image),
                subsampledByFilter(s) ? step : 1);
        return add(unpacked, packed);
    }

    private static long packed(long width, long height, int bits, int channels, int step) {
        long rowBits = samples(shrunk(width, step), 1, Math.max(1, bits) * Math.max(1, channels));
        return rowBits == Long.MAX_VALUE ? Long.MAX_VALUE : samples((rowBits + 7) / 8, shrunk(height, step), 1);
    }

    private static boolean subsampledByFilter(COSStream stream) {
        List<COSName> filters = filters(stream);
        if (filters.isEmpty()) {
            return false;
        }
        String last = filters.get(filters.size() - 1).getName();
        return switch (last) {
            case "DCTDecode", "DCT", "JPXDecode", "JBIG2Decode" -> true;
            default -> false;
        };
    }

    private static long add(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    private static boolean isStencil(PDImage image) {
        try {
            return image.isStencil();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static long shrunk(long length, int step) {
        return length <= 0 ? length : (length + step - 1) / step;
    }

    private static long colourKeyBytes(long[] base, int components, int step) {
        return base == null ? Long.MAX_VALUE : samples(shrunk(base[0], step), shrunk(base[1], step), components + 9);
    }

    private static int components(PDImage image) {
        try {
            if (image.isStencil() || image.getColorSpace() == null) {
                return 1;
            }
            PDColorSpace space = image.getColorSpace();
            int own = Math.max(1, space.getNumberOfComponents());
            if (space instanceof PDSeparation separation) {
                return own + alternate(separation.getAlternateColorSpace());
            }
            if (space instanceof PDDeviceN deviceN) {
                return own + alternate(deviceN.getAlternateColorSpace());
            }
            return own;
        } catch (Exception e) {
            return 4;
        }
    }

    private static int alternate(PDColorSpace space) {
        return space == null ? 4 : Math.max(1, space.getNumberOfComponents());
    }

    private static int channels(PDImage image) {
        try {
            return image.getColorSpace() == null ? 1 : Math.max(1, image.getColorSpace().getNumberOfComponents());
        } catch (Exception e) {
            return 4;
        }
    }

    static long codedBytes(PDImage image) {
        try {
            return switch (image.getSuffix() == null ? "" : image.getSuffix()) {
                case "jpg" -> {
                    try (InputStream in = image.createInputStream(List.of(COSName.DCT_DECODE.getName(),
                            COSName.DCT_DECODE_ABBREVIATION.getName()))) {
                        Jpeg.Frame f = Jpeg.frame(in);
                        yield f == null ? Long.MAX_VALUE : (long) f.width() * f.height() * Math.max(1, f.components());
                    }
                }
                case "jpx" -> {
                    if (image.getCOSObject() instanceof COSStream stream) {
                        yield jpxBytes(jpxHeader(stream));
                    }
                    try (InputStream in = image.createInputStream(List.of(COSName.JPX_DECODE.getName()))) {
                        yield jpxBytes(in.readNBytes(JPX_HEADER_SCAN));
                    }
                }
                case "tiff" -> faxBytes(image);
                case "jb2" -> jbig2Bytes(image);
                default -> 0;
            };
        } catch (IOException | RuntimeException e) {
            return Long.MAX_VALUE;
        }
    }

    private static long jbig2Bytes(PDImage image) throws IOException {
        long largest;
        try (InputStream in = image.createInputStream(List.of(COSName.JBIG2_DECODE.getName()))) {
            largest = Jbig2Size.largestBitmap(in);
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
        if (image.getCOSObject() instanceof COSDictionary dict
                && dict.getDictionaryObject(COSName.DECODE_PARMS, COSName.DP) instanceof COSDictionary parms
                && parms.getDictionaryObject(COSName.JBIG2_GLOBALS) instanceof COSStream globals) {
            try (InputStream in = globals.createInputStream()) {
                largest = Math.max(largest, Jbig2Size.largestBitmap(in));
            } catch (IOException e) {
                return Long.MAX_VALUE;
            }
        }
        return largest;
    }

    static long jpxBytes(byte[] b) {
        long[] size = jpxSize(b);
        return size == null ? Long.MAX_VALUE : samples(size[0], size[1], (int) size[2]);
    }

    private static long[] jpxSize(byte[] b) {
        for (int i = 0; i + 42 <= b.length; i++) {
            if ((b[i] & 0xFF) == 0xFF && (b[i + 1] & 0xFF) == 0x4F && (b[i + 2] & 0xFF) == 0xFF && (b[i + 3] & 0xFF) == 0x51) {
                long width = u32(b, i + 8) - u32(b, i + 16);
                long height = u32(b, i + 12) - u32(b, i + 20);
                int components = (b[i + 40] & 0xFF) << 8 | b[i + 41] & 0xFF;
                return new long[] {width, height, components};
            }
        }
        return null;
    }

    private static long samples(long width, long height, int components) {
        if (width <= 0 || height <= 0 || components <= 0 || width > Long.MAX_VALUE / height) {
            return Long.MAX_VALUE;
        }
        long pixels = width * height;
        return pixels > Long.MAX_VALUE / components ? Long.MAX_VALUE : pixels * components;
    }

    private static long u32(byte[] b, int at) {
        return (long) (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | b[at + 3] & 0xFF;
    }

    private static long faxBytes(PDImage image) {
        COSDictionary parms = faxParms(image);
        long columns = parms == null ? 1728 : parms.getInt(COSName.COLUMNS, 1728);
        long rows = parms == null ? 0 : parms.getInt(COSName.ROWS, 0);
        long height = image.getHeight();
        rows = rows > 0 && height > 0 ? Math.min(rows, height) : Math.max(rows, height);
        return (columns + 7) / 8 * Math.max(0, rows);
    }

    private static COSDictionary faxParms(PDImage image) {
        if (!(image.getCOSObject() instanceof COSDictionary dict)) {
            return null;
        }
        COSBase parms = dict.getDictionaryObject(COSName.DECODE_PARMS, COSName.DP);
        if (parms instanceof COSDictionary d) {
            return d;
        }
        if (parms instanceof COSArray a) {
            COSBase filters = dict.getDictionaryObject(COSName.FILTER, COSName.F);
            int at = filters instanceof COSArray f ? f.indexOf(COSName.CCITTFAX_DECODE) : 0;
            if (at < 0 && filters instanceof COSArray f) {
                at = f.indexOf(COSName.CCITTFAX_DECODE_ABBREVIATION);
            }
            return at >= 0 && at < a.size() && a.getObject(at) instanceof COSDictionary d ? d : null;
        }
        return null;
    }
}
