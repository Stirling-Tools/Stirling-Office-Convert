package stirling.software.officeconvert.topdf;

import java.awt.Dimension;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
                Tree parsed = treeBytes(zip);
                long tree = Math.min(xml, parsed.bytes());
                yield tree * TREE_BYTES_PER_BYTE + (xml - tree) * 5 / 2 + media / 2 + parsed.held();
            }
            case PPT -> xml * 7 + media * 2;
        };
        return saturated(Admission.BASE_BYTES + markup + decode);
    }

    // XMLBeans holds a tree-parsed OOXML part in roughly this many bytes of heap per byte of markup;
    // the streamed sheet data costs far less, so estimate() charges the two separately.
    static final long TREE_BYTES_PER_BYTE = 24;

    static final long MAX_SHEET_SCAN_BYTES = 256L << 20;

    private static final int SHEET_CHUNK_BYTES = 1 << 20;

    private static final byte[] SHEET_DATA = "sheetData".getBytes(StandardCharsets.ISO_8859_1);

    private static final int LOOKBACK = 64 + SHEET_DATA.length + 2;

    record Tree(long bytes, long held) {}

    /**
     * Bytes of a workbook that XMLBeans parses into a DOM tree: the workbook, styles and shared-string
     * parts in full, plus each worksheet's skeleton (everything outside its streamed {@code <sheetData>}),
     * and the most any one worksheet's skeleton holds in raw bytes while it is cut out.
     */
    private static Tree treeBytes(OfficeZip zip) {
        long tree = mainPartBytes(zip);
        long held = 0;
        long[] left = {MAX_SHEET_SCAN_BYTES};
        for (String part : zip.partNames()) {
            String p = part.toLowerCase(Locale.ROOT);
            long size = Math.max(0, zip.size(part));
            if (p.endsWith("/styles.xml") || p.endsWith("/sharedstrings.xml")) {
                tree = saturated(tree + size);
            } else if (p.contains("/worksheets/") && p.endsWith(".xml")) {
                Tree sheet = worksheet(zip, part, size, left);
                tree = saturated(tree + sheet.bytes());
                held = Math.max(held, sheet.held());
            }
        }
        return new Tree(tree, held);
    }

    private static long mainPartBytes(OfficeZip zip) {
        try {
            return Math.max(0, zip.size(zip.mainPart()));
        } catch (IOException e) {
            return 0;
        }
    }

    private static Tree worksheet(OfficeZip zip, String part, long size, long[] left) {
        if (left[0] <= 0) {
            return new Tree(size, 0);
        }
        try (InputStream in = zip.open(part)) {
            SheetScan scan = new SheetScan();
            byte[] buf = new byte[SHEET_CHUNK_BYTES + LOOKBACK];
            int len = 0;
            int from = 0;
            long base = 0;
            while (true) {
                int n = in.readNBytes(buf, len, (int) Math.min(buf.length - len, left[0]));
                left[0] -= n;
                len += n;
                boolean full = len == buf.length;
                boolean cut = !full && left[0] <= 0 && in.read() >= 0;
                boolean end = !full && !cut;
                int limit = full || cut ? Math.max(from, len - SHEET_DATA.length - 1) : len;
                for (int i = from; i < limit && !scan.done; i++) {
                    scan.at(buf, i, len, base);
                }
                if (scan.done) {
                    return new Tree(size, 3 * size);
                }
                if (end) {
                    return scan.tree(size, -1);
                }
                if (cut) {
                    return scan.tree(Math.max(size, base + len), base + limit);
                }
                int keep = len - limit + LOOKBACK;
                int start = Math.max(0, len - keep);
                System.arraycopy(buf, start, buf, 0, len - start);
                base += start;
                from = limit - start;
                len -= start;
            }
        } catch (IOException | RuntimeException e) {
            return new Tree(size, 0);
        }
    }

    private static final class SheetScan {

        long open = -1;

        long tagEnd = -1;

        long firstClose = -1;

        long lastClose = -1;

        long lastCloseEnd = -1;

        boolean done;

        void at(byte[] b, int i, int len, long base) {
            long pos = base + i;
            if (open < 0) {
                int tag = tagAt(b, i, len, false);
                if (tag >= 0) {
                    open = base + tag;
                    if (tag < i) {
                        for (int k = tag; k < i; k++) {
                            if (b[k] == '>') {
                                tagEnd = base + k;
                                done = k > 0 && b[k - 1] == '/';
                                return;
                            }
                        }
                    }
                }
                if (open < 0) {
                    return;
                }
            }
            if (tagEnd < 0) {
                if (b[i] == '>') {
                    tagEnd = pos;
                    done = i > 0 && b[i - 1] == '/';
                }
                return;
            }
            if (b[i] == '>' && lastClose >= 0 && lastCloseEnd < 0) {
                lastCloseEnd = pos;
            }
            int tag = tagAt(b, i, len, true);
            if (tag >= 0 && base + tag > tagEnd) {
                if (firstClose < 0) {
                    firstClose = base + tag;
                }
                lastClose = base + tag;
                lastCloseEnd = -1;
            }
        }

        Tree tree(long size, long scanned) {
            if (open < 0 || tagEnd < 0) {
                return new Tree(size, 3 * size);
            }
            long head = tagEnd + 2;
            if (scanned >= 0) {
                long from = firstClose < 0 ? scanned : firstClose;
                long tail = size - (lastClose < 0 ? scanned : lastClose);
                return new Tree(saturated(head + Math.max(0, tail)), 3 * Math.max(0, size - from));
            }
            if (firstClose < 0) {
                return new Tree(head, 0);
            }
            long tail = lastCloseEnd < 0 ? 0 : size - lastCloseEnd - 1;
            return new Tree(head + Math.max(0, tail), 3 * Math.max(0, size - firstClose));
        }
    }

    private static int tagAt(byte[] xml, int i, int len, boolean closing) {
        if (i + SHEET_DATA.length >= len || xml[i] != 's') {
            return -1;
        }
        for (int k = 0; k < SHEET_DATA.length; k++) {
            if (xml[i + k] != SHEET_DATA[k]) {
                return -1;
            }
        }
        byte after = xml[i + SHEET_DATA.length];
        if (!(after == '>' || after == '/' || after == ' ' || after == '\t' || after == '\r' || after == '\n')) {
            return -1;
        }
        int j = i - 1;
        if (j >= 0 && xml[j] == ':') {
            j--;
            while (j >= 0 && xml[j] != '<' && xml[j] != '/' && xml[j] > ' ' && i - j < 64) {
                j--;
            }
        }
        if (closing) {
            return j >= 1 && xml[j] == '/' && xml[j - 1] == '<' ? j - 1 : -1;
        }
        return j >= 0 && xml[j] == '<' ? j : -1;
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
