package stirling.software.officeconvert.topdf.font;

import java.awt.AWTError;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class ColorEmoji {

    public record Bitmap(int glyph, byte[] png, float left, float top, float width, float height) {}

    private static final int MAX_CLUSTER_CHARS = 64;
    private static final int MAX_TABLE_BYTES = 4 << 20;
    private static final int MAX_BITMAP_BYTES = 1 << 20;
    private static final int MAX_STRIKE_GLYPHS = 65_536;
    private static final long MAX_STRIKE_WORK = 4L * MAX_STRIKE_GLYPHS;
    private static final int CACHED = 512;
    private static final int[] NONE = {};
    private static final FontRenderContext FRC = new FontRenderContext(null, false, true);
    private static final Map<Path, Optional<ColorEmoji>> OPENED = new ConcurrentHashMap<>();

    private record Location(long offset, int length, int format, byte[] metrics) {}

    private final Path file;
    private final Font awt;
    private final int unitsPerEm;
    private final float ascent;
    private final float descent;
    private final int[] advances;
    private final int ppem;
    private final Map<Integer, Location> locations;
    private final Lru<Integer, Optional<Bitmap>> bitmaps = new Lru<>(CACHED);
    private final Lru<String, int[]> shaped = new Lru<>(CACHED);

    private ColorEmoji(Path file, Font awt, int unitsPerEm, float ascent, float descent, int[] advances, int ppem,
            Map<Integer, Location> locations) {
        this.file = file;
        this.awt = awt;
        this.unitsPerEm = unitsPerEm;
        this.ascent = ascent;
        this.descent = descent;
        this.advances = advances;
        this.ppem = ppem;
        this.locations = locations;
    }

    static ColorEmoji open(Path file) {
        return OPENED.computeIfAbsent(file.toAbsolutePath().normalize(), f -> Optional.ofNullable(read(f)))
                .orElse(null);
    }

    public float ascent() {
        return ascent;
    }

    public float descent() {
        return descent;
    }

    public static int clusterEnd(CharSequence s, int start) {
        int n = s.length();
        int cp = Character.codePointAt(s, start);
        int i = start + Character.charCount(cp);
        if (regional(cp)) {
            return i < n && regional(Character.codePointAt(s, i)) ? i + 2 : -1;
        }
        if (cp >= '0' && cp <= '9' || cp == '#' || cp == '*') {
            int j = i < n && s.charAt(i) == 0xFE0F ? i + 1 : i;
            return j < n && s.charAt(j) == 0x20E3 ? j + 1 : -1;
        }
        if (!Character.isEmoji(cp)) {
            return -1;
        }
        boolean colour = Character.isEmojiPresentation(cp);
        if (i < n && s.charAt(i) == 0xFE0E) {
            return -1;
        }
        int end = extend(s, i, cp);
        colour |= end > i;
        if (!colour) {
            return -1;
        }
        while (end + 1 < n && s.charAt(end) == 0x200D && end - start < MAX_CLUSTER_CHARS) {
            int next = Character.codePointAt(s, end + 1);
            if (!Character.isEmoji(next) && !Character.isExtendedPictographic(next)) {
                break;
            }
            end = extend(s, end + 1 + Character.charCount(next), next);
        }
        return end;
    }

    private static int extend(CharSequence s, int i, int base) {
        int n = s.length();
        if (i < n && s.charAt(i) == 0xFE0F) {
            i++;
        }
        if (i + 1 < n && Character.isEmojiModifierBase(base) && Character.isEmojiModifier(Character.codePointAt(s, i))) {
            i += 2;
        }
        for (int k = 0; k < 32 && i + 1 < n; k++) {
            int tag = Character.codePointAt(s, i);
            if (tag < 0xE0020 || tag > 0xE007F) {
                break;
            }
            i += 2;
        }
        return i;
    }

    private static boolean regional(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    public int[] glyphs(String cluster) {
        int[] known = shaped.get(cluster);
        if (known != null) {
            return known.length == 0 ? null : known;
        }
        int[] made = shape(cluster);
        shaped.putIfAbsent(cluster, made);
        return made.length == 0 ? null : made;
    }

    private int[] shape(String cluster) {
        int[] codes;
        try {
            char[] chars = cluster.toCharArray();
            GlyphVector gv = awt.layoutGlyphVector(FRC, chars, 0, chars.length, Font.LAYOUT_LEFT_TO_RIGHT);
            codes = gv.getGlyphCodes(0, gv.getNumGlyphs(), null);
        } catch (RuntimeException | LinkageError | InternalError | AWTError e) {
            return NONE;
        }
        int[] out = new int[codes.length];
        int m = 0;
        for (int code : codes) {
            if (code >= 0xFFFE) {
                continue;
            }
            if (code <= 0 || code >= advances.length) {
                return NONE;
            }
            if (locations.containsKey(code)) {
                out[m++] = code;
            } else if (advances[code] > 0) {
                return NONE;
            }
        }
        return m == 0 ? NONE : Arrays.copyOf(out, m);
    }

    public int run(CharSequence s, int start) {
        int end = start;
        while (end < s.length()) {
            int next = clusterEnd(s, end);
            if (next <= end || glyphs(s.subSequence(end, next).toString()) == null) {
                break;
            }
            end = next;
        }
        return end;
    }

    public float advance(String text, float each) {
        float total = 0;
        for (int i = 0; i < text.length(); ) {
            int end = clusterEnd(text, i);
            int[] g = end <= i ? null : glyphs(text.substring(i, end));
            if (g == null) {
                end = i + Character.charCount(text.codePointAt(i));
            } else {
                total += each > 0 ? each * g.length : advance(g);
            }
            i = end;
        }
        return total;
    }

    public static GlyphRun carrier(FontFace face, String text, float width, float size) {
        int glyph = face.glyph(' ');
        int units = glyph > 0 ? face.glyphAdvance(glyph) : 0;
        if (units <= 0 || !(width > 0) || !(size > 0) || text.isEmpty()) {
            return null;
        }
        GlyphRun run = new GlyphRun(face, text, false, true, new int[] {glyph}, new float[] {0}, new float[] {0},
                new int[] {0}, units);
        return run.stretched(width * face.unitsPerEm() / size / units);
    }

    public float advance(int[] glyphs) {
        float total = 0;
        for (int g : glyphs) {
            total += advances[g];
        }
        return total / unitsPerEm;
    }

    public float advance(int glyph) {
        return (float) advances[glyph] / unitsPerEm;
    }

    public Bitmap bitmap(int glyph) {
        Optional<Bitmap> known = bitmaps.get(glyph);
        if (known == null) {
            Bitmap read;
            try {
                read = load(glyph);
            } catch (IOException | RuntimeException e) {
                read = null;
            }
            known = Optional.ofNullable(read);
            bitmaps.putIfAbsent(glyph, known);
        }
        return known.orElse(null);
    }

    private Bitmap load(int glyph) throws IOException {
        Location at = locations.get(glyph);
        if (at == null) {
            return null;
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer b = read(ch, at.offset(), at.length());
            byte[] metrics = at.metrics();
            int skip = 0;
            if (at.format() == 17) {
                metrics = new byte[] {b.get(0), b.get(1), b.get(2), b.get(3)};
                skip = 5;
            } else if (at.format() == 18) {
                metrics = new byte[] {b.get(0), b.get(1), b.get(2), b.get(3)};
                skip = 8;
            } else if (at.format() != 19 || metrics == null) {
                return null;
            }
            int length = b.getInt(skip);
            if (length <= 0 || length > MAX_BITMAP_BYTES || skip + 4 + length > b.capacity()) {
                return null;
            }
            byte[] png = new byte[length];
            b.get(skip + 4, png);
            if (png.length < 8 || (png[0] & 0xFF) != 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') {
                return null;
            }
            float px = ppem;
            int height = metrics[0] & 0xFF;
            int width = metrics[1] & 0xFF;
            return new Bitmap(glyph, png, metrics[2] / px, metrics[3] / px, width / px, height / px);
        }
    }

    private static ColorEmoji read(Path file) {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = ch.size();
            if (size < 12) {
                return null;
            }
            ByteBuffer head = read(ch, 0, 12);
            int version = head.getInt(0);
            if (version != 0x00010000 && version != 0x74727565) {
                return null;
            }
            int count = Math.min(head.getShort(4) & 0xFFFF, 64);
            ByteBuffer dir = read(ch, 12, 16 * count);
            Map<String, long[]> tables = new HashMap<>();
            for (int i = 0; i < count; i++) {
                byte[] tag = new byte[4];
                dir.get(16 * i, tag);
                long at = dir.getInt(16 * i + 8) & 0xFFFFFFFFL;
                long length = dir.getInt(16 * i + 12) & 0xFFFFFFFFL;
                if (length > 0 && at + length <= size) {
                    tables.put(new String(tag, StandardCharsets.ISO_8859_1), new long[] {at, length});
                }
            }
            for (String need : new String[] {"CBDT", "CBLC", "head", "hhea", "hmtx", "maxp", "cmap"}) {
                if (!tables.containsKey(need)) {
                    return null;
                }
            }
            ByteBuffer headTable = table(ch, tables.get("head"));
            ByteBuffer hhea = table(ch, tables.get("hhea"));
            ByteBuffer maxp = table(ch, tables.get("maxp"));
            ByteBuffer hmtx = table(ch, tables.get("hmtx"));
            ByteBuffer cblc = table(ch, tables.get("CBLC"));
            if (headTable.capacity() < 20 || hhea.capacity() < 36 || maxp.capacity() < 6 || cblc.capacity() < 8) {
                return null;
            }
            int unitsPerEm = headTable.getShort(18) & 0xFFFF;
            if (unitsPerEm < 16) {
                return null;
            }
            int glyphs = maxp.getShort(4) & 0xFFFF;
            int metrics = Math.min(hhea.getShort(34) & 0xFFFF, glyphs);
            if (metrics < 1 || hmtx.capacity() < 4 * metrics) {
                return null;
            }
            int[] advances = new int[glyphs];
            for (int g = 0; g < glyphs; g++) {
                advances[g] = hmtx.getShort(4 * Math.min(g, metrics - 1)) & 0xFFFF;
            }
            Map<Integer, Location> locations = new HashMap<>();
            int ppem = strike(cblc, tables.get("CBDT")[0], tables.get("CBDT")[1], glyphs, locations);
            if (ppem <= 0 || locations.isEmpty()) {
                return null;
            }
            Font awt;
            try (InputStream in = Files.newInputStream(file)) {
                awt = Font.createFont(Font.TRUETYPE_FONT, in).deriveFont((float) unitsPerEm);
            }
            return new ColorEmoji(file, awt, unitsPerEm, hhea.getShort(4) / (float) unitsPerEm,
                    -hhea.getShort(6) / (float) unitsPerEm, advances, ppem, locations);
        } catch (IOException | FontFormatException | RuntimeException | LinkageError | AWTError e) {
            return null;
        }
    }

    private static int strike(ByteBuffer cblc, long cbdt, long cbdtLength, int glyphs, Map<Integer, Location> out) {
        long sizes = cblc.getInt(4) & 0xFFFFFFFFL;
        int best = -1;
        int ppem = 0;
        for (int s = 0; s < Math.min(sizes, 64); s++) {
            int rec = 8 + 48 * s;
            if (rec + 48 > cblc.capacity()) {
                break;
            }
            int p = cblc.get(rec + 45) & 0xFF;
            if (p > ppem) {
                ppem = p;
                best = rec;
            }
        }
        if (best < 0) {
            return 0;
        }
        int array = cblc.getInt(best);
        int tables = Math.min(cblc.getInt(best + 8), 4096);
        long work = 0;
        for (int t = 0; t < tables; t++) {
            int entry = array + 8 * t;
            if (array < 0 || entry + 8 > cblc.capacity()) {
                break;
            }
            int first = cblc.getShort(entry) & 0xFFFF;
            int last = cblc.getShort(entry + 2) & 0xFFFF;
            int sub = array + cblc.getInt(entry + 4);
            if (sub < 0 || sub + 8 > cblc.capacity() || last < first || last >= glyphs) {
                continue;
            }
            int indexFormat = cblc.getShort(sub) & 0xFFFF;
            int imageFormat = cblc.getShort(sub + 2) & 0xFFFF;
            long data = cbdt + (cblc.getInt(sub + 4) & 0xFFFFFFFFL);
            work += index(cblc, sub + 8, indexFormat, imageFormat, data, cbdt + cbdtLength, first, last, out);
            if (out.size() > MAX_STRIKE_GLYPHS || work > MAX_STRIKE_WORK) {
                out.clear();
                return 0;
            }
        }
        return ppem;
    }

    private static long index(ByteBuffer cblc, int at, int indexFormat, int imageFormat, long data, long end,
            int first, int last, Map<Integer, Location> out) {
        int n = last - first + 1;
        switch (indexFormat) {
            case 1, 3 -> {
                int step = indexFormat == 1 ? 4 : 2;
                if (at + step * (n + 1) > cblc.capacity()) {
                    return 0;
                }
                for (int i = 0; i < n; i++) {
                    long from = offset(cblc, at + step * i, step);
                    long to = offset(cblc, at + step * (i + 1), step);
                    put(out, first + i, data + from, to - from, imageFormat, null, end);
                }
                return n;
            }
            case 2 -> {
                if (at + 12 > cblc.capacity()) {
                    return 0;
                }
                long size = cblc.getInt(at) & 0xFFFFFFFFL;
                byte[] big = big(cblc, at + 4);
                for (int i = 0; i < n; i++) {
                    put(out, first + i, data + size * i, size, imageFormat, big, end);
                }
                return n;
            }
            case 4 -> {
                if (at + 4 > cblc.capacity()) {
                    return 0;
                }
                int pairs = cblc.getInt(at);
                if (pairs < 0 || at + 4 + 4L * (pairs + 1) > cblc.capacity()) {
                    return 0;
                }
                for (int i = 0; i < pairs; i++) {
                    int rec = at + 4 + 4 * i;
                    int glyph = cblc.getShort(rec) & 0xFFFF;
                    long from = cblc.getShort(rec + 2) & 0xFFFF;
                    long to = cblc.getShort(rec + 6) & 0xFFFF;
                    put(out, glyph, data + from, to - from, imageFormat, null, end);
                }
                return pairs;
            }
            case 5 -> {
                if (at + 16 > cblc.capacity()) {
                    return 0;
                }
                long size = cblc.getInt(at) & 0xFFFFFFFFL;
                byte[] big = big(cblc, at + 4);
                int count = cblc.getInt(at + 12);
                if (count < 0 || at + 16 + 2L * count > cblc.capacity()) {
                    return 0;
                }
                for (int i = 0; i < count; i++) {
                    put(out, cblc.getShort(at + 16 + 2 * i) & 0xFFFF, data + size * i, size, imageFormat, big, end);
                }
                return count;
            }
            default -> {
                return 0;
            }
        }
    }

    private static long offset(ByteBuffer b, int at, int step) {
        return step == 4 ? b.getInt(at) & 0xFFFFFFFFL : b.getShort(at) & 0xFFFF;
    }

    private static byte[] big(ByteBuffer b, int at) {
        return new byte[] {b.get(at), b.get(at + 1), b.get(at + 2), b.get(at + 3)};
    }

    private static void put(Map<Integer, Location> out, int glyph, long offset, long length, int format,
            byte[] metrics, long end) {
        if (length > 4 && length <= MAX_BITMAP_BYTES + 16 && offset + length <= end
                && (format == 17 || format == 18 || format == 19)) {
            out.put(glyph, new Location(offset, (int) length, format, metrics));
        }
    }

    private static ByteBuffer table(FileChannel ch, long[] t) throws IOException {
        if (t[1] > MAX_TABLE_BYTES) {
            throw new IOException("table too large");
        }
        return read(ch, t[0], (int) t[1]);
    }

    private static ByteBuffer read(FileChannel ch, long at, int length) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(length);
        while (b.hasRemaining()) {
            if (ch.read(b, at + b.position()) < 0) {
                throw new IOException("short read");
            }
        }
        return b.flip();
    }
}
