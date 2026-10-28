package stirling.software.officeconvert.pdfa;

import java.awt.geom.GeneralPath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.fontbox.ttf.HorizontalMetricsTable;
import org.apache.fontbox.ttf.TTFTable;
import org.apache.fontbox.ttf.TrueTypeFont;

import stirling.software.officeconvert.extract.PdfFiles;

final class TrueTypeWriter {

    private record Glyph(byte[] buffer, int offset, int length, int advance, int lsb) {

        Glyph(byte[] data, int advance, int lsb) {
            this(data, 0, data.length, advance, lsb);
        }

        ByteBuffer view() {
            return ByteBuffer.wrap(buffer, offset, length).slice();
        }

        byte[] data() {
            return offset == 0 && length == buffer.length ? buffer
                    : java.util.Arrays.copyOfRange(buffer, offset, offset + length);
        }
    }

    private final TrueTypeFont source;

    private final int unitsPerEm;

    private final byte[] glyf;

    private final int glyfStart;

    private final int glyfLength;

    private final long[] loca;

    private final HorizontalMetricsTable hmtx;

    private final List<Glyph> glyphs = new ArrayList<>();

    private final Map<Integer, Integer> components = new HashMap<>();

    private final List<Integer> pending = new ArrayList<>();

    private int maxPoints;

    private int maxContours;

    private long glyphBytes;

    private boolean hinting = true;

    TrueTypeWriter(TrueTypeFont source) throws IOException {
        this(source, null);
    }

    TrueTypeWriter(TrueTypeFont source, byte[] parsedFrom) throws IOException {
        this.source = source;
        this.unitsPerEm = source.getUnitsPerEm();
        TTFTable g = source.getTableMap().get("glyf");
        if (g == null || source.getIndexToLocation() == null) {
            throw new IOException("The font has no TrueType outlines");
        }
        if (parsedFrom != null && g.getOffset() >= 0 && g.getOffset() + g.getLength() <= parsedFrom.length) {
            this.glyf = parsedFrom;
            this.glyfStart = (int) g.getOffset();
            this.glyfLength = (int) g.getLength();
        } else {
            this.glyf = source.getTableBytes(g);
            this.glyfStart = 0;
            this.glyfLength = glyf.length;
        }
        this.loca = source.getIndexToLocation().getOffsets();
        this.hmtx = source.getHorizontalMetrics();
        glyphs.add(copyGlyph(0, advanceOf(0)));
    }

    TrueTypeWriter(int unitsPerEm) {
        this.source = null;
        this.unitsPerEm = unitsPerEm;
        this.glyf = null;
        this.glyfStart = 0;
        this.glyfLength = 0;
        this.loca = null;
        this.hmtx = null;
        glyphs.add(new Glyph(new byte[0], unitsPerEm / 2, 0));
    }

    void dropHinting() {
        hinting = false;
    }

    int unitsPerEm() {
        return unitsPerEm;
    }

    int size() {
        return glyphs.size();
    }

    long glyphBytes() {
        return glyphBytes;
    }

    boolean hasGlyph(int sourceGid) {
        if (loca == null || sourceGid <= 0 || sourceGid + 1 >= loca.length) {
            return false;
        }
        return loca[sourceGid + 1] > loca[sourceGid];
    }

    int addCopy(int sourceGid, int advance) {
        glyphs.add(copyGlyph(sourceGid, advance));
        return glyphs.size() - 1;
    }

    void keepComponentIds() {
        for (int i = 0; i < glyphs.size(); i++) {
            components.putIfAbsent(i, i);
        }
    }

    int addEmpty(int advance) {
        glyphs.add(new Glyph(new byte[0], advance, 0));
        return glyphs.size() - 1;
    }

    int addOutline(GeneralPath path, int advance) {
        glyphs.add(outline(path, advance));
        return glyphs.size() - 1;
    }

    void replaceNotdef(GeneralPath path, int advance) {
        glyphs.set(0, outline(path, advance));
    }

    private Glyph outline(GeneralPath path, int advance) {
        byte[] data = Outline.encode(path);
        glyphBytes += data.length;
        if (data.length < 10) {
            return new Glyph(data, advance, 0);
        }
        ByteBuffer b = ByteBuffer.wrap(data);
        maxContours = Math.max(maxContours, b.getShort(0));
        maxPoints = Math.max(maxPoints, Outline.points(data));
        return new Glyph(data, advance, b.getShort(2));
    }

    record Metrics(int xMin, int yMin, int xMax, int yMax, int maxAdvance, int minLsb, int minRsb, int maxExtent,
            int averageAdvance) {}

    Metrics metrics() {
        int xMin = 0;
        int yMin = 0;
        int xMax = 0;
        int yMax = 0;
        int maxAdvance = 0;
        int minLsb = 0;
        int minRsb = 0;
        int maxExtent = 0;
        long advances = 0;
        boolean any = false;
        for (Glyph g : glyphs) {
            int adv = Math.max(0, Math.min(65_535, g.advance()));
            maxAdvance = Math.max(maxAdvance, adv);
            advances += adv;
            if (g.length() < 10) {
                continue;
            }
            ByteBuffer b = g.view();
            int gxMin = b.getShort(2);
            int gyMin = b.getShort(4);
            int gxMax = b.getShort(6);
            int gyMax = b.getShort(8);
            xMin = any ? Math.min(xMin, gxMin) : gxMin;
            yMin = any ? Math.min(yMin, gyMin) : gyMin;
            xMax = any ? Math.max(xMax, gxMax) : gxMax;
            yMax = any ? Math.max(yMax, gyMax) : gyMax;
            minLsb = any ? Math.min(minLsb, g.lsb()) : g.lsb();
            minRsb = any ? Math.min(minRsb, adv - gxMax) : adv - gxMax;
            maxExtent = Math.max(maxExtent, g.lsb() + (gxMax - gxMin));
            any = true;
        }
        return new Metrics(xMin, yMin, xMax, yMax, maxAdvance, minLsb, minRsb, maxExtent,
                (int) (advances / glyphs.size()));
    }

    private int advanceOf(int gid) {
        return hmtx == null ? 0 : hmtx.getAdvanceWidth(gid);
    }

    private Glyph copyGlyph(int gid, int advance) {
        if (loca == null || gid < 0 || gid + 1 >= loca.length) {
            return new Glyph(new byte[0], advance, 0);
        }
        long start = loca[gid];
        long end = loca[gid + 1];
        if (end <= start || end > glyfLength) {
            return new Glyph(new byte[0], advance, 0);
        }
        int length = (int) (end - start);
        int at = glyfStart + (int) start;
        int lsb = hmtx == null ? 0 : hmtx.getLeftSideBearing(gid);
        if (!wellFormed(ByteBuffer.wrap(glyf, at, length).slice(), advance, lsb)) {
            return new Glyph(new byte[0], advance, 0);
        }
        glyphBytes += length;
        if (length >= 10 && ByteBuffer.wrap(glyf).getShort(at) < 0) {
            pending.add(glyphs.size());
        }
        return new Glyph(glyf, at, length, advance, lsb);
    }

    private void breakComponentCycles() {
        byte[] state = new byte[glyphs.size()];
        for (int gid = 0; gid < glyphs.size(); gid++) {
            if (state[gid] == 0) {
                visit(gid, state, 0);
            }
        }
    }

    private void visit(int gid, byte[] state, int depth) {
        state[gid] = 1;
        for (int child : components(glyphs.get(gid))) {
            if (child >= glyphs.size()) {
                continue;
            }
            if (state[child] == 1 || depth > 64) {
                glyphs.set(gid, new Glyph(new byte[0], glyphs.get(gid).advance(), 0));
                break;
            }
            if (state[child] == 0) {
                visit(child, state, depth + 1);
            }
        }
        state[gid] = 2;
    }

    private static List<Integer> components(Glyph g) {
        List<Integer> out = new ArrayList<>();
        if (g.length() < 10) {
            return out;
        }
        ByteBuffer b = g.view();
        if (b.getShort(0) >= 0) {
            return out;
        }
        int at = 10;
        while (at + 4 <= g.length()) {
            int flags = b.getShort(at) & 0xFFFF;
            out.add(b.getShort(at + 2) & 0xFFFF);
            at += 4 + ((flags & 0x0001) != 0 ? 4 : 2) + ((flags & 0x0008) != 0 ? 2
                    : (flags & 0x0040) != 0 ? 4 : (flags & 0x0080) != 0 ? 8 : 0);
            if ((flags & 0x0020) == 0) {
                break;
            }
        }
        return out;
    }

    private void resolveComponents() {
        for (int p = 0; p < pending.size(); p++) {
            int index = pending.get(p);
            byte[] data = glyphs.get(index).data().clone();
            ByteBuffer b = ByteBuffer.wrap(data);
            int at = 10;
            while (at + 4 <= data.length) {
                int flags = b.getShort(at) & 0xFFFF;
                int sourceGid = b.getShort(at + 2) & 0xFFFF;
                Integer mapped = components.get(sourceGid);
                if (mapped == null) {
                    glyphs.add(copyGlyph(sourceGid, advanceOf(sourceGid)));
                    mapped = glyphs.size() - 1;
                    components.put(sourceGid, mapped);
                }
                b.putShort(at + 2, (short) (int) mapped);
                at += 4 + ((flags & 0x0001) != 0 ? 4 : 2);
                if ((flags & 0x0008) != 0) {
                    at += 2;
                } else if ((flags & 0x0040) != 0) {
                    at += 4;
                } else if ((flags & 0x0080) != 0) {
                    at += 8;
                }
                if ((flags & 0x0020) == 0) {
                    break;
                }
            }
            Glyph g = glyphs.get(index);
            glyphs.set(index, new Glyph(data, g.advance(), g.lsb()));
        }
    }

    byte[] build(String postScriptName, TreeMap<Integer, Integer> cmap, int platformEncoding) throws IOException {
        return build(postScriptName, Cmaps.format4(cmap, platformEncoding));
    }

    byte[] build(String postScriptName, byte[] cmapTable) throws IOException {
        return build(postScriptName, cmapTable, Map.of());
    }

    byte[] build(String postScriptName, byte[] cmapTable, Map<String, byte[]> overrides) throws IOException {
        resolveComponents();
        breakComponentCycles();
        if (glyphs.size() == 1 && glyphs.get(0).length() == 0) {
            addEmpty(0);
        }
        if (glyphs.size() > 65_535) {
            throw new IOException("The font needs more than 65535 glyphs");
        }
        TreeMap<String, byte[]> tables = new TreeMap<>();
        List<Glyph> written = hinting ? glyphs : new ArrayList<>(glyphs.size());
        if (!hinting) {
            for (Glyph g : glyphs) {
                written.add(new Glyph(unhinted(g.data()), g.advance(), g.lsb()));
            }
        }
        long size = 0;
        for (Glyph g : written) {
            size += Sfnt.pad(g.length());
        }
        if (size > Integer.MAX_VALUE - 16) {
            throw new IOException("The glyphs need more than 2 GB");
        }
        byte[] glyfOut = new byte[(int) size];
        ByteBuffer locaOut = ByteBuffer.allocate((glyphs.size() + 1) * 4);
        ByteBuffer hmtxOut = ByteBuffer.allocate(glyphs.size() * 4);
        int maxAdvance = 0;
        int at = 0;
        for (Glyph g : written) {
            PdfFiles.stopIfInterrupted();
            locaOut.putInt(at);
            System.arraycopy(g.buffer(), g.offset(), glyfOut, at, g.length());
            at += Sfnt.pad(g.length());
            int adv = Math.max(0, Math.min(65_535, g.advance()));
            maxAdvance = Math.max(maxAdvance, adv);
            hmtxOut.putShort((short) adv);
            hmtxOut.putShort((short) g.lsb());
        }
        locaOut.putInt(at);
        tables.put("glyf", glyfOut);
        tables.put("loca", locaOut.array());
        tables.put("hmtx", hmtxOut.array());
        tables.put("head", head());
        tables.put("hhea", hhea(maxAdvance));
        tables.put("maxp", maxp());
        tables.put("post", post());
        tables.put("name", name(postScriptName));
        tables.put("cmap", cmapTable);
        if (source != null) {
            for (String tag : hinting ? List.of("cvt ", "fpgm", "prep", "gasp", "OS/2") : List.of("gasp", "OS/2")) {
                TTFTable t = source.getTableMap().get(tag);
                if (t != null) {
                    byte[] bytes = source.getTableBytes(t);
                    tables.put(tag, "cvt ".equals(tag) && bytes.length % 2 != 0
                            ? java.util.Arrays.copyOf(bytes, bytes.length - 1) : bytes);
                }
            }
        }
        tables.putAll(overrides);
        return Sfnt.write(tables);
    }

    static boolean wellFormed(ByteBuffer b) {
        return wellFormed(b, -1, 0);
    }

    private static boolean wellFormed(ByteBuffer b, int advance, int lsb) {
        int length = b.capacity();
        if (length < 10) {
            return length == 0;
        }
        int contours = b.getShort(0);
        if (contours < -1) {
            return false;
        }
        if (contours < 0) {
            int at = 10;
            while (true) {
                if (at + 4 > length) {
                    return false;
                }
                int flags = b.getShort(at) & 0xFFFF;
                at += 4 + ((flags & 0x0001) != 0 ? 4 : 2) + ((flags & 0x0008) != 0 ? 2
                        : (flags & 0x0040) != 0 ? 4 : (flags & 0x0080) != 0 ? 8 : 0);
                if (at > length) {
                    return false;
                }
                if ((flags & 0x0020) == 0) {
                    return true;
                }
            }
        }
        int at = 10 + 2 * contours;
        if (at + 2 > length) {
            return false;
        }
        int points = contours == 0 ? 0 : (b.getShort(at - 2) & 0xFFFF) + 1;
        at += 2 + (b.getShort(at) & 0xFFFF);
        int flagsAt = at;
        int xBytes = 0;
        int yBytes = 0;
        for (int p = 0; p < points;) {
            if (at >= length) {
                return false;
            }
            int flag = b.get(at++) & 0xFF;
            int repeat = 1;
            if ((flag & 0x08) != 0) {
                if (at >= length) {
                    return false;
                }
                repeat += b.get(at++) & 0xFF;
            }
            int n = Math.min(repeat, points - p);
            xBytes += n * ((flag & 0x02) != 0 ? 1 : (flag & 0x10) != 0 ? 0 : 2);
            yBytes += n * ((flag & 0x04) != 0 ? 1 : (flag & 0x20) != 0 ? 0 : 2);
            p += repeat;
        }
        return at + xBytes + yBytes <= length && coordinatesFit(b, flagsAt, at, points, 0x02, 0x10, advance, lsb)
                && coordinatesFit(b, flagsAt, at + xBytes, points, 0x04, 0x20, -1, 0);
    }

    private static boolean coordinatesFit(ByteBuffer data, int flagsAt, int at, int points, int shortFlag,
            int positiveFlag, int advance, int lsb) {
        int coordinate = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int p = 0; p < points;) {
            int flags = data.get(flagsAt++) & 0xFF;
            int repeat = (flags & 0x08) == 0 ? 1 : 1 + (data.get(flagsAt++) & 0xFF);
            repeat = Math.min(repeat, points - p);
            for (int i = 0; i < repeat; i++) {
                int delta = 0;
                if ((flags & shortFlag) != 0) {
                    delta = data.get(at++) & 0xFF;
                    if ((flags & positiveFlag) == 0) {
                        delta = -delta;
                    }
                } else if ((flags & positiveFlag) == 0) {
                    delta = data.getShort(at);
                    at += 2;
                }
                coordinate += delta;
                min = Math.min(min, coordinate);
                max = Math.max(max, coordinate);
                if (coordinate < Short.MIN_VALUE || coordinate > Short.MAX_VALUE) {
                    return false;
                }
            }
            p += repeat;
        }
        int extent = lsb + (points == 0 ? 0 : max - min);
        int right = advance - extent;
        return advance < 0 || extent >= Short.MIN_VALUE && extent <= Short.MAX_VALUE
                && right >= Short.MIN_VALUE && right <= Short.MAX_VALUE;
    }

    static byte[] unhinted(byte[] data) {
        if (data.length < 10) {
            return data;
        }
        ByteBuffer b = ByteBuffer.wrap(data);
        int contours = b.getShort(0);
        if (contours >= 0) {
            int at = 10 + 2 * contours;
            if (at + 2 > data.length) {
                return data;
            }
            int length = b.getShort(at) & 0xFFFF;
            if (length == 0 || at + 2 + length > data.length) {
                return data;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length - length);
            out.write(data, 0, at);
            out.write(0);
            out.write(0);
            out.write(data, at + 2 + length, data.length - at - 2 - length);
            return out.toByteArray();
        }
        int at = 10;
        while (at + 4 <= data.length) {
            int flags = b.getShort(at) & 0xFFFF;
            int next = at + 4 + ((flags & 0x0001) != 0 ? 4 : 2) + ((flags & 0x0008) != 0 ? 2
                    : (flags & 0x0040) != 0 ? 4 : (flags & 0x0080) != 0 ? 8 : 0);
            if (next > data.length) {
                return data;
            }
            if ((flags & 0x0020) == 0) {
                if ((flags & 0x0100) == 0) {
                    return data;
                }
                byte[] out = java.util.Arrays.copyOf(data, next);
                ByteBuffer.wrap(out).putShort(at, (short) (flags & ~0x0100));
                return out;
            }
            at = next;
        }
        return data;
    }

    private byte[] head() throws IOException {
        ByteBuffer b = ByteBuffer.allocate(54);
        if (source != null && source.getTableMap().get("head") != null) {
            byte[] h = source.getTableBytes(source.getTableMap().get("head"));
            b.put(h, 0, Math.min(54, h.length));
        } else {
            b.putInt(0, 0x00010000);
            b.putInt(4, 0x00010000);
            b.putInt(12, 0x5F0F3CF5);
            b.putShort(16, (short) 0x000B);
            b.putShort(18, (short) unitsPerEm);
            b.putShort(36, (short) -unitsPerEm);
            b.putShort(38, (short) -unitsPerEm);
            b.putShort(40, (short) (unitsPerEm * 2));
            b.putShort(42, (short) (unitsPerEm * 2));
            b.putShort(46, (short) 8);
            b.putShort(48, (short) 2);
        }
        b.putInt(8, 0);
        b.putShort(50, (short) 1);
        b.putShort(52, (short) 0);
        return b.array();
    }

    private byte[] hhea(int maxAdvance) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(36);
        if (source != null && source.getTableMap().get("hhea") != null) {
            byte[] h = source.getTableBytes(source.getTableMap().get("hhea"));
            b.put(h, 0, Math.min(36, h.length));
        } else {
            b.putInt(0, 0x00010000);
            b.putShort(4, (short) (unitsPerEm * 8 / 10));
            b.putShort(6, (short) (-unitsPerEm * 2 / 10));
            b.putShort(18, (short) 1);
        }
        b.putShort(10, (short) maxAdvance);
        b.putShort(32, (short) 0);
        b.putShort(34, (short) glyphs.size());
        return b.array();
    }

    private byte[] maxp() throws IOException {
        if (source != null && source.getTableMap().get("maxp") != null) {
            byte[] m = source.getTableBytes(source.getTableMap().get("maxp"));
            if (m.length >= 32 && ByteBuffer.wrap(m).getInt(0) == 0x00010000) {
                m = java.util.Arrays.copyOf(m, 32);
                ByteBuffer.wrap(m).putShort(4, (short) glyphs.size());
                return m;
            }
        }
        int points = maxPoints;
        int contours = maxContours;
        for (Glyph g : glyphs) {
            if (g.length() >= 12 && g.view().getShort(0) > 0) {
                contours = Math.max(contours, g.view().getShort(0));
                points = Math.max(points, Outline.points(g.data()));
            }
        }
        ByteBuffer b = ByteBuffer.allocate(32);
        b.putInt(0, 0x00010000);
        b.putShort(4, (short) glyphs.size());
        b.putShort(6, (short) Math.max(1, points));
        b.putShort(8, (short) Math.max(1, contours));
        b.putShort(14, (short) 2);
        return b.array();
    }

    private byte[] post() throws IOException {
        ByteBuffer b = ByteBuffer.allocate(32);
        if (source != null && source.getTableMap().get("post") != null) {
            byte[] p = source.getTableBytes(source.getTableMap().get("post"));
            b.put(p, 0, Math.min(16, p.length));
        }
        b.putInt(0, 0x00030000);
        return b.array();
    }

    private static byte[] name(String postScriptName) {
        String ps = postScriptName.replaceAll("[^!-~]", "").replaceAll("[\\[\\](){}<>/%]", "");
        if (ps.isEmpty()) {
            ps = "Font";
        }
        String[] values = {ps, "Regular", ps, ps};
        int[] ids = {1, 2, 4, 6};
        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        ByteBuffer head = ByteBuffer.allocate(6 + 12 * ids.length * 2);
        head.putShort((short) 0);
        head.putShort((short) (ids.length * 2));
        head.putShort((short) (6 + 12 * ids.length * 2));
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < ids.length; i++) {
                byte[] s = pass == 0 ? values[i].getBytes(StandardCharsets.ISO_8859_1)
                        : values[i].getBytes(StandardCharsets.UTF_16BE);
                head.putShort((short) (pass == 0 ? 1 : 3));
                head.putShort((short) (pass == 0 ? 0 : 1));
                head.putShort((short) (pass == 0 ? 0 : 0x409));
                head.putShort((short) ids[i]);
                head.putShort((short) s.length);
                head.putShort((short) strings.size());
                strings.writeBytes(s);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(head.array());
        out.writeBytes(strings.toByteArray());
        return out.toByteArray();
    }
}
