package stirling.software.officeconvert.topdf.font;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FontScanner {

    private static final int MAX_TABLES = 512;

    private static final int MAX_FACES = 256;

    private static final int MAX_NAME_BYTES = 1 << 20;

    private static final int MAX_CMAP_BYTES = 8 << 20;

    private static final int MAX_RANGES = 1 << 16;

    private static final Charset MAC_ROMAN = macRoman();

    private FontScanner() {}

    static List<FontEntry> scan(Path file) {
        try (Source src = Source.of(file)) {
            return scan(src, file, null);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    static List<FontEntry> scan(byte[] data) {
        try (Source src = Source.of(data)) {
            return scan(src, null, data);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    static byte[] whole(FontEntry entry, long maxBytes) throws IOException {
        try (Source src = entry.file() != null ? Source.of(entry.file()) : Source.of(entry.data())) {
            if (entry.index() < 0) {
                if (src.length() > maxBytes) {
                    throw new IOException("the font is too large to embed whole");
                }
                return src.slice(0, (int) src.length()).bytes;
            }
            Slice dir = src.slice(entry.offset(), 12);
            int n = dir.u16(4);
            if (n <= 0 || n > MAX_TABLES) {
                throw new IOException("the collection face has " + n + " tables");
            }
            Slice records = src.slice(entry.offset() + 12, 16 * n);
            long total = 12 + 16L * n;
            for (int i = 0; i < n; i++) {
                total += (records.u32(16 * i + 12) + 3) & ~3L;
            }
            if (total > maxBytes) {
                throw new IOException("the font is too large to embed whole");
            }
            ByteBuffer out = ByteBuffer.allocate((int) total);
            out.put(dir.bytes);
            int at = 12 + 16 * n;
            for (int i = 0; i < n; i++) {
                long offset = records.u32(16 * i + 8);
                int length = (int) records.u32(16 * i + 12);
                out.put(12 + 16 * i, records.bytes, 16 * i, 8);
                out.putInt(12 + 16 * i + 8, at);
                out.putInt(12 + 16 * i + 12, length);
                out.put(at, src.slice(offset, length).bytes);
                at += (length + 3) & ~3;
            }
            return out.array();
        }
    }

    static int[] coverage(FontEntry entry) {
        try (Source src = entry.file() != null ? Source.of(entry.file()) : Source.of(entry.data())) {
            long[] cmap = tables(src, entry.offset()).get("cmap");
            return cmap == null ? new int[0] : ranges(src, cmap, entry.symbolCmap());
        } catch (IOException | RuntimeException e) {
            return new int[0];
        }
    }

    private static List<FontEntry> scan(Source src, Path file, byte[] data) throws IOException {
        Slice head = src.slice(0, 12);
        if (head.u32(0) == 0x74746366L) {
            int count = (int) Math.min(head.u32(8), MAX_FACES);
            Slice offsets = src.slice(12, 4 * count);
            List<FontEntry> faces = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                try {
                    FontEntry e = face(src, offsets.u32(4 * i), i, file, data);
                    if (e != null) {
                        faces.add(e);
                    }
                } catch (IOException | RuntimeException e) {
                    continue;
                }
            }
            return faces;
        }
        FontEntry e = face(src, 0, -1, file, data);
        return e == null ? List.of() : List.of(e);
    }

    private static FontEntry face(Source src, long offset, int index, Path file, byte[] data) throws IOException {
        long version = src.slice(offset, 4).u32(0);
        boolean otto = version == 0x4F54544FL;
        if (version != 0x00010000L && version != 0x74727565L && !otto) {
            return null;
        }
        Map<String, long[]> tables = tables(src, offset);
        long[] name = tables.get("name");
        long[] cmap = tables.get("cmap");
        long[] head = tables.get("head");
        if (name == null || cmap == null || head == null || !tables.containsKey("hhea") || !tables.containsKey("hmtx")
                || !tables.containsKey("maxp")) {
            return null;
        }
        Names names = names(src.slice(name[0], (int) Math.min(name[1], MAX_NAME_BYTES)));
        if (names.family == null) {
            return null;
        }
        Slice headTable = src.slice(head[0], (int) Math.min(head[1], 54));
        int macStyle = headTable.length() >= 46 ? headTable.u16(44) : 0;
        long[] os2 = tables.get("OS/2");
        int weight = 400;
        int fsType = 0;
        int fsSelection = -1;
        if (os2 != null && os2[1] >= 64) {
            Slice t = src.slice(os2[0], 64);
            weight = t.u16(4);
            fsType = t.u16(8);
            fsSelection = t.u16(62);
        }
        if (weight < 1 || weight > 1000) {
            weight = 400;
        }
        if (weight == 400) {
            weight = Weights.of(names.family);
        }
        boolean bold = fsSelection >= 0 ? (fsSelection & 0x20) != 0 : (macStyle & 1) != 0;
        boolean italic = fsSelection >= 0 ? (fsSelection & 0x201) != 0 : (macStyle & 2) != 0;
        if (bold && weight < 600) {
            weight = 700;
        }
        boolean cff = otto || tables.containsKey("CFF ") || tables.containsKey("CFF2");
        int cmapKind = cmapKind(src.slice(cmap[0], (int) Math.min(cmap[1], 4 + 8 * MAX_TABLES)));
        String unusable = null;
        if (cff) {
            unusable = "it has PostScript (CFF) outlines, which PDFBox cannot embed";
        } else if (!tables.containsKey("glyf") || !tables.containsKey("loca")) {
            unusable = "it has no TrueType outlines";
        } else if (os2 == null || !tables.containsKey("post")) {
            unusable = "it lacks the OS/2 or post table";
        } else if ((fsType & 0x000F) == 0x0002 || (fsType & 0x0200) != 0) {
            unusable = "its licence does not permit embedding";
        } else if (cmapKind == 0) {
            unusable = "it has no Unicode character map";
        }
        return new FontEntry(file, data, index, offset, names.postScript, names.family, names.subfamily,
                names.fullName, List.copyOf(names.legacy), List.copyOf(names.typographic), weight, bold, italic,
                cmapKind == 2, (fsType & 0x0100) != 0, unusable);
    }

    private static Map<String, long[]> tables(Source src, long offset) throws IOException {
        int count = Math.min(src.slice(offset + 4, 2).u16(0), MAX_TABLES);
        Slice dir = src.slice(offset + 12, 16 * count);
        Map<String, long[]> tables = new HashMap<>();
        for (int i = 0; i < count; i++) {
            int rec = 16 * i;
            String tag = new String(dir.bytes, rec, 4, StandardCharsets.ISO_8859_1);
            long at = dir.u32(rec + 8);
            long length = dir.u32(rec + 12);
            if (length > 0 && at + length <= src.length()) {
                tables.putIfAbsent(tag, new long[] {at, length});
            }
        }
        return tables;
    }

    private static final class Names {
        final Set<String> legacy = new LinkedHashSet<>();
        final Set<String> typographic = new LinkedHashSet<>();
        String family;
        String subfamily;
        String fullName;
        String postScript;
        final int[] ranks = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
    }

    private static Names names(Slice t) {
        Names out = new Names();
        if (t.length() < 6) {
            return out;
        }
        int count = t.u16(2);
        int strings = t.u16(4);
        for (int i = 0; i < count; i++) {
            int rec = 6 + 12 * i;
            if (rec + 12 > t.length()) {
                break;
            }
            int platform = t.u16(rec);
            int encoding = t.u16(rec + 2);
            int language = t.u16(rec + 4);
            int id = t.u16(rec + 6);
            int len = t.u16(rec + 8);
            int pos = strings + t.u16(rec + 10);
            if (id != 1 && id != 2 && id != 4 && id != 6 && id != 16 || len == 0 || pos + len > t.length()) {
                continue;
            }
            String value = decode(t.bytes, pos, len, platform, encoding);
            if (value == null || value.isBlank()) {
                continue;
            }
            value = value.strip();
            int rank = rank(platform, language);
            switch (id) {
                case 1 -> {
                    out.legacy.add(value);
                    if (rank < out.ranks[0]) {
                        out.family = value;
                        out.ranks[0] = rank;
                    }
                }
                case 2 -> {
                    if (rank < out.ranks[1]) {
                        out.subfamily = value;
                        out.ranks[1] = rank;
                    }
                }
                case 4 -> {
                    if (rank < out.ranks[2]) {
                        out.fullName = value;
                        out.ranks[2] = rank;
                    }
                }
                case 6 -> {
                    if (rank < out.ranks[3]) {
                        out.postScript = value;
                        out.ranks[3] = rank;
                    }
                }
                default -> out.typographic.add(value);
            }
        }
        return out;
    }

    private static int rank(int platform, int language) {
        if (platform == 3) {
            return language == 0x409 ? 0 : 2;
        }
        if (platform == 1) {
            return language == 0 ? 1 : 3;
        }
        return 4;
    }

    private static String decode(byte[] raw, int pos, int len, int platform, int encoding) {
        if (platform == 0 || platform == 3 && (encoding == 0 || encoding == 1 || encoding == 10)) {
            return new String(raw, pos, len & ~1, StandardCharsets.UTF_16BE);
        }
        if (platform == 1 && encoding == 0) {
            return new String(raw, pos, len, MAC_ROMAN);
        }
        return null;
    }

    private static int cmapKind(Slice t) {
        if (t.length() < 4) {
            return 0;
        }
        int count = t.u16(2);
        boolean unicode = false;
        boolean symbol = false;
        for (int i = 0; i < count && 4 + 8 * i + 8 <= t.length(); i++) {
            int platform = t.u16(4 + 8 * i);
            int encoding = t.u16(6 + 8 * i);
            if (platform == 0 || platform == 3 && (encoding == 1 || encoding == 10)) {
                unicode = true;
            } else if (platform == 3 && encoding == 0) {
                symbol = true;
            }
        }
        return unicode ? 1 : symbol ? 2 : 0;
    }

    private static int[] ranges(Source src, long[] cmap, boolean symbolOnly) throws IOException {
        Slice t = src.slice(cmap[0], (int) Math.min(cmap[1], MAX_CMAP_BYTES));
        int count = t.u16(2);
        int best = -1;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < count && 4 + 8 * i + 8 <= t.length(); i++) {
            int platform = t.u16(4 + 8 * i);
            int encoding = t.u16(6 + 8 * i);
            int rank = platform == 0 && encoding == 4 ? 0 : platform == 3 && encoding == 10 ? 1
                    : platform == 0 && encoding == 3 ? 2 : platform == 3 && encoding == 1 ? 3
                    : platform == 3 && encoding == 0 ? 4 : platform == 0 ? 5 : Integer.MAX_VALUE;
            long at = t.u32(8 + 8 * i);
            if (rank < bestRank && at + 2 <= t.length()) {
                bestRank = rank;
                best = (int) at;
            }
        }
        if (best < 0) {
            return new int[0];
        }
        List<int[]> out = new ArrayList<>();
        int format = t.u16(best);
        if (format == 4 && best + 14 <= t.length()) {
            int segments = t.u16(best + 6) / 2;
            for (int s = 0; s < segments && out.size() < MAX_RANGES; s++) {
                int endAt = best + 14 + 2 * s;
                int startAt = best + 16 + 2 * segments + 2 * s;
                if (startAt + 2 > t.length()) {
                    break;
                }
                int end = t.u16(endAt);
                int start = t.u16(startAt);
                if (start <= end && start != 0xFFFF) {
                    out.add(new int[] {start, end});
                }
            }
        } else if ((format == 12 || format == 13) && best + 16 <= t.length()) {
            long groups = Math.min(t.u32(best + 12), MAX_RANGES);
            for (int g = 0; g < groups; g++) {
                int rec = best + 16 + 12 * g;
                if (rec + 8 > t.length()) {
                    break;
                }
                long start = t.u32(rec);
                long end = t.u32(rec + 4);
                if (start <= end && end <= Character.MAX_CODE_POINT) {
                    out.add(new int[] {(int) start, (int) end});
                }
            }
        } else if (format == 6 && best + 10 <= t.length()) {
            int first = t.u16(best + 6);
            int entries = t.u16(best + 8);
            if (entries > 0) {
                out.add(new int[] {first, first + entries - 1});
            }
        } else if (format == 0) {
            out.add(new int[] {0, 255});
        } else {
            out.add(new int[] {0, Character.MAX_CODE_POINT});
        }
        if (symbolOnly) {
            out.add(new int[] {0x20, 0xFF});
        }
        out.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] r : out) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && r[0] <= last[1] + 1) {
                last[1] = Math.max(last[1], r[1]);
            } else {
                merged.add(r);
            }
        }
        int[] flat = new int[merged.size() * 2];
        for (int i = 0; i < merged.size(); i++) {
            flat[2 * i] = merged.get(i)[0];
            flat[2 * i + 1] = merged.get(i)[1];
        }
        return flat;
    }

    private static Charset macRoman() {
        try {
            return Charset.forName("x-MacRoman");
        } catch (IllegalArgumentException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    private static final class Slice {
        final byte[] bytes;

        Slice(byte[] bytes) {
            this.bytes = bytes;
        }

        int length() {
            return bytes.length;
        }

        int u16(int pos) {
            return (bytes[pos] & 0xFF) << 8 | (bytes[pos + 1] & 0xFF);
        }

        long u32(int pos) {
            return ((long) (bytes[pos] & 0xFF) << 24) | (bytes[pos + 1] & 0xFF) << 16 | (bytes[pos + 2] & 0xFF) << 8
                    | (bytes[pos + 3] & 0xFF);
        }
    }

    private abstract static class Source implements Closeable {

        abstract long length();

        abstract Slice slice(long pos, int len) throws IOException;

        static Source of(Path file) throws IOException {
            RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
            long length;
            try {
                length = raf.length();
            } catch (IOException e) {
                raf.close();
                throw e;
            }
            return new Source() {
                @Override
                long length() {
                    return length;
                }

                @Override
                Slice slice(long pos, int len) throws IOException {
                    if (pos < 0 || len < 0 || pos + len > length) {
                        throw new IOException("Font data out of range");
                    }
                    byte[] b = new byte[len];
                    raf.seek(pos);
                    raf.readFully(b);
                    return new Slice(b);
                }

                @Override
                public void close() throws IOException {
                    raf.close();
                }
            };
        }

        static Source of(byte[] data) {
            return new Source() {
                @Override
                long length() {
                    return data.length;
                }

                @Override
                Slice slice(long pos, int len) throws IOException {
                    if (pos < 0 || len < 0 || pos + len > data.length) {
                        throw new IOException("Font data out of range");
                    }
                    byte[] b = new byte[len];
                    System.arraycopy(data, (int) pos, b, 0, len);
                    return new Slice(b);
                }

                @Override
                public void close() {}
            };
        }
    }
}
