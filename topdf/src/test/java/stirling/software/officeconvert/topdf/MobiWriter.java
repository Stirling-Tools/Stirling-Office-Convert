package stirling.software.officeconvert.topdf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class MobiWriter {

    static final int TEXT_RECORD = 4096;

    final List<byte[]> records = new ArrayList<>();

    private MobiWriter() {}

    static MobiWriter create() {
        MobiWriter w = new MobiWriter();
        w.records.add(new byte[0]);
        return w;
    }

    int add(byte[] record) {
        records.add(record);
        return records.size() - 1;
    }

    byte[] build(String type) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] name = new byte[32];
        byte[] title = "test-book".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, name, 0, title.length);
        out.writeBytes(name);
        out.writeBytes(new byte[28]);
        out.writeBytes(type.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[8]);
        u16(out, records.size());
        int offset = 78 + 8 * records.size() + 2;
        for (int i = 0; i < records.size(); i++) {
            u32(out, offset);
            u32(out, i * 2);
            offset += records.get(i).length;
        }
        out.writeBytes(new byte[2]);
        for (byte[] r : records) {
            out.writeBytes(r);
        }
        return out.toByteArray();
    }

    static List<byte[]> chunks(byte[] text) {
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < text.length; i += TEXT_RECORD) {
            byte[] c = new byte[Math.min(TEXT_RECORD, text.length - i)];
            System.arraycopy(text, i, c, 0, c.length);
            out.add(c);
        }
        return out;
    }

    static byte[] header(int compression, long textLength, int textRecords, int encryption, int version,
            int firstImage, int huffRecord, int huffCount, int extraFlags, int fdst, int fdstCount, int fragments,
            int skeletons, byte[] exth) {
        byte[] h = new byte[16 + 0x108];
        put16(h, 0, compression);
        put32(h, 4, textLength);
        put16(h, 8, textRecords);
        put16(h, 10, TEXT_RECORD);
        put16(h, 12, encryption);
        h[16] = 'M';
        h[17] = 'O';
        h[18] = 'B';
        h[19] = 'I';
        put32(h, 20, 0x108);
        put32(h, 24, 2);
        put32(h, 28, 65001);
        put32(h, 36, version);
        put32(h, 80, -1);
        put32(h, 108, firstImage);
        put32(h, 112, huffRecord);
        put32(h, 116, huffCount);
        put32(h, 128, exth == null ? 0 : 0x40);
        put32(h, 192, fdst);
        put32(h, 196, fdstCount);
        put32(h, 0xF0, extraFlags);
        put32(h, 0xF4, -1);
        put32(h, 0xF8, fragments);
        put32(h, 0xFC, skeletons);
        put32(h, 0x100, -1);
        put32(h, 0x104, -1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(h);
        if (exth != null) {
            out.writeBytes(exth);
        }
        return out.toByteArray();
    }

    static byte[] exth(Object... typeValue) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int i = 0; i < typeValue.length; i += 2) {
            byte[] v = typeValue[i + 1] instanceof String s ? s.getBytes(StandardCharsets.UTF_8)
                    : new byte[] {0, 0, 0, (byte) (int) (Integer) typeValue[i + 1]};
            u32(body, (Integer) typeValue[i]);
            u32(body, v.length + 8);
            body.writeBytes(v);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("EXTH".getBytes(StandardCharsets.US_ASCII));
        u32(out, 12 + body.size());
        u32(out, typeValue.length / 2);
        out.writeBytes(body.toByteArray());
        while (out.size() % 4 != 0) {
            out.write(0);
        }
        return out.toByteArray();
    }

    static byte[] palmDoc(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i < in.length) {
            int bestLength = 0;
            int bestDistance = 0;
            for (int d = 1; d <= Math.min(i, 2047); d++) {
                int n = 0;
                while (n < 10 && i + n < in.length && in[i + n] == in[i - d + n]) {
                    n++;
                }
                if (n > bestLength) {
                    bestLength = n;
                    bestDistance = d;
                }
            }
            if (bestLength >= 3) {
                int m = 0x8000 | bestDistance << 3 | (bestLength - 3);
                out.write(m >>> 8);
                out.write(m & 0xFF);
                i += bestLength;
                continue;
            }
            int c = in[i] & 0xFF;
            int next = i + 1 < in.length ? in[i + 1] & 0xFF : -1;
            if (c == ' ' && next >= 0x40 && next < 0x80) {
                out.write(next ^ 0x80);
                i += 2;
            } else if (c == 0 || c >= 9 && c < 0x80) {
                out.write(c);
                i++;
            } else {
                out.write(1);
                out.write(c);
                i++;
            }
        }
        return out.toByteArray();
    }

    static byte[] withTrailing(byte[] record) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(record);
        out.write(0);
        out.writeBytes(new byte[] {'x', 'y', 'z'});
        out.write(0x84);
        return out.toByteArray();
    }

    static byte[] indx(int[][] tags, List<byte[]> entries, boolean head, int records) {
        int headerLength = 192;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (head) {
            body.writeBytes("TAGX".getBytes(StandardCharsets.US_ASCII));
            u32(body, 12 + 4 * tags.length);
            u32(body, 1);
            for (int[] t : tags) {
                for (int v : t) {
                    body.write(v);
                }
            }
        }
        List<Integer> starts = new ArrayList<>();
        for (byte[] e : entries) {
            starts.add(headerLength + body.size());
            body.writeBytes(e);
        }
        int idxt = headerLength + body.size();
        body.writeBytes("IDXT".getBytes(StandardCharsets.US_ASCII));
        for (int s : starts) {
            u16(body, s);
        }
        byte[] h = new byte[headerLength];
        h[0] = 'I';
        h[1] = 'N';
        h[2] = 'D';
        h[3] = 'X';
        put32(h, 4, headerLength);
        put32(h, 20, head ? 0 : idxt);
        put32(h, 24, head ? records : entries.size());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(h);
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    static byte[] entry(String label, int control, long... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] l = label.getBytes(StandardCharsets.US_ASCII);
        out.write(l.length);
        out.writeBytes(l);
        out.write(control);
        for (long v : values) {
            out.writeBytes(varint(v));
        }
        return out.toByteArray();
    }

    static byte[] varint(long v) {
        ByteArrayOutputStream groups = new ByteArrayOutputStream();
        List<Integer> parts = new ArrayList<>();
        do {
            parts.add(0, (int) (v & 0x7F));
            v >>>= 7;
        } while (v != 0);
        for (int i = 0; i < parts.size(); i++) {
            groups.write(parts.get(i) | (i == parts.size() - 1 ? 0x80 : 0));
        }
        return groups.toByteArray();
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

    static void put16(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 8);
        b[at + 1] = (byte) v;
    }

    static void put32(byte[] b, int at, long v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }
}
