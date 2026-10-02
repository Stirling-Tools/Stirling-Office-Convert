package stirling.software.officeconvert.topdf.font;

import java.awt.geom.GeneralPath;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.HeaderTable;
import org.apache.fontbox.ttf.HorizontalHeaderTable;
import org.apache.fontbox.ttf.HorizontalMetricsTable;
import org.apache.fontbox.ttf.OS2WindowsMetricsTable;
import org.apache.fontbox.ttf.PostScriptTable;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TTFSubsetter;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.util.Hex;

// The subset Type 0 font PDType0Font.load(document, ttf, true) writes, without the per-glyph width table PDFBox
// builds at load only to replace it when subsetting
final class SubsetFont {

    private static final List<String> TABLES =
            List.of("head", "hhea", "loca", "maxp", "cvt ", "prep", "glyf", "hmtx", "fpgm", "gasp");

    private static final String BASE25 = "BCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static final int MAX_RANGES = 100;

    private final PDDocument document;

    private final TrueTypeFont ttf;

    private final CmapLookup cmap;

    private final PDFontDescriptor descriptor;

    private final COSDictionary dict = new COSDictionary();

    private final COSDictionary cidFont = new COSDictionary();

    private final Set<Integer> codePoints = new LinkedHashSet<>();

    private final Set<Integer> glyphIds = new HashSet<>();

    SubsetFont(PDDocument document, TrueTypeFont ttf) throws IOException {
        this.document = document;
        this.ttf = ttf;
        dict.setItem(COSName.TYPE, COSName.FONT);
        cmap = ttf.getUnicodeCmapLookup();
        descriptor = descriptor(ttf);
        if (!embeddingPermitted(ttf)) {
            throw new IOException("This font does not permit embedding");
        }
        dict.setName(COSName.BASE_FONT, ttf.getName());
        dict.setItem(COSName.SUBTYPE, COSName.TYPE0);
        dict.setName(COSName.BASE_FONT, descriptor.getFontName());
        dict.setItem(COSName.ENCODING, COSName.IDENTITY_H);
        cidFont.setItem(COSName.TYPE, COSName.FONT);
        cidFont.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE2);
        cidFont.setName(COSName.BASE_FONT, descriptor.getFontName());
        COSDictionary info = new COSDictionary();
        info.setString(COSName.REGISTRY, "Adobe");
        info.setString(COSName.ORDERING, "Identity");
        info.setInt(COSName.SUPPLEMENT, 0);
        cidFont.setItem(COSName.CIDSYSTEMINFO, info);
        cidFont.setItem(COSName.FONT_DESC, descriptor.getCOSObject());
        cidFont.setItem(COSName.W, new COSArray());
        cidFont.setItem(COSName.CID_TO_GID_MAP, COSName.IDENTITY);
        COSArray descendants = new COSArray();
        descendants.add(cidFont);
        dict.setItem(COSName.DESCENDANT_FONTS, descendants);
    }

    COSDictionary dictionary() {
        return dict;
    }

    void addCodePoint(int codePoint) {
        codePoints.add(codePoint);
    }

    void addGlyphs(Set<Integer> glyphs) {
        glyphIds.addAll(glyphs);
    }

    void subset() throws IOException {
        OS2WindowsMetricsTable os2 = ttf.getOS2Windows();
        if (os2 != null && (os2.getFsType() & OS2WindowsMetricsTable.FSTYPE_NO_SUBSETTING)
                == OS2WindowsMetricsTable.FSTYPE_NO_SUBSETTING) {
            throw new IOException("This font does not permit subsetting");
        }
        TTFSubsetter subsetter = new TTFSubsetter(ttf, TABLES);
        subsetter.addAll(codePoints);
        subsetter.forceInvisible('​');
        subsetter.forceInvisible('‌');
        subsetter.forceInvisible('⁠');
        subsetter.forceInvisible('﻿');
        if (!glyphIds.isEmpty()) {
            subsetter.addGlyphIds(glyphIds);
        }
        Map<Integer, Integer> gidToCid = subsetter.getGIDMap();
        String tag = tag(gidToCid);
        subsetter.setPrefix(tag);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        subsetter.writeToStream(out);
        byte[] subset = out.toByteArray();
        TreeMap<Integer, Integer> cidToGid = new TreeMap<>();
        gidToCid.forEach((gid, cid) -> cidToGid.put(cid, gid));
        toUnicode(gidToCid);
        PDStream file = new PDStream(document, new ByteArrayInputStream(subset), COSName.FLATE_DECODE);
        TrueTypeFont embedded = new TTFParser().parseEmbedded(new ByteArrayInputStream(subset));
        if (!embeddingPermitted(embedded)) {
            throw new IOException("This font does not permit embedding");
        }
        file.getCOSObject().setLong(COSName.LENGTH1, embedded.getOriginalDataSize());
        descriptor.setFontFile2(file);
        String name = tag + descriptor.getFontName();
        dict.setName(COSName.BASE_FONT, name);
        descriptor.setFontName(name);
        cidFont.setName(COSName.BASE_FONT, name);
        widths(embedded, cidToGid);
        cidToGidMap(cidToGid);
        cidSet(cidToGid);
    }

    private void toUnicode(Map<Integer, Integer> gidToCid) throws IOException {
        Map<Integer, Integer> used = new HashMap<>();
        for (int cp : codePoints) {
            int gid = cmap.getGlyphId(cp);
            if (gid > 0) {
                used.putIfAbsent(gid, cp);
            }
        }
        TreeMap<Integer, String> text = new TreeMap<>();
        boolean surrogates = false;
        int last = Math.min(ttf.getMaximumProfile().getNumGlyphs(), gidToCid.size() - 1);
        for (int gid = 1; gid <= last; gid++) {
            Integer old = gidToCid.get(gid);
            if (old == null) {
                continue;
            }
            int cid = old;
            List<Integer> codes = cmap.getCharCodes(cid);
            Integer cp = used.get(cid);
            if (cp == null && codes == null) {
                continue;
            }
            int codePoint = cp != null ? cp : codes.get(0);
            if (codePoint > 0xFFFF) {
                surrogates = true;
            }
            String s = new String(new int[] {codePoint}, 0, 1);
            if (cid < 0 || cid > 0xFFFF || s.isEmpty()) {
                throw new IllegalArgumentException("CID is not valid");
            }
            text.put(cid, s);
        }
        PDStream stream = new PDStream(document, new ByteArrayInputStream(cmapBytes(text)), COSName.FLATE_DECODE);
        if (surrogates && document.getVersion() < 1.5) {
            document.setVersion(1.5f);
        }
        dict.setItem(COSName.TO_UNICODE, stream);
    }

    private static byte[] cmapBytes(TreeMap<Integer, String> text) throws IOException {
        List<Integer> from = new ArrayList<>();
        List<Integer> to = new ArrayList<>();
        List<String> dst = new ArrayList<>();
        Map.Entry<Integer, String> prev = null;
        for (Map.Entry<Integer, String> next : text.entrySet()) {
            if (prev != null && sequential(prev.getKey(), next.getKey()) && sequential(prev.getValue(), next.getValue())) {
                to.set(to.size() - 1, next.getKey());
            } else {
                from.add(next.getKey());
                to.add(next.getKey());
                dst.add(next.getValue());
            }
            prev = next;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Writer w = new OutputStreamWriter(out, StandardCharsets.US_ASCII);
        w.write("/CIDInit /ProcSet findresource begin\n12 dict begin\n\nbegincmap\n/CIDSystemInfo\n"
                + "<< /Registry (Adobe)\n/Ordering (UCS)\n/Supplement 0\n>> def\n\n"
                + "/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n\n"
                + "1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n\n");
        int batches = (int) Math.ceil(from.size() / (double) MAX_RANGES);
        for (int batch = 0; batch < batches; batch++) {
            int count = batch == batches - 1 ? from.size() - MAX_RANGES * batch : MAX_RANGES;
            w.write(count + " beginbfrange\n");
            for (int j = 0; j < count; j++) {
                int i = batch * MAX_RANGES + j;
                w.write('<');
                w.write(Hex.getChars(from.get(i).shortValue()));
                w.write("> <");
                w.write(Hex.getChars(to.get(i).shortValue()));
                w.write("> <");
                w.write(Hex.getCharsUTF16BE(dst.get(i)));
                w.write(">\n");
            }
            w.write("endbfrange\n\n");
        }
        w.write("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        w.flush();
        return out.toByteArray();
    }

    private static boolean sequential(int prev, int next) {
        return prev + 1 == next && (prev >> 8 & 0xFF) == (next >> 8 & 0xFF) && (prev & 0xFF) < (next & 0xFF);
    }

    private static boolean sequential(String prev, String next) {
        return !prev.isEmpty() && !next.isEmpty() && sequential(prev.codePointAt(0), next.codePointAt(0))
                && prev.codePointCount(0, prev.length()) == 1;
    }

    private void widths(TrueTypeFont embedded, TreeMap<Integer, Integer> cidToGid) throws IOException {
        float scaling = 1000f / embedded.getHeader().getUnitsPerEm();
        HorizontalMetricsTable hmtx = embedded.getHorizontalMetrics();
        COSArray widths = new COSArray();
        COSArray run = new COSArray();
        int prev = Integer.MIN_VALUE;
        for (Map.Entry<Integer, Integer> e : cidToGid.entrySet()) {
            int cid = e.getKey();
            long width = Math.round(hmtx.getAdvanceWidth(e.getValue()) * scaling);
            if (width == 1000) {
                continue;
            }
            if (prev != cid - 1) {
                run = new COSArray();
                widths.add(COSInteger.get(cid));
                widths.add(run);
            }
            run.add(COSInteger.get(width));
            prev = cid;
        }
        cidFont.setItem(COSName.W, widths);
    }

    private void cidToGidMap(TreeMap<Integer, Integer> cidToGid) throws IOException {
        int max = cidToGid.lastKey();
        byte[] map = new byte[max * 2 + 2];
        for (Map.Entry<Integer, Integer> e : cidToGid.entrySet()) {
            int gid = e.getValue();
            map[2 * e.getKey()] = (byte) (gid >> 8 & 0xFF);
            map[2 * e.getKey() + 1] = (byte) (gid & 0xFF);
        }
        cidFont.setItem(COSName.CID_TO_GID_MAP,
                new PDStream(document, new ByteArrayInputStream(map), COSName.FLATE_DECODE));
    }

    private void cidSet(TreeMap<Integer, Integer> cidToGid) throws IOException {
        int max = cidToGid.lastKey();
        byte[] bits = new byte[max / 8 + 1];
        for (int cid = 0; cid <= max; cid++) {
            bits[cid / 8] |= (byte) (1 << 7 - cid % 8);
        }
        descriptor.setCIDSet(new PDStream(document, new ByteArrayInputStream(bits), COSName.FLATE_DECODE));
    }

    private static String tag(Map<Integer, Integer> gidToCid) {
        long num = Math.abs(gidToCid.hashCode());
        StringBuilder sb = new StringBuilder();
        do {
            sb.append(BASE25.charAt((int) (num % 25)));
            num /= 25;
        } while (num != 0 && sb.length() < 6);
        while (sb.length() < 6) {
            sb.insert(0, 'A');
        }
        return sb.append('+').toString();
    }

    private static boolean embeddingPermitted(TrueTypeFont ttf) throws IOException {
        OS2WindowsMetricsTable os2 = ttf.getOS2Windows();
        if (os2 == null) {
            return true;
        }
        int fsType = os2.getFsType();
        return (fsType & 0x000F) != OS2WindowsMetricsTable.FSTYPE_RESTRICTED
                && (fsType & OS2WindowsMetricsTable.FSTYPE_BITMAP_ONLY) != OS2WindowsMetricsTable.FSTYPE_BITMAP_ONLY;
    }

    private static PDFontDescriptor descriptor(TrueTypeFont ttf) throws IOException {
        String name = ttf.getName();
        OS2WindowsMetricsTable os2 = ttf.getOS2Windows();
        if (os2 == null) {
            throw new IOException("os2 table is missing in font " + name);
        }
        PostScriptTable post = ttf.getPostScript();
        if (post == null) {
            throw new IOException("post table is missing in font " + name);
        }
        COSDictionary d = new COSDictionary();
        d.setItem(COSName.TYPE, COSName.FONT_DESC);
        PDFontDescriptor fd = new PDFontDescriptor(d);
        fd.setFontName(name);
        HorizontalHeaderTable hhea = ttf.getHorizontalHeader();
        fd.setFixedPitch(post.getIsFixedPitch() > 0 || hhea.getNumberOfHMetrics() == 1);
        fd.setItalic((os2.getFsSelection() & (1 | 512)) != 0);
        switch (os2.getFamilyClass()) {
            case OS2WindowsMetricsTable.FAMILY_CLASS_CLAREDON_SERIFS, OS2WindowsMetricsTable.FAMILY_CLASS_FREEFORM_SERIFS,
                    OS2WindowsMetricsTable.FAMILY_CLASS_MODERN_SERIFS, OS2WindowsMetricsTable.FAMILY_CLASS_OLDSTYLE_SERIFS,
                    OS2WindowsMetricsTable.FAMILY_CLASS_SLAB_SERIFS -> fd.setSerif(true);
            case OS2WindowsMetricsTable.FAMILY_CLASS_SCRIPTS -> fd.setScript(true);
            default -> {
            }
        }
        fd.setFontWeight(os2.getWeightClass());
        fd.setSymbolic(true);
        fd.setNonSymbolic(false);
        fd.setItalicAngle(post.getItalicAngle());
        HeaderTable head = ttf.getHeader();
        PDRectangle box = new PDRectangle();
        float scaling = 1000f / head.getUnitsPerEm();
        box.setLowerLeftX(head.getXMin() * scaling);
        box.setLowerLeftY(head.getYMin() * scaling);
        box.setUpperRightX(head.getXMax() * scaling);
        box.setUpperRightY(head.getYMax() * scaling);
        fd.setFontBoundingBox(box);
        fd.setAscent(hhea.getAscender() * scaling);
        fd.setDescent(hhea.getDescender() * scaling);
        if (os2.getVersion() >= 1.2) {
            fd.setCapHeight(os2.getCapHeight() * scaling);
            fd.setXHeight(os2.getHeight() * scaling);
        } else {
            GeneralPath h = ttf.getPath("H");
            fd.setCapHeight(h != null ? Math.round(h.getBounds2D().getMaxY()) * scaling
                    : (os2.getTypoAscender() + os2.getTypoDescender()) * scaling);
            GeneralPath x = ttf.getPath("x");
            fd.setXHeight(x != null ? Math.round(x.getBounds2D().getMaxY()) * scaling
                    : os2.getTypoAscender() / 2.0f * scaling);
        }
        fd.setStemV(fd.getFontBoundingBox().getWidth() * .13f);
        return fd;
    }
}
