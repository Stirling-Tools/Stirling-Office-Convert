package stirling.software.officeconvert.topdf.xls;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.Entry;

final class Repair {

    static final int MAX_STREAM_BYTES = 256 << 20;

    private static final int FILEPASS = 0x002F;

    private static final Map<Integer, Integer> FIXED = Map.ofEntries(Map.entry(0x000C, 2), Map.entry(0x000D, 2),
            Map.entry(0x000F, 2), Map.entry(0x0010, 8), Map.entry(0x0011, 2), Map.entry(0x0012, 2),
            Map.entry(0x0013, 2), Map.entry(0x0019, 2), Map.entry(0x0022, 2), Map.entry(0x002A, 2),
            Map.entry(0x002B, 2), Map.entry(0x0040, 2), Map.entry(0x0042, 2), Map.entry(0x0055, 2),
            Map.entry(0x005F, 2), Map.entry(0x0080, 8), Map.entry(0x0081, 2), Map.entry(0x0082, 2),
            Map.entry(0x0083, 2), Map.entry(0x0084, 2), Map.entry(0x008C, 4), Map.entry(0x008D, 2),
            Map.entry(0x009C, 2), Map.entry(0x00DA, 2), Map.entry(0x0160, 2), Map.entry(0x0161, 2),
            Map.entry(0x01AF, 2), Map.entry(0x01BC, 2), Map.entry(0x0225, 4), Map.entry(0x0236, 16));

    private Repair() {}

    static byte[] workbook(DirectoryNode root) throws IOException {
        Entry e = root.hasEntryCaseInsensitive("Workbook") ? root.getEntryCaseInsensitive("Workbook") : null;
        if (!(e instanceof DocumentEntry doc) || doc.getSize() > MAX_STREAM_BYTES) {
            return null;
        }
        byte[] d;
        try (InputStream in = root.createDocumentInputStream(doc)) {
            d = in.readNBytes(MAX_STREAM_BYTES);
        }
        return records(d);
    }

    static byte[] records(byte[] d) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
        boolean changed = false;
        int at = 0;
        while (at + 4 <= d.length) {
            int sid = d[at] & 0xFF | (d[at + 1] & 0xFF) << 8;
            int len = d[at + 2] & 0xFF | (d[at + 3] & 0xFF) << 8;
            int size = Math.min(len, d.length - at - 4);
            if (sid == FILEPASS) {
                return null;
            }
            Integer fixed = FIXED.get(sid);
            if (fixed != null && size > fixed) {
                out.write(d[at]);
                out.write(d[at + 1]);
                out.write(fixed);
                out.write(fixed >> 8);
                out.write(d, at + 4, fixed);
                changed = true;
            } else {
                out.write(d, at, 4 + size);
            }
            at += 4 + size;
        }
        out.write(d, at, d.length - at);
        return changed ? out.toByteArray() : null;
    }
}
