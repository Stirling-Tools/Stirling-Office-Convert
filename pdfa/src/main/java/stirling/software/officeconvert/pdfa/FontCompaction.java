package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.fontbox.ttf.CmapSubtable;
import org.apache.fontbox.ttf.CmapTable;
import org.apache.fontbox.ttf.HorizontalMetricsTable;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import stirling.software.officeconvert.extract.PdfFiles;

final class FontCompaction {

    static final double MAX_RATIO = 0.8;

    static final int MIN_SAVING = 1024;

    private final PDDocument doc;

    private final FontUsage usage;

    private final PdfALevel level;

    private final Map<COSStream, List<COSDictionary>> users = new IdentityHashMap<>();

    private final Set<COSDictionary> form = Collections.newSetFromMap(new IdentityHashMap<>());

    FontCompaction(PDDocument doc, FontUsage usage, PdfALevel level) {
        this.doc = doc;
        this.usage = usage;
        this.level = level;
    }

    void run() throws IOException {
        for (Map.Entry<COSStream, List<COSDictionary>> e : users.entrySet()) {
            PdfFiles.stopIfInterrupted();
            if (Collections.disjoint(form, e.getValue())) {
                try {
                    compact(e.getKey(), e.getValue());
                } catch (IOException | RuntimeException ex) {
                    PdfFiles.stopIfInterrupted();
                }
            }
        }
    }

    void collect(COSBase b) {
        if (!(b instanceof COSDictionary d) || b instanceof COSStream) {
            return;
        }
        if (d.containsKey(COSName.DA) || d.containsKey(COSName.DR)) {
            COSDictionary dr = ContentGraph.dict(d.getDictionaryObject(COSName.DR));
            COSDictionary fonts = dr == null ? null : ContentGraph.dict(dr.getDictionaryObject(COSName.FONT));
            if (fonts != null) {
                for (COSBase f : fonts.getValues()) {
                    COSDictionary fd = ContentGraph.dict(f);
                    if (fd != null) {
                        form.add(fd);
                    }
                }
            }
        }
        COSStream program = program(d);
        if (program != null) {
            users.computeIfAbsent(program, k -> new ArrayList<>()).add(d);
        }
    }

    private static COSStream program(COSDictionary font) {
        COSName sub = font.getCOSName(COSName.SUBTYPE);
        COSDictionary descriptorOwner = font;
        if (COSName.TYPE0.equals(sub)) {
            COSArray kids = ContentGraph.array(font.getDictionaryObject(COSName.DESCENDANT_FONTS));
            descriptorOwner = kids == null || kids.size() != 1 ? null : ContentGraph.dict(kids.getObject(0));
        } else if (!COSName.TRUE_TYPE.equals(sub) && !COSName.TYPE1.equals(sub)) {
            return null;
        }
        COSDictionary fd = descriptorOwner == null ? null
                : ContentGraph.dict(descriptorOwner.getDictionaryObject(COSName.FONT_DESC));
        if (fd == null) {
            return null;
        }
        for (COSName key : new COSName[] {COSName.FONT_FILE2, COSName.FONT_FILE, COSName.FONT_FILE3}) {
            if (fd.getDictionaryObject(key) instanceof COSStream s) {
                return s;
            }
        }
        return null;
    }

    private void compact(COSStream program, List<COSDictionary> fonts) throws IOException {
        List<PDFont> loaded = new ArrayList<>();
        for (COSDictionary d : fonts) {
            TreeSet<Integer> codes = usage.codes().get(d);
            if (codes == null || codes.isEmpty()) {
                return;
            }
            PDFont f = usage.unchanged(d);
            if (f == null) {
                return;
            }
            loaded.add(f);
        }
        COSName key = programKey(loaded.get(0), program);
        if (!COSName.FONT_FILE2.equals(key)) {
            outlines(program, loaded, key);
            return;
        }
        boolean cid = loaded.get(0) instanceof PDType0Font;
        for (PDFont f : loaded) {
            if (cid != f instanceof PDType0Font || !cid && !(f instanceof PDTrueTypeFont)
                    || cid && !(((PDType0Font) f).getDescendantFont() instanceof PDCIDFontType2) || vertical(f)) {
                return;
            }
        }
        TrueTypeFont ttf = trueType(loaded.get(0));
        if (ttf == null || ttf.getTableMap().get("glyf") == null || ttf.getIndexToLocation() == null) {
            return;
        }
        TreeSet<Integer> keep = new TreeSet<>();
        Map<Integer, Integer> unicode = new TreeMap<>();
        Map<Integer, Integer> macRoman = new TreeMap<>();
        Map<COSDictionary, TreeMap<Integer, Integer>> cidMaps = new LinkedHashMap<>();
        for (PDFont f : loaded) {
            TreeSet<Integer> codes = usage.codes().get(f.getCOSObject());
            if (cid) {
                PDCIDFontType2 c = (PDCIDFontType2) ((PDType0Font) f).getDescendantFont();
                TreeMap<Integer, Integer> map = cidMaps.computeIfAbsent(c.getCOSObject(), k -> new TreeMap<>());
                for (int code : codes) {
                    int gid = c.codeToGID(code);
                    map.put(c.codeToCID(code), gid);
                    keep.add(gid);
                }
            } else if (!SimpleGlyphs.collect((PDTrueTypeFont) f, ttf, codes, keep, unicode, macRoman)) {
                return;
            }
        }
        keep.remove(0);
        int glyphs = ttf.getNumberOfGlyphs();
        keep.removeIf(g -> g < 0 || g >= glyphs);
        TrueTypeWriter w = new TrueTypeWriter(ttf);
        HorizontalMetricsTable hmtx = ttf.getHorizontalMetrics();
        Map<Integer, Integer> remap = new TreeMap<>();
        remap.put(0, 0);
        for (int gid : keep) {
            remap.put(gid, w.addCopy(gid, hmtx == null ? 0 : hmtx.getAdvanceWidth(gid)));
        }
        String name = ttf.getName() == null ? "Font" : ttf.getName();
        byte[] font = w.build(FontRebuild.cleanName(name), cmap(ttf, remap, unicode, macRoman));
        byte[] packed = PdfWriter.deflate(font);
        long before = program.getLength();
        if (packed.length > before * MAX_RATIO || before - packed.length < MIN_SAVING) {
            return;
        }
        COSStream file = doc.getDocument().createCOSStream();
        try (OutputStream out = file.createRawOutputStream()) {
            out.write(packed);
        }
        file.setItem(COSName.FILTER, COSName.FLATE_DECODE);
        file.setInt(COSName.LENGTH1, font.length);
        String tag = tag(remap);
        for (PDFont f : loaded) {
            COSDictionary fd = descriptor(f);
            fd.setItem(COSName.FONT_FILE2, file);
            retag(f, fd, tag);
        }
        for (Map.Entry<COSDictionary, TreeMap<Integer, Integer>> e : cidMaps.entrySet()) {
            cidToGid(e.getKey(), e.getValue(), remap);
        }
    }

    private void outlines(COSStream program, List<PDFont> loaded, COSName key) throws IOException {
        for (PDFont f : loaded) {
            if (programKey(f, program) != key) {
                return;
            }
        }
        COSStream file = COSName.FONT_FILE.equals(key)
                ? OutlineCompaction.type1(doc, program, loaded, usage.codes())
                : OutlineCompaction.cff(doc, program, loaded, usage.codes());
        if (file == null) {
            return;
        }
        String tag = tag(Map.of(usage.codes().get(loaded.get(0).getCOSObject()).hashCode(),
                String.valueOf(loaded.get(0).getName()).hashCode()));
        for (PDFont f : loaded) {
            COSDictionary fd = descriptor(f);
            fd.setItem(key, file);
            retag(f, fd, tag);
        }
    }

    private static COSName programKey(PDFont f, COSStream program) {
        COSDictionary fd = descriptor(f);
        if (fd == null) {
            return null;
        }
        for (COSName key : new COSName[] {COSName.FONT_FILE2, COSName.FONT_FILE, COSName.FONT_FILE3}) {
            if (fd.getDictionaryObject(key) == program) {
                return key;
            }
        }
        return null;
    }

    private static boolean vertical(PDFont f) {
        return f instanceof PDType0Font t0 && t0.getCOSObject().getDictionaryObject(COSName.ENCODING) instanceof COSName n
                && n.getName().endsWith("-V");
    }

    private static TrueTypeFont trueType(PDFont f) {
        if (f instanceof PDTrueTypeFont t) {
            return t.getTrueTypeFont();
        }
        return ((PDCIDFontType2) ((PDType0Font) f).getDescendantFont()).getTrueTypeFont();
    }

    private static COSDictionary descriptor(PDFont f) {
        COSDictionary owner = f instanceof PDType0Font t0 ? t0.getDescendantFont().getCOSObject() : f.getCOSObject();
        return ContentGraph.dict(owner.getDictionaryObject(COSName.FONT_DESC));
    }

    private static byte[] cmap(TrueTypeFont ttf, Map<Integer, Integer> remap, Map<Integer, Integer> unicode,
            Map<Integer, Integer> macRoman) throws IOException {
        CmapTable table = ttf.getCmap();
        List<Cmaps.Subtable> subs = new ArrayList<>();
        if (table != null) {
            for (CmapSubtable s : table.getCmaps()) {
                TreeMap<Integer, Integer> map = new TreeMap<>();
                for (Map.Entry<Integer, Integer> e : remap.entrySet()) {
                    List<Integer> codes = e.getKey() == 0 ? null : s.getCharCodes(e.getKey());
                    if (codes != null) {
                        for (int code : codes) {
                            map.put(code, e.getValue());
                        }
                    }
                }
                Map<Integer, Integer> named = s.getPlatformId() == 3 && s.getPlatformEncodingId() == 1 ? unicode
                        : s.getPlatformId() == 1 && s.getPlatformEncodingId() == 0 ? macRoman : Map.of();
                for (Map.Entry<Integer, Integer> e : named.entrySet()) {
                    Integer gid = remap.get(e.getValue());
                    if (gid != null && gid > 0) {
                        map.putIfAbsent(e.getKey(), gid);
                    }
                }
                subs.add(new Cmaps.Subtable(s.getPlatformId(), s.getPlatformEncodingId(), map));
            }
        }
        subs.sort(Comparator.comparingInt(Cmaps.Subtable::platform).thenComparingInt(Cmaps.Subtable::encoding));
        if (subs.isEmpty()) {
            subs.add(new Cmaps.Subtable(3, 1, new TreeMap<>()));
        }
        return Cmaps.table(subs);
    }

    private void cidToGid(COSDictionary cidFont, TreeMap<Integer, Integer> cids, Map<Integer, Integer> remap)
            throws IOException {
        int max = cids.isEmpty() ? 0 : cids.lastKey();
        byte[] map = new byte[(max + 1) * 2];
        byte[] bits = new byte[max / 8 + 1];
        bits[0] |= (byte) 0x80;
        for (Map.Entry<Integer, Integer> e : cids.entrySet()) {
            int gid = remap.getOrDefault(e.getValue(), 0);
            map[2 * e.getKey()] = (byte) (gid >> 8);
            map[2 * e.getKey() + 1] = (byte) gid;
            if (gid > 0) {
                bits[e.getKey() / 8] |= (byte) (1 << (7 - e.getKey() % 8));
            }
        }
        COSStream s = doc.getDocument().createCOSStream();
        try (OutputStream out = s.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(map);
        }
        cidFont.setItem(COSName.CID_TO_GID_MAP, s);
        COSDictionary fd = ContentGraph.dict(cidFont.getDictionaryObject(COSName.FONT_DESC));
        if (level.part() == 1 && fd != null) {
            COSStream set = doc.getDocument().createCOSStream();
            try (OutputStream out = set.createOutputStream(COSName.FLATE_DECODE)) {
                out.write(bits);
            }
            fd.setItem(COSName.CID_SET, set);
        }
    }

    private static void retag(PDFont f, COSDictionary fd, String tag) {
        String name = fd.getNameAsString(COSName.FONT_NAME);
        if (name == null || name.matches("[A-Z]{6}\\+.*")) {
            return;
        }
        fd.setName(COSName.FONT_NAME, tag + name);
        if (f instanceof PDType0Font t0) {
            COSDictionary c = t0.getDescendantFont().getCOSObject();
            String base = c.getNameAsString(COSName.BASE_FONT);
            if (base != null) {
                c.setName(COSName.BASE_FONT, tag + base);
            }
        }
        String base = f.getCOSObject().getNameAsString(COSName.BASE_FONT);
        if (base != null) {
            f.getCOSObject().setName(COSName.BASE_FONT, tag + base);
        }
    }

    private static String tag(Map<Integer, Integer> remap) {
        long h = remap.keySet().hashCode() & 0x7FFFFFFFL;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            b.append((char) ('A' + h % 26));
            h /= 26;
        }
        return b.append('+').toString();
    }
}
