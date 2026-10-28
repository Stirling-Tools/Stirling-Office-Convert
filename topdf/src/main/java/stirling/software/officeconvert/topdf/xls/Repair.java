package stirling.software.officeconvert.topdf.xls;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.Map;

import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.Entry;

import stirling.software.officeconvert.topdf.io.SourceFile;

final class Repair {

    static final int MAX_STREAM_BYTES = 256 << 20;

    private static final int MAX_PROBES = 4096;

    private static final int FILEPASS = 0x002F;

    private static final int BOF = 0x0809;

    private static final int EOF = 0x000A;

    private static final int MSODRAWING = 0x00EC;

    private static final int OBJ = 0x005D;

    private static final int TXO = 0x01B6;

    private static final int NOTE = 0x001C;

    private static final int CONTINUE = 0x003C;

    private static final int BOUNDSHEET = 0x0085;

    private static final int WINDOW2 = 0x023E;

    private static final Map<Integer, Integer> FIXED = Map.ofEntries(Map.entry(0x000C, 2), Map.entry(0x000D, 2),
            Map.entry(0x000F, 2), Map.entry(0x0010, 8), Map.entry(0x0011, 2), Map.entry(0x0012, 2),
            Map.entry(0x0013, 2), Map.entry(0x0019, 2), Map.entry(0x0022, 2), Map.entry(0x002A, 2),
            Map.entry(0x002B, 2), Map.entry(0x0040, 2), Map.entry(0x0042, 2), Map.entry(0x0055, 2),
            Map.entry(0x005F, 2), Map.entry(0x0080, 8), Map.entry(0x0081, 2), Map.entry(0x0082, 2),
            Map.entry(0x0083, 2), Map.entry(0x0084, 2), Map.entry(0x008C, 4), Map.entry(0x008D, 2),
            Map.entry(0x009C, 2), Map.entry(0x00DA, 2), Map.entry(0x0160, 2), Map.entry(0x0161, 2),
            Map.entry(0x01AF, 2), Map.entry(0x01BC, 2), Map.entry(0x0225, 4), Map.entry(0x0236, 16));

    private Repair() {}

    static byte[] stream(DirectoryNode root) throws IOException {
        Entry e = root.hasEntryCaseInsensitive("Workbook") ? root.getEntryCaseInsensitive("Workbook") : null;
        if (!(e instanceof DocumentEntry doc) || doc.getSize() > MAX_STREAM_BYTES) {
            return null;
        }
        try (InputStream in = root.createDocumentInputStream(doc)) {
            return in.readNBytes(MAX_STREAM_BYTES);
        }
    }

    static byte[] records(byte[] d) {
        return records(d, false);
    }

    static byte[] records(byte[] d, boolean salvage) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
        boolean changed = false;
        boolean objects = false;
        int closed = 0;
        int depth = 0;
        int at = 0;
        while (at + 4 <= d.length) {
            int sid = d[at] & 0xFF | (d[at + 1] & 0xFF) << 8;
            int len = d[at + 2] & 0xFF | (d[at + 3] & 0xFF) << 8;
            if (sid == FILEPASS) {
                return null;
            }
            if (at + 4 + len > d.length) {
                if (!salvage) {
                    out.write(d, at, d.length - at);
                    return changed ? out.toByteArray() : null;
                }
                changed = true;
                break;
            }
            if (sid == BOF) {
                depth++;
            } else if (sid == EOF) {
                depth--;
                closed += depth == 0 ? 1 : 0;
            }
            boolean drawing = sid == MSODRAWING || sid == OBJ || sid == TXO || sid == NOTE
                    || sid == CONTINUE && objects;
            if (salvage && drawing) {
                objects = true;
                changed = true;
                at += 4 + len;
                continue;
            }
            objects = false;
            Integer fixed = sid == WINDOW2 ? null : FIXED.get(sid);
            if (fixed != null && len > fixed) {
                out.write(d[at]);
                out.write(d[at + 1]);
                out.write(fixed);
                out.write(fixed >> 8);
                out.write(d, at + 4, fixed);
                changed = true;
            } else {
                out.write(d, at, 4 + len);
            }
            at += 4 + len;
        }
        if (!salvage) {
            out.write(d, at, d.length - at);
        }
        if (salvage && depth > 0 && closed > 0) {
            out.write(new byte[] {0x3E, 0x02, 18, 0, (byte) 0xB6, 0x06, 0, 0, 0, 0, 64, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0}, 0, 22);
        }
        for (int k = 0; salvage && k < depth && k < 64; k++) {
            out.write(new byte[] {(byte) EOF, 0, 0, 0}, 0, 4);
            changed = true;
        }
        return changed ? out.toByteArray() : null;
    }

    static byte[] salvage(Path file) throws InterruptedIOException {
        byte[] d;
        try {
            d = SourceFile.head(file, MAX_STREAM_BYTES);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            return null;
        }
        long[] budget = {4L * d.length + (1 << 20)};
        int probes = 0;
        for (int at = 512; at + 20 <= d.length && probes < MAX_PROBES && budget[0] > 0; at += 64) {
            if (u16(d, at) == BOF && u16(d, at + 2) == 16 && u16(d, at + 4) == 0x0600 && u16(d, at + 6) == 5) {
                SheetPart.stopIfInterrupted();
                probes++;
                byte[] stream = workbookAt(d, at, budget);
                if (stream != null) {
                    return stream;
                }
            }
        }
        return null;
    }

    private static byte[] workbookAt(byte[] d, int start, long[] budget) throws InterruptedIOException {
        int sheets = 0;
        int ends = 0;
        int depth = 0;
        int at = start;
        int records = 0;
        while (at + 4 <= d.length) {
            if (budget[0] <= 0) {
                return null;
            }
            if ((++records & 0xFFF) == 0) {
                SheetPart.stopIfInterrupted();
            }
            int sid = u16(d, at);
            int len = u16(d, at + 2);
            if (len > 8224 || at + 4 + len > d.length) {
                break;
            }
            budget[0] -= 4 + len;
            if (sid == BOUNDSHEET && depth == 1 && ends == 0) {
                sheets++;
            }
            if (sid == BOF) {
                depth++;
            } else if (sid == EOF) {
                depth--;
                if (depth == 0) {
                    ends++;
                }
            }
            at += 4 + len;
            if (depth <= 0 && ends > sheets) {
                break;
            }
        }
        if (ends == 0 || at - start < 64) {
            return null;
        }
        byte[] out = new byte[at - start];
        System.arraycopy(d, start, out, 0, out.length);
        return out;
    }

    private static int u16(byte[] d, int at) {
        return d[at] & 0xFF | (d[at + 1] & 0xFF) << 8;
    }
}
