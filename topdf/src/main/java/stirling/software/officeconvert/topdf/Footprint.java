package stirling.software.officeconvert.topdf;

import java.awt.Dimension;
import java.io.IOException;
import java.util.Locale;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.PictureDecoder;

/** The heap a document is likely to need, from its parts alone and before anything is laid out: markup that becomes
 * cells, runs and shapes, pictures that go through to the PDF, and the largest one that must be decoded. */
final class Footprint {

    static final int MAX_MEDIA_PARTS = 256;

    static final int MEDIA_HEAD_BYTES = 64 << 10;

    private Footprint() {}

    static long estimate(OfficeZip zip, OfficeToPdf.Format format) {
        long xml = 0;
        long media = 0;
        long decode = 0;
        int looked = 0;
        for (String part : zip.partNames()) {
            long size = Math.max(0, zip.size(part));
            String p = part.toLowerCase(Locale.ROOT);
            if (p.endsWith(".xml") || p.endsWith(".vml")) {
                xml += size;
            } else if (p.contains("/media/")) {
                media += size;
                if (looked++ < MAX_MEDIA_PARTS) {
                    decode = Math.max(decode, decodeBytes(zip, part, size));
                }
            }
        }
        long markup = switch (format) {
            case DOCX, TEXT -> xml * 8 + media / 2;
            case PPTX -> xml * 7 + media * 2;
            case XLSX, CSV, TSV -> xml * 5 / 2 + media / 2;
            case PPT -> xml * 7 + media * 2;
        };
        return saturated(Admission.BASE_BYTES + markup + decode);
    }

    // A binary file is read whole into records; its pictures sit inside it, one decoded at a time
    static long legacy(long bytes) {
        long size = Math.max(0, bytes);
        return saturated(Admission.BASE_BYTES + size * 6 + Math.min(size * 32, 4 * PictureDecoder.DECODE_PIXELS));
    }

    // A JPEG goes into the PDF as it is; other pictures are decoded to 4 bytes a pixel, up to the decoder's cap
    private static long decodeBytes(OfficeZip zip, String part, long size) {
        byte[] head;
        try {
            head = zip.head(part, MEDIA_HEAD_BYTES);
        } catch (IOException | RuntimeException e) {
            return 0;
        }
        PictureDecoder.Kind kind = PictureDecoder.sniff(head);
        switch (kind) {
            case JPEG, UNKNOWN, SVG -> {
                return 0;
            }
            case EMF, WMF -> {
                return 4L * 4_000_000;
            }
            default -> {
                long pixels;
                try {
                    Dimension d = PictureDecoder.pixelSize(head);
                    pixels = (long) d.width * d.height;
                } catch (IOException | RuntimeException e) {
                    pixels = size * 8;
                }
                return 4 * Math.min(Math.max(0, pixels), PictureDecoder.DECODE_PIXELS);
            }
        }
    }

    private static long saturated(long v) {
        return v < 0 ? Long.MAX_VALUE : v;
    }
}
