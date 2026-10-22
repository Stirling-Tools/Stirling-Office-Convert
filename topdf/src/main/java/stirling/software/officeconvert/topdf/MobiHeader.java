package stirling.software.officeconvert.topdf;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

record MobiHeader(int compression, long textLength, int textRecords, int encryption, int encoding, int version,
        int firstImage, int huffRecord, int huffCount, int extraFlags, int fdst, int fdstCount, int fragments,
        int skeletons, String fullName, Map<Integer, List<byte[]>> exth) {

    static final int NONE = 1;

    static final int PALMDOC = 2;

    static final int HUFF_CDIC = 17480;

    static final int EXTH_AUTHOR = 100;

    static final int EXTH_KF8_BOUNDARY = 121;

    static final int EXTH_COVER = 201;

    static final int EXTH_TITLE = 503;

    private static final int MAX_EXTH_RECORDS = 10_000;

    static MobiHeader parse(byte[] r, boolean mobi) throws IOException {
        if (r.length < 16) {
            throw new IOException("The eBook is damaged: its first record is too short");
        }
        int compression = PalmDatabase.u16(r, 0);
        long textLength = PalmDatabase.u32(r, 4);
        int textRecords = PalmDatabase.u16(r, 8);
        if (!mobi || r.length < 24 || r[16] != 'M' || r[17] != 'O' || r[18] != 'B' || r[19] != 'I') {
            return new MobiHeader(compression, textLength, textRecords, 0, 1252, 0, -1, -1, 0, 0, -1, 0, -1, -1,
                    null, Map.of());
        }
        int length = PalmDatabase.index(r, 20);
        int end = length < 0 ? r.length : (int) Math.min(r.length, 16L + length);
        int encryption = PalmDatabase.u16(r, 12);
        int encoding = field(r, end, 28);
        int version = field(r, end, 36);
        int firstImage = field(r, end, 108);
        int huffRecord = field(r, end, 112);
        int huffCount = field(r, end, 116);
        int exthFlags = field(r, end, 128);
        int extraFlags = length >= 0xE4 && end >= 0xF4 ? PalmDatabase.u16(r, 0xF2) : 0;
        boolean kf8 = version >= 8;
        int fdst = kf8 ? field(r, end, 192) : -1;
        int fdstCount = kf8 ? field(r, end, 196) : 0;
        int fragments = kf8 ? field(r, end, 0xF8) : -1;
        int skeletons = kf8 ? field(r, end, 0xFC) : -1;
        Charset charset = encoding == 65001 ? StandardCharsets.UTF_8 : Charset.forName("windows-1252");
        String fullName = null;
        int nameOffset = field(r, r.length, 84);
        int nameLength = field(r, r.length, 88);
        if (nameOffset > 0 && nameLength > 0 && (long) nameOffset + nameLength <= r.length) {
            fullName = new String(r, nameOffset, nameLength, charset).strip();
        }
        Map<Integer, List<byte[]>> exth = (exthFlags & 0x40) != 0 && length > 0 ? exth(r, 16 + length) : Map.of();
        return new MobiHeader(compression, textLength, textRecords, encryption, encoding, version, firstImage,
                huffRecord, huffCount, extraFlags, fdst, fdstCount, fragments, skeletons, fullName, exth);
    }

    private static int field(byte[] r, int end, int offset) {
        return offset + 4 <= end ? PalmDatabase.index(r, offset) : -1;
    }

    private static Map<Integer, List<byte[]>> exth(byte[] r, int at) {
        Map<Integer, List<byte[]>> out = new HashMap<>();
        if (at < 0 || at + 12 > r.length || r[at] != 'E' || r[at + 1] != 'X' || r[at + 2] != 'T' || r[at + 3] != 'H') {
            return out;
        }
        long count = PalmDatabase.u32(r, at + 8);
        int p = at + 12;
        for (long i = 0; i < Math.min(count, MAX_EXTH_RECORDS) && p + 8 <= r.length; i++) {
            int type = PalmDatabase.index(r, p);
            long size = PalmDatabase.u32(r, p + 4);
            if (size < 8 || p + size > r.length) {
                break;
            }
            byte[] value = new byte[(int) size - 8];
            System.arraycopy(r, p + 8, value, 0, value.length);
            out.computeIfAbsent(type, k -> new ArrayList<>()).add(value);
            p += (int) size;
        }
        return out;
    }

    Charset charset() {
        return encoding == 65001 ? StandardCharsets.UTF_8 : Charset.forName("windows-1252");
    }

    String text(int type) {
        List<byte[]> values = exth.get(type);
        if (values == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (byte[] v : values) {
            String s = new String(v, charset()).strip();
            if (!s.isEmpty() && !parts.contains(s)) {
                parts.add(s);
            }
        }
        return parts.isEmpty() ? null : String.join(" & ", parts);
    }

    int number(int type) {
        List<byte[]> values = exth.get(type);
        if (values == null || values.isEmpty() || values.get(0).length != 4) {
            return -1;
        }
        return PalmDatabase.index(values.get(0), 0);
    }

    String title() {
        String t = text(EXTH_TITLE);
        return t != null ? t : fullName == null || fullName.isEmpty() ? null : fullName;
    }
}
