package stirling.software.officeconvert.pdfa;

import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDCIDFont;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;

final class FontRebuild {

    enum Mode {
        OWN,
        SUBSTITUTE
    }

    private final PDDocument doc;

    private final PdfALevel level;

    private FontRebuild(PDDocument doc, PdfALevel level) {
        this.doc = doc;
        this.level = level;
    }

    static String rebuild(PDDocument doc, PDFont font, Set<Integer> codes, Mode mode, GlyphSource substitute,
            PdfALevel level, int bytesPerCode) throws IOException {
        return new FontRebuild(doc, level).run(font, codes, mode, substitute, bytesPerCode);
    }

    private String run(PDFont font, Set<Integer> codes, Mode mode, GlyphSource substitute, int bytesPerCode)
            throws IOException {
        String original = font.getName();
        boolean type0 = font instanceof PDType0Font;
        PDCIDFont cid = type0 ? ((PDType0Font) font).getDescendantFont() : null;
        TrueTypeFont own = mode == Mode.OWN ? ownTrueType(font) : null;
        TrueTypeWriter w;
        if (mode == Mode.SUBSTITUTE) {
            w = new TrueTypeWriter(substitute.font());
        } else if (own != null) {
            w = new TrueTypeWriter(own);
        } else {
            w = new TrueTypeWriter(1000);
        }
        double scale = w.unitsPerEm() / 1000.0;
        TreeMap<Integer, Integer> slots = new TreeMap<>();
        TreeMap<Integer, String> unicode = new TreeMap<>();
        TreeMap<Integer, Float> widths = new TreeMap<>();
        int missing = 0;
        for (int code : codes) {
            int key = type0 ? cid.codeToCID(code) : code;
            String text = text(font, code);
            if (ToUnicodeWriter.valid(text)) {
                unicode.put(code, text);
            } else if (level.unicode()) {
                unicode.put(code, ToUnicodeWriter.privateUse(code));
            }
            if (slots.containsKey(key)) {
                continue;
            }
            float width = font.getWidth(code);
            widths.put(code, width);
            int advance = (int) Math.round(width * scale);
            int slot;
            if (mode == Mode.SUBSTITUTE) {
                String name = font instanceof PDSimpleFont s && s.getEncoding() != null ? s.getEncoding().getName(code) : null;
                int gid = substitute.glyph(code, name, text);
                if (gid > 0) {
                    slot = w.addCopy(gid, advance);
                } else {
                    GeneralPath p = substitute.fallback(text, w.unitsPerEm());
                    slot = p != null ? w.addOutline(p, advance) : w.addEmpty(advance);
                    if (p == null && text != null && !text.isBlank()) {
                        missing++;
                    }
                }
            } else if (own != null) {
                int gid = ownGid(font, code);
                slot = w.addCopy(gid > 0 && gid < own.getNumberOfGlyphs() ? gid : 0, advance);
            } else {
                GeneralPath p = font instanceof PDVectorFont v ? v.getNormalizedPath(code) : null;
                slot = p == null ? w.addEmpty(advance) : w.addOutline(p, advance);
            }
            slots.put(key, slot);
        }
        String base = cleanName(type0 ? cid.getBaseFont() : font.getName());
        String name = tag(slots, base) + base;
        TreeMap<Integer, Integer> cmap = new TreeMap<>();
        if (!type0) {
            for (Map.Entry<Integer, Integer> e : slots.entrySet()) {
                cmap.put(0xF000 + e.getKey(), e.getValue());
            }
        }
        byte[] program = w.build(base, cmap, 0);
        COSStream file = doc.getDocument().createCOSStream();
        try (OutputStream out = file.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(program);
        }
        file.setInt(COSName.LENGTH1, program.length);
        COSDictionary fd = descriptor(font.getFontDescriptor(), name, file, w, mode == Mode.SUBSTITUTE ? substitute.font() : own);
        if (type0) {
            updateCid((PDType0Font) font, cid, name, fd, slots);
        } else {
            updateSimple(font, name, fd, codes, widths);
        }
        if (!unicode.isEmpty()) {
            font.getCOSObject().setItem(COSName.TO_UNICODE, ToUnicodeWriter.write(doc, unicode, type0 ? bytesPerCode : 1));
        }
        String note = mode == Mode.SUBSTITUTE ? cleanName(original) + " as " + substitute.description()
                : null;
        if (missing > 0 && note != null) {
            note += ", " + missing + (missing == 1 ? " character" : " characters") + " not in it";
        }
        return note;
    }

    private static String text(PDFont font, int code) {
        try {
            return font.toUnicode(code);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static TrueTypeFont ownTrueType(PDFont font) {
        TrueTypeFont t = null;
        if (font instanceof PDTrueTypeFont tt) {
            t = tt.getTrueTypeFont();
        } else if (font instanceof PDType0Font t0 && t0.getDescendantFont() instanceof PDCIDFontType2 c2) {
            t = c2.getTrueTypeFont();
        }
        try {
            return t != null && t.getTableMap().get("glyf") != null && t.getIndexToLocation() != null ? t : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static int ownGid(PDFont font, int code) throws IOException {
        if (font instanceof PDTrueTypeFont tt) {
            return tt.codeToGID(code);
        }
        return ((PDType0Font) font).codeToGID(code);
    }

    private void updateSimple(PDFont font, String name, COSDictionary fd, Set<Integer> codes, Map<Integer, Float> widths)
            throws IOException {
        COSDictionary dict = font.getCOSObject();
        int first = Integer.MAX_VALUE;
        int last = Integer.MIN_VALUE;
        for (int c : codes) {
            first = Math.min(first, c);
            last = Math.max(last, c);
        }
        COSArray w = new COSArray();
        for (int c = first; c <= last; c++) {
            Float v = widths.get(c);
            float width = v != null ? v : font.getWidth(c);
            w.add(width == Math.rint(width) ? COSInteger.get((long) width) : new COSFloat(width));
        }
        dict.setItem(COSName.SUBTYPE, COSName.TRUE_TYPE);
        dict.setName(COSName.BASE_FONT, name);
        dict.removeItem(COSName.ENCODING);
        dict.setInt(COSName.FIRST_CHAR, first);
        dict.setInt(COSName.LAST_CHAR, last);
        dict.setItem(COSName.WIDTHS, w);
        dict.setItem(COSName.FONT_DESC, fd);
    }

    private void updateCid(PDType0Font font, PDCIDFont cid, String name, COSDictionary fd, TreeMap<Integer, Integer> slots)
            throws IOException {
        COSDictionary d = cid.getCOSObject();
        d.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE2);
        d.setName(COSName.BASE_FONT, name);
        d.setItem(COSName.FONT_DESC, fd);
        int max = slots.isEmpty() ? 0 : slots.lastKey();
        byte[] map = new byte[(max + 1) * 2];
        for (Map.Entry<Integer, Integer> e : slots.entrySet()) {
            map[2 * e.getKey()] = (byte) (e.getValue() >> 8);
            map[2 * e.getKey() + 1] = (byte) (int) e.getValue();
        }
        COSStream s = doc.getDocument().createCOSStream();
        try (OutputStream out = s.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(map);
        }
        d.setItem(COSName.CID_TO_GID_MAP, s);
        font.getCOSObject().setName(COSName.BASE_FONT, name);
        if (level.part() == 1) {
            byte[] bits = new byte[max / 8 + 1];
            bits[0] |= (byte) 0x80;
            for (int c : slots.keySet()) {
                bits[c / 8] |= (byte) (1 << (7 - c % 8));
            }
            COSStream set = doc.getDocument().createCOSStream();
            try (OutputStream out = set.createOutputStream(COSName.FLATE_DECODE)) {
                out.write(bits);
            }
            fd.setItem(COSName.CID_SET, set);
        }
    }

    private COSDictionary descriptor(PDFontDescriptor old, String name, COSStream file, TrueTypeWriter w,
            TrueTypeFont metrics) throws IOException {
        COSDictionary fd = new COSDictionary();
        if (old != null) {
            for (Map.Entry<COSName, ?> e : old.getCOSObject().entrySet()) {
                fd.setItem(e.getKey(), old.getCOSObject().getItem(e.getKey()));
            }
        }
        for (COSName k : new COSName[] {COSName.FONT_FILE, COSName.FONT_FILE2, COSName.FONT_FILE3, COSName.CHAR_SET,
                COSName.CID_SET}) {
            fd.removeItem(k);
        }
        fd.setItem(COSName.TYPE, COSName.FONT_DESC);
        fd.setName(COSName.FONT_NAME, name);
        fd.setItem(COSName.FONT_FILE2, file);
        int flags = old == null ? 0 : old.getFlags();
        flags = (flags | 4) & ~32;
        fd.setInt(COSName.FLAGS, flags);
        double k = metrics == null ? 1 : 1000.0 / metrics.getUnitsPerEm();
        if (fd.getDictionaryObject(COSName.FONT_BBOX) == null) {
            COSArray box = new COSArray();
            if (metrics != null && metrics.getHeader() != null) {
                box.add(COSInteger.get(Math.round(metrics.getHeader().getXMin() * k)));
                box.add(COSInteger.get(Math.round(metrics.getHeader().getYMin() * k)));
                box.add(COSInteger.get(Math.round(metrics.getHeader().getXMax() * k)));
                box.add(COSInteger.get(Math.round(metrics.getHeader().getYMax() * k)));
            } else {
                for (int v : new int[] {-200, -300, 1200, 1000}) {
                    box.add(COSInteger.get(v));
                }
            }
            fd.setItem(COSName.FONT_BBOX, box);
        }
        if (fd.getDictionaryObject(COSName.ITALIC_ANGLE) == null) {
            fd.setInt(COSName.ITALIC_ANGLE, 0);
        }
        boolean hhea = metrics != null && metrics.getHorizontalHeader() != null;
        if (fd.getDictionaryObject(COSName.ASCENT) == null) {
            fd.setInt(COSName.ASCENT, hhea ? (int) Math.round(metrics.getHorizontalHeader().getAscender() * k) : 800);
        }
        if (fd.getDictionaryObject(COSName.DESCENT) == null) {
            fd.setInt(COSName.DESCENT, hhea ? (int) Math.round(metrics.getHorizontalHeader().getDescender() * k) : -200);
        }
        if (fd.getDictionaryObject(COSName.CAP_HEIGHT) == null) {
            fd.setInt(COSName.CAP_HEIGHT, 700);
        }
        if (fd.getDictionaryObject(COSName.STEM_V) == null) {
            fd.setInt(COSName.STEM_V, 80);
        }
        return fd;
    }

    static String cleanName(String name) {
        String n = name == null ? "Font" : name.replaceFirst("^[A-Z]{6}\\+", "");
        n = n.replaceAll("[^!-~]", "").replaceAll("[\\[\\](){}<>/%#]", "");
        if (n.length() > 100) {
            n = n.substring(0, 100);
        }
        return n.isEmpty() ? "Font" : n;
    }

    private static String tag(TreeMap<Integer, Integer> slots, String base) {
        long h = (slots.hashCode() * 31L + base.hashCode()) & 0x7FFFFFFFL;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            b.append((char) ('A' + h % 26));
            h /= 26;
        }
        return b.append('+').toString();
    }
}
