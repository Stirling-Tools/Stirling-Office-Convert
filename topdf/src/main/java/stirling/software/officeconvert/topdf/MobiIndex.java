package stirling.software.officeconvert.topdf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class MobiIndex {

    static final int MAX_ENTRIES = 200_000;

    static final int MAX_RECORDS = 4_096;

    static final String DAMAGED = "The eBook is damaged: its KF8 index cannot be read";

    record Entry(String label, Map<Integer, List<Long>> tags) {

        long value(int tag, int i) throws IOException {
            List<Long> v = tags.get(tag);
            if (v == null || v.size() <= i) {
                throw new IOException(DAMAGED);
            }
            return v.get(i);
        }
    }

    private record Tag(int tag, int values, int mask, boolean end) {}

    private MobiIndex() {}

    static List<Entry> read(PalmDatabase pdb, int first) throws IOException {
        try {
            return entries(pdb, first);
        } catch (IndexOutOfBoundsException e) {
            throw new IOException(DAMAGED, e);
        }
    }

    private static List<Entry> entries(PalmDatabase pdb, int first) throws IOException {
        byte[] head = pdb.record(first);
        if (!indx(head)) {
            throw new IOException(DAMAGED);
        }
        int headerLength = PalmDatabase.index(head, 4);
        int records = PalmDatabase.index(head, 24);
        if (headerLength < 0 || records < 0 || records > MAX_RECORDS || headerLength + 12 > head.length
                || head[headerLength] != 'T' || head[headerLength + 1] != 'A' || head[headerLength + 2] != 'G'
                || head[headerLength + 3] != 'X') {
            throw new IOException(DAMAGED);
        }
        int tagxLength = PalmDatabase.index(head, headerLength + 4);
        int controlBytes = PalmDatabase.index(head, headerLength + 8);
        if (tagxLength < 12 || controlBytes < 1 || controlBytes > 8 || headerLength + tagxLength > head.length) {
            throw new IOException(DAMAGED);
        }
        List<Tag> tags = new ArrayList<>();
        for (int i = headerLength + 12; i + 4 <= headerLength + tagxLength; i += 4) {
            tags.add(new Tag(head[i] & 0xFF, head[i + 1] & 0xFF, head[i + 2] & 0xFF, (head[i + 3] & 0xFF) == 1));
        }
        List<Entry> out = new ArrayList<>();
        for (int r = first + 1; r <= first + records; r++) {
            OfficeToPdf.stopIfInterrupted();
            byte[] data = pdb.record(r);
            if (!indx(data)) {
                throw new IOException(DAMAGED);
            }
            int idxt = PalmDatabase.index(data, 20);
            int count = PalmDatabase.index(data, 24);
            if (idxt < 0 || count < 0 || idxt + 4 + 2L * count > data.length || out.size() + (long) count > MAX_ENTRIES) {
                throw new IOException(DAMAGED);
            }
            for (int j = 0; j < count; j++) {
                int start = PalmDatabase.u16(data, idxt + 4 + 2 * j);
                int end = j + 1 < count ? PalmDatabase.u16(data, idxt + 4 + 2 * (j + 1)) : idxt;
                if (start >= end || end > data.length) {
                    throw new IOException(DAMAGED);
                }
                int labelLength = data[start] & 0xFF;
                String label = new String(data, start + 1, labelLength, StandardCharsets.ISO_8859_1);
                int from = start + 1 + labelLength;
                if (from + controlBytes > end) {
                    throw new IOException(DAMAGED);
                }
                out.add(new Entry(label, tagMap(controlBytes, tags, data, from, end)));
            }
        }
        return out;
    }

    private static boolean indx(byte[] r) {
        return r.length >= 28 && r[0] == 'I' && r[1] == 'N' && r[2] == 'D' && r[3] == 'X';
    }

    private static Map<Integer, List<Long>> tagMap(int controlBytes, List<Tag> tags, byte[] data, int from, int end)
            throws IOException {
        int control = from;
        int[] pos = {from + controlBytes};
        List<long[]> pending = new ArrayList<>();
        for (Tag t : tags) {
            if (t.end()) {
                control++;
                continue;
            }
            if (control >= from + controlBytes || t.mask() == 0) {
                throw new IOException(DAMAGED);
            }
            int value = data[control] & t.mask();
            if (value == 0) {
                continue;
            }
            if (value == t.mask() && Integer.bitCount(t.mask()) > 1) {
                long bytes = varint(data, pos, end);
                pending.add(new long[] {t.tag(), -1, bytes, t.values()});
            } else {
                int mask = t.mask();
                while ((mask & 1) == 0) {
                    mask >>>= 1;
                    value >>>= 1;
                }
                pending.add(new long[] {t.tag(), value, -1, t.values()});
            }
        }
        Map<Integer, List<Long>> out = new HashMap<>();
        for (long[] p : pending) {
            List<Long> values = new ArrayList<>();
            if (p[1] >= 0) {
                for (long k = 0; k < p[1] * p[3]; k++) {
                    values.add(varint(data, pos, end));
                }
            } else {
                int started = pos[0];
                while (pos[0] - started < p[2]) {
                    values.add(varint(data, pos, end));
                }
            }
            out.put((int) p[0], values);
        }
        return out;
    }

    private static long varint(byte[] data, int[] pos, int end) throws IOException {
        long v = 0;
        for (int i = 0; i < 5; i++) {
            if (pos[0] >= end) {
                throw new IOException(DAMAGED);
            }
            int b = data[pos[0]++] & 0xFF;
            v = v << 7 | (b & 0x7F);
            if ((b & 0x80) != 0) {
                return v;
            }
        }
        throw new IOException(DAMAGED);
    }
}
