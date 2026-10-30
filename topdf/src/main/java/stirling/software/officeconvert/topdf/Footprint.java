package stirling.software.officeconvert.topdf;

import java.awt.Dimension;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
            case DOCX, TEXT, HTML, EPUB, JSON -> xml * 8 + media / 2;
            case PPTX -> xml * 7 + media * 2;
            case XLSX, CSV, TSV -> {
                long tree = Math.min(xml, treeBytes(zip));
                yield tree * TREE_BYTES_PER_BYTE + (xml - tree) * 5 / 2 + media / 2;
            }
            case PPT -> xml * 7 + media * 2;
        };
        return saturated(Admission.BASE_BYTES + markup + decode);
    }

    // XMLBeans holds a tree-parsed OOXML part in roughly this many bytes of heap per byte of markup;
    // the streamed sheet data costs far less, so estimate() charges the two separately.
    static final long TREE_BYTES_PER_BYTE = 24;

    private static final int SHEET_SCAN_BYTES = 8 << 20;

    private static final int SHEET_CHUNK_BYTES = 1 << 20;

    private static final byte[] OPEN_SHEET_DATA = "<sheetData".getBytes(StandardCharsets.ISO_8859_1);

    private static final byte[] CLOSE_SHEET_DATA = "</sheetData>".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * Bytes of a workbook that XMLBeans parses into a DOM tree: the workbook, styles and shared-string
     * parts in full, plus each worksheet's skeleton (everything outside its streamed {@code <sheetData>}).
     */
    private static long treeBytes(OfficeZip zip) {
        long tree = mainPartBytes(zip);
        for (String part : zip.partNames()) {
            String p = part.toLowerCase(Locale.ROOT);
            long size = Math.max(0, zip.size(part));
            if (p.endsWith("/styles.xml") || p.endsWith("/sharedstrings.xml")) {
                tree += size;
            } else if (p.contains("/worksheets/") && p.endsWith(".xml")) {
                tree += worksheetSkeletonBytes(zip, part, size);
            }
        }
        return tree;
    }

    private static long mainPartBytes(OfficeZip zip) {
        try {
            return Math.max(0, zip.size(zip.mainPart()));
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Bytes of a worksheet outside {@code <sheetData>} (its tree-parsed skeleton): the header before
     * it and any markup after it, wherever the sheet data closes.
     */
    private static long worksheetSkeletonBytes(OfficeZip zip, String part, long size) {
        try (InputStream in = zip.open(part)) {
            byte[] head = in.readNBytes(SHEET_SCAN_BYTES);
            int open = indexOf(head, OPEN_SHEET_DATA, 0);
            if (open < 0) {
                return size;
            }
            long end = sheetDataEnd(head, open);
            if (end < 0) {
                end = closeAfter(in, head);
            }
            return end < 0 ? open : open + Math.max(0, size - end);
        } catch (IOException | RuntimeException e) {
            return size;
        }
    }

    private static long closeAfter(InputStream in, byte[] head) throws IOException {
        int keep = Math.min(head.length, CLOSE_SHEET_DATA.length - 1);
        byte[] carry = Arrays.copyOfRange(head, head.length - keep, head.length);
        long base = head.length - keep;
        byte[] chunk = new byte[SHEET_CHUNK_BYTES];
        int n;
        while ((n = in.readNBytes(chunk, 0, chunk.length)) > 0) {
            byte[] window = new byte[carry.length + n];
            System.arraycopy(carry, 0, window, 0, carry.length);
            System.arraycopy(chunk, 0, window, carry.length, n);
            int close = indexOf(window, CLOSE_SHEET_DATA, 0);
            if (close >= 0) {
                return base + close + CLOSE_SHEET_DATA.length;
            }
            keep = Math.min(window.length, CLOSE_SHEET_DATA.length - 1);
            carry = Arrays.copyOfRange(window, window.length - keep, window.length);
            base += window.length - keep;
        }
        return -1;
    }

    private static int sheetDataEnd(byte[] head, int open) {
        int gt = indexOf(head, (byte) '>', open);
        if (gt <= 0) {
            return -1;
        }
        if (head[gt - 1] == '/') {
            return gt + 1;
        }
        int close = indexOf(head, CLOSE_SHEET_DATA, gt);
        return close < 0 ? -1 : close + CLOSE_SHEET_DATA.length;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static int indexOf(byte[] haystack, byte needle, int from) {
        for (int i = Math.max(0, from); i < haystack.length; i++) {
            if (haystack[i] == needle) {
                return i;
            }
        }
        return -1;
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
