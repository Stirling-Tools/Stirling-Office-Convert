package stirling.software.officeconvert.html;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Kf8Index {

    private static final int HEADER = 192;

    private static final int MAX_RECORD = 0xFFF0;

    record Tag(int number, int values, int mask) {}

    record Entry(String label, Map<Integer, long[]> values) {}

    private final List<List<Tag>> tagGroups;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Long> cncx = new LinkedHashMap<>();
    private final List<ByteArrayOutputStream> cncxRecords = new ArrayList<>();

    Kf8Index(List<List<Tag>> tagGroups) {
        this.tagGroups = tagGroups;
    }

    long text(String s) {
        Long known = cncx.get(s);
        if (known != null) {
            return known;
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        byte[] length = varint(bytes.length);
        if (cncxRecords.isEmpty()
                || cncxRecords.get(cncxRecords.size() - 1).size() + length.length + bytes.length > MAX_RECORD) {
            cncxRecords.add(new ByteArrayOutputStream());
        }
        ByteArrayOutputStream record = cncxRecords.get(cncxRecords.size() - 1);
        long offset = (long) (cncxRecords.size() - 1) * 0x10000 + record.size();
        record.writeBytes(length);
        record.writeBytes(bytes);
        cncx.put(s, offset);
        return offset;
    }

    void add(String label, Map<Integer, long[]> values) {
        entries.add(new Entry(label, values));
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    List<byte[]> records() {
        List<byte[]> data = new ArrayList<>();
        List<String> lastLabels = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        String last = null;
        for (Entry e : entries) {
            byte[] encoded = encode(e);
            if (!offsets.isEmpty() && HEADER + body.size() + encoded.length + 4 + 2 * (offsets.size() + 1) > MAX_RECORD) {
                data.add(dataRecord(body, offsets));
                lastLabels.add(last);
                counts.add(offsets.size());
                body.reset();
                offsets.clear();
            }
            offsets.add(HEADER + body.size());
            body.writeBytes(encoded);
            last = e.label();
        }
        if (!offsets.isEmpty()) {
            data.add(dataRecord(body, offsets));
            lastLabels.add(last);
            counts.add(offsets.size());
        }
        List<byte[]> out = new ArrayList<>();
        out.add(headerRecord(lastLabels, counts));
        out.addAll(data);
        for (ByteArrayOutputStream c : cncxRecords) {
            ByteArrayOutputStream padded = new ByteArrayOutputStream();
            padded.writeBytes(c.toByteArray());
            while (padded.size() % 4 != 0) {
                padded.write(0);
            }
            out.add(padded.toByteArray());
        }
        return out;
    }

    private byte[] encode(Entry e) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] label = e.label().getBytes(StandardCharsets.US_ASCII);
        out.write(label.length);
        out.writeBytes(label);
        ByteArrayOutputStream values = new ByteArrayOutputStream();
        for (List<Tag> group : tagGroups) {
            int control = 0;
            for (Tag t : group) {
                long[] v = e.values().get(t.number());
                if (v == null || v.length == 0) {
                    continue;
                }
                int count = v.length / t.values();
                int shift = Integer.numberOfTrailingZeros(t.mask());
                control |= (count << shift) & t.mask();
                for (long x : v) {
                    values.writeBytes(varint(x));
                }
            }
            out.write(control);
        }
        out.writeBytes(values.toByteArray());
        return out.toByteArray();
    }

    private byte[] tagx() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int count = 0;
        for (List<Tag> group : tagGroups) {
            count += group.size() + 1;
        }
        out.writeBytes("TAGX".getBytes(StandardCharsets.US_ASCII));
        u32(out, 12 + 4L * count);
        u32(out, tagGroups.size());
        for (List<Tag> group : tagGroups) {
            for (Tag t : group) {
                out.write(t.number());
                out.write(t.values());
                out.write(t.mask());
                out.write(0);
            }
            out.writeBytes(new byte[] {0, 0, 0, 1});
        }
        return out.toByteArray();
    }

    private byte[] headerRecord(List<String> lastLabels, List<Integer> counts) {
        ByteArrayOutputStream rest = new ByteArrayOutputStream();
        rest.writeBytes(tagx());
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < lastLabels.size(); i++) {
            offsets.add(HEADER + rest.size());
            byte[] label = lastLabels.get(i).getBytes(StandardCharsets.US_ASCII);
            rest.write(label.length);
            rest.writeBytes(label);
            u16(rest, counts.get(i));
        }
        while (rest.size() % 4 != 0) {
            rest.write(0);
        }
        int idxt = HEADER + rest.size();
        rest.writeBytes("IDXT".getBytes(StandardCharsets.US_ASCII));
        for (int o : offsets) {
            u16(rest, o);
        }
        while (rest.size() % 4 != 0) {
            rest.write(0);
        }
        byte[] h = header(0, idxt, lastLabels.size());
        Kf8Writer.put32(h, 28, 65001);
        Kf8Writer.put32(h, 32, 0xFFFFFFFFL);
        Kf8Writer.put32(h, 36, entries.size());
        Kf8Writer.put32(h, 52, cncxRecords.size());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(h);
        out.writeBytes(rest.toByteArray());
        return out.toByteArray();
    }

    private static byte[] dataRecord(ByteArrayOutputStream body, List<Integer> offsets) {
        ByteArrayOutputStream rest = new ByteArrayOutputStream();
        rest.writeBytes(body.toByteArray());
        while (rest.size() % 4 != 0) {
            rest.write(0);
        }
        int idxt = HEADER + rest.size();
        rest.writeBytes("IDXT".getBytes(StandardCharsets.US_ASCII));
        for (int o : offsets) {
            u16(rest, o);
        }
        while (rest.size() % 4 != 0) {
            rest.write(0);
        }
        byte[] h = header(1, idxt, offsets.size());
        Kf8Writer.put32(h, 28, 0xFFFFFFFFL);
        Kf8Writer.put32(h, 32, 0xFFFFFFFFL);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(h);
        out.writeBytes(rest.toByteArray());
        return out.toByteArray();
    }

    private static byte[] header(int type, int idxt, int count) {
        byte[] h = new byte[HEADER];
        h[0] = 'I';
        h[1] = 'N';
        h[2] = 'D';
        h[3] = 'X';
        Kf8Writer.put32(h, 4, HEADER);
        Kf8Writer.put32(h, 12, type);
        Kf8Writer.put32(h, 20, idxt);
        Kf8Writer.put32(h, 24, count);
        return h;
    }

    static byte[] varint(long v) {
        int groups = 1;
        while (v >>> (7 * groups) != 0 && groups < 10) {
            groups++;
        }
        byte[] out = new byte[groups];
        for (int i = groups - 1; i >= 0; i--) {
            out[i] = (byte) (v & 0x7F);
            v >>>= 7;
        }
        out[groups - 1] |= (byte) 0x80;
        return out;
    }

    static void u16(ByteArrayOutputStream out, int v) {
        out.write(v >>> 8 & 0xFF);
        out.write(v & 0xFF);
    }

    static void u32(ByteArrayOutputStream out, long v) {
        out.write((int) (v >>> 24) & 0xFF);
        out.write((int) (v >>> 16) & 0xFF);
        out.write((int) (v >>> 8) & 0xFF);
        out.write((int) v & 0xFF);
    }
}
