package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

public final class ImageBudget {

    public static final long MAX_DECODED_BYTES = 256L * 1024 * 1024;

    private static final int JPX_HEADER_SCAN = 1 << 20;

    private ImageBudget() {}

    private static final Map<COSBase, Boolean> VERDICTS = Collections.synchronizedMap(new WeakHashMap<>());

    public static boolean affordable(PDImage image) {
        if (decodedBytes(image) > MAX_DECODED_BYTES) {
            return false;
        }
        if (!(image instanceof PDImageXObject x)) {
            return codedBytes(image) <= MAX_DECODED_BYTES;
        }
        Boolean known = VERDICTS.get(x.getCOSObject());
        if (known == null) {
            known = codedBytes(image) <= MAX_DECODED_BYTES && inflatesWithin(image);
            VERDICTS.put(x.getCOSObject(), known);
        }
        return known;
    }

    private static boolean inflatesWithin(PDImage image) {
        if (!(image instanceof PDImageXObject x)) {
            return true;
        }
        COSStream mask = x.getCOSObject().getCOSStream(COSName.SMASK);
        return StreamGuard.fits(x.getCOSObject()) && (mask == null || StreamGuard.fits(mask));
    }

    static long decodedBytes(PDImage image) {
        int components;
        try {
            components = image.isStencil() || image.getColorSpace() == null ? 1 : image.getColorSpace().getNumberOfComponents();
        } catch (Exception e) {
            components = 4;
        }
        long bits = (long) image.getWidth() * image.getHeight() * Math.max(1, components) * Math.max(1, image.getBitsPerComponent());
        return bits / 8;
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
                    try (InputStream in = image.createInputStream(List.of(COSName.JPX_DECODE.getName()))) {
                        yield jpxBytes(in.readNBytes(JPX_HEADER_SCAN));
                    }
                }
                case "tiff" -> faxBytes(image);
                case "jb2" -> jbig2Bytes(image);
                default -> 0;
            };
        } catch (IOException | RuntimeException e) {
            return 0;
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
        for (int i = 0; i + 42 <= b.length; i++) {
            if ((b[i] & 0xFF) == 0xFF && (b[i + 1] & 0xFF) == 0x4F && (b[i + 2] & 0xFF) == 0xFF && (b[i + 3] & 0xFF) == 0x51) {
                long width = u32(b, i + 8) - u32(b, i + 16);
                long height = u32(b, i + 12) - u32(b, i + 20);
                int components = (b[i + 40] & 0xFF) << 8 | b[i + 41] & 0xFF;
                return width <= 0 || height <= 0 ? Long.MAX_VALUE : width * height * Math.max(1, components);
            }
        }
        return Long.MAX_VALUE;
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
