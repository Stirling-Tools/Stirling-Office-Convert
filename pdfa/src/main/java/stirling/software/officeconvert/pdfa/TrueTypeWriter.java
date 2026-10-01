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

final class TrueTypeWriter {

    private record Glyph(byte[] data, int advance, int lsb) {}

    private final TrueTypeFont source;

    private final int unitsPerEm;

    private final byte[] glyf;

    private final long[] loca;

    private final HorizontalMetricsTable hmtx;

    private final List<Glyph> glyphs = new ArrayList<>();

    private final Map<Integer, Integer> components = new HashMap<>();

    private final List<Integer> pending = new ArrayList<>();

    private int maxPoints;

    private int maxContours;

    TrueTypeWriter(TrueTypeFont source) throws IOException {
        this.source = source;
        this.unitsPerEm = source.getUnitsPerEm();
        TTFTable g = source.getTableMap().get("glyf");
        if (g == null || source.getIndexToLocation() == null) {
            throw new IOException("The font has no TrueType outlines");
        }
        this.glyf = source.getTableBytes(g);
        this.loca = source.getIndexToLocation().getOffsets();
        this.hmtx = source.getHorizontalMetrics();
        glyphs.add(copyGlyph(0, advanceOf(0)));
    }

    TrueTypeWriter(int unitsPerEm) {
        this.source = null;
        this.unitsPerEm = unitsPerEm;
        this.glyf = null;
        this.loca = null;
        this.hmtx = null;
        glyphs.add(new Glyph(new byte[0], unitsPerEm / 2, 0));
    }

    int unitsPerEm() {
        return unitsPerEm;
    }

    int size() {
        return glyphs.size();
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

    int addEmpty(int advance) {
        glyphs.add(new Glyph(new byte[0], advance, 0));
        return glyphs.size() - 1;
    }

    int addOutline(GeneralPath path, int advance) {
        byte[] data = Outline.encode(path);
        if (data.length >= 10) {
            ByteBuffer b = ByteBuffer.wrap(data);
            maxContours = Math.max(maxContours, b.getShort(0));
            maxPoints = Math.max(maxPoints, Outline.points(data));
            glyphs.add(new Glyph(data, advance, b.getShort(2)));
        } else {
            glyphs.add(new Glyph(data, advance, 0));
        }
        return glyphs.size() - 1;
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
        if (end <= start || end > glyf.length) {
            return new Glyph(new byte[0], advance, 0);
        }
        byte[] data = java.util.Arrays.copyOfRange(glyf, (int) start, (int) end);
        int lsb = hmtx == null ? 0 : hmtx.getLeftSideBearing(gid);
        if (data.length >= 10 && ByteBuffer.wrap(data).getShort(0) < 0) {
            pending.add(glyphs.size());
        }
        return new Glyph(data, advance, lsb);
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
        resolveComponents();
        if (glyphs.size() > 65_535) {
            throw new IOException("The font needs more than 65535 glyphs");
        }
        TreeMap<String, byte[]> tables = new TreeMap<>();
        ByteArrayOutputStream glyfOut = new ByteArrayOutputStream();
        ByteBuffer locaOut = ByteBuffer.allocate((glyphs.size() + 1) * 4);
        ByteBuffer hmtxOut = ByteBuffer.allocate(glyphs.size() * 4);
        int maxAdvance = 0;
        for (Glyph g : glyphs) {
            locaOut.putInt(glyfOut.size());
            glyfOut.write(g.data());
            while (glyfOut.size() % 4 != 0) {
                glyfOut.write(0);
            }
            int adv = Math.max(0, Math.min(65_535, g.advance()));
            maxAdvance = Math.max(maxAdvance, adv);
            hmtxOut.putShort((short) adv);
            hmtxOut.putShort((short) g.lsb());
        }
        locaOut.putInt(glyfOut.size());
        tables.put("glyf", glyfOut.toByteArray());
        tables.put("loca", locaOut.array());
        tables.put("hmtx", hmtxOut.array());
        tables.put("head", head());
        tables.put("hhea", hhea(maxAdvance));
        tables.put("maxp", maxp());
        tables.put("post", post());
        tables.put("name", name(postScriptName));
        tables.put("cmap", cmapTable);
        if (source != null) {
            for (String tag : List.of("cvt ", "fpgm", "prep", "gasp", "OS/2")) {
                TTFTable t = source.getTableMap().get(tag);
                if (t != null) {
                    tables.put(tag, source.getTableBytes(t));
                }
            }
        }
        return Sfnt.write(tables);
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
            byte[] m = source.getTableBytes(source.getTableMap().get("maxp")).clone();
            ByteBuffer.wrap(m).putShort(4, (short) glyphs.size());
            return m;
        }
        ByteBuffer b = ByteBuffer.allocate(32);
        b.putInt(0, 0x00010000);
        b.putShort(4, (short) glyphs.size());
        b.putShort(6, (short) Math.max(1, maxPoints));
        b.putShort(8, (short) Math.max(1, maxContours));
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
