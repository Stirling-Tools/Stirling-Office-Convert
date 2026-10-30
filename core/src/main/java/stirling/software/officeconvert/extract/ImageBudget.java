package stirling.software.officeconvert.extract;

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
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

public final class ImageBudget {

    public static final long MAX_DECODED_BYTES = 256L * 1024 * 1024;

    public static final long MAX_JPX_SAMPLES = 1L << 26;

    private static final int JPX_HEADER_SCAN = 1 << 20;

    private ImageBudget() {}

    private static final Map<COSBase, Boolean> VERDICTS = Collections.synchronizedMap(new WeakHashMap<>());

    public static boolean affordable(PDImage image) {
        boolean jpx = jpx(image);
        if (!jpx && decodedBytes(image) > MAX_DECODED_BYTES) {
            return false;
        }
        long limit = jpx ? MAX_JPX_SAMPLES : MAX_DECODED_BYTES;
        if (!(image instanceof PDImageXObject x)) {
            return codedBytes(image) <= limit && maskedRenderBytes(image) <= MAX_DECODED_BYTES;
        }
        Boolean known = VERDICTS.get(x.getCOSObject());
        if (known == null) {
            known = codedBytes(image) <= limit && maskedRenderBytes(image) <= MAX_DECODED_BYTES
                    && inflatesWithin(image);
            VERDICTS.put(x.getCOSObject(), known);
        }
        return known;
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
        boolean stencil;
        try {
            stencil = image.isStencil();
        } catch (RuntimeException e) {
            stencil = false;
        }
        if (!(image instanceof PDImageXObject x)) {
            return stencil ? samples(image.getWidth(), image.getHeight(), 4) : 0;
        }
        COSStream mask = appliedMask(x.getCOSObject());
        if (mask == null && !stencil) {
            return x.getCOSObject().getDictionaryObject(COSName.MASK) instanceof COSArray ? colourKeyBytes(x) : 0;
        }
        long[] base = size(x.getCOSObject());
        long[] applied = mask == null ? base : size(mask);
        if (base == null || applied == null) {
            return Long.MAX_VALUE;
        }
        return samples(Math.max(base[0], applied[0]), Math.max(base[1], applied[1]), 4);
    }

    static long decodedBytes(PDImage image) {
        return samples(image.getWidth(), image.getHeight(), components(image) + 4);
    }

    private static long colourKeyBytes(PDImageXObject image) {
        long[] base = size(image.getCOSObject());
        return base == null ? Long.MAX_VALUE : samples(base[0], base[1], components(image) + 9);
    }

    private static int components(PDImage image) {
        try {
            return Math.max(1, image.isStencil() || image.getColorSpace() == null ? 1
                    : image.getColorSpace().getNumberOfComponents());
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
