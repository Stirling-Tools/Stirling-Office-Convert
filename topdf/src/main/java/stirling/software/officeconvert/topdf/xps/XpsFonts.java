package stirling.software.officeconvert.topdf.xps;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.HorizontalHeaderTable;
import org.apache.fontbox.ttf.OS2WindowsMetricsTable;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.OpenTypeFont;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TTFSubsetter;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.fontbox.util.BoundingBox;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.topdf.font.EmbeddableFont;
import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.io.OfficeZip;

final class XpsFonts {

    static final long MAX_FONT_BYTES = 64L << 20;

    static final long MAX_ALL_FONT_BYTES = 256L << 20;

    static final int MAX_FONTS = 2_000;

    private final PDDocument document;

    private final XpsPackage pkg;

    private final Map<String, Font> fonts = new HashMap<>();

    private final Map<String, String> failed = new LinkedHashMap<>();

    private final FontLibrary library;

    private final List<java.io.Closeable> opened = new ArrayList<>();

    private Font fallback;

    private boolean fallbackTried;

    private long loadedBytes;

    XpsFonts(PDDocument document, XpsPackage pkg, FontLibrary library) {
        this.document = document;
        this.pkg = pkg;
        this.library = library;
    }

    Font fallback() {
        if (fallbackTried) {
            return fallback;
        }
        fallbackTried = true;
        try {
            FontFace face = library.find("Arial", false, false);
            EmbeddableFont font = EmbeddableFont.open(face);
            opened.add(font);
            if (!font.trueTypeOutlines()) {
                face = library.lastResort(false, false);
                font = EmbeddableFont.open(face);
                opened.add(font);
            }
            fallback = new Font("/" + face.family(), font.font(), null, false);
            fallback.substitute = true;
        } catch (IOException | RuntimeException e) {
            fallback = null;
        }
        return fallback;
    }

    void close() {
        for (java.io.Closeable c : opened) {
            try {
                c.close();
            } catch (IOException | RuntimeException e) {
                continue;
            }
        }
        opened.clear();
    }

    final class Font {
        final String part;

        final TrueTypeFont ttf;

        final byte[] data;

        final boolean cff;

        final boolean collection;

        final COSDictionary dict = new COSDictionary();

        final BitSet used = new BitSet();

        final Map<Integer, String> text = new HashMap<>();

        final int glyphs;

        boolean substitute;

        final float unitsPerEm;

        final float ascent;

        final float descent;

        private final int[] advances;

        private final int[] vertical;

        private final CmapLookup cmap;

        private final boolean symbol;

        Font(String part, TrueTypeFont ttf, byte[] data, boolean collection) throws IOException {
            this.part = part;
            this.ttf = ttf;
            this.data = data;
            this.collection = collection;
            this.cff = ttf instanceof OpenTypeFont otf && otf.isPostScript();
            this.glyphs = ttf.getNumberOfGlyphs();
            if (glyphs <= 0 || glyphs > 65535) {
                throw new IOException("the font has " + glyphs + " glyphs");
            }
            int upem = ttf.getUnitsPerEm();
            this.unitsPerEm = upem > 0 && upem <= 16384 ? upem : 1000;
            HorizontalHeaderTable hhea = ttf.getHorizontalHeader();
            OS2WindowsMetricsTable os2 = safeOs2(ttf);
            float a = hhea == null ? 0.8f * unitsPerEm : hhea.getAscender();
            float d = hhea == null ? -0.2f * unitsPerEm : hhea.getDescender();
            if (os2 != null && os2.getWinAscent() > 0) {
                a = os2.getWinAscent();
                d = -os2.getWinDescent();
            }
            this.ascent = a / unitsPerEm;
            this.descent = d / unitsPerEm;
            this.advances = new int[glyphs];
            var hmtx = ttf.getHorizontalMetrics();
            for (int g = 0; g < glyphs; g++) {
                advances[g] = hmtx == null ? (int) unitsPerEm / 2 : hmtx.getAdvanceWidth(g);
            }
            int[] vert = null;
            try {
                var vmtx = ttf.getVerticalMetrics();
                if (vmtx != null) {
                    vert = new int[glyphs];
                    for (int g = 0; g < glyphs; g++) {
                        vert[g] = vmtx.getAdvanceHeight(g);
                    }
                }
            } catch (IOException | RuntimeException e) {
                vert = null;
            }
            this.vertical = vert;
            CmapLookup lookup;
            try {
                lookup = ttf.getUnicodeCmapLookup(false);
            } catch (IOException | RuntimeException e) {
                lookup = null;
            }
            this.cmap = lookup;
            boolean sym = false;
            try {
                var table = ttf.getCmap();
                sym = table != null && table.getSubtable(3, 0) != null && table.getSubtable(3, 1) == null;
            } catch (IOException | RuntimeException e) {
                sym = false;
            }
            this.symbol = sym;
        }

        float advance(int gid) {
            return gid >= 0 && gid < glyphs ? advances[gid] / unitsPerEm : 0;
        }

        float verticalAdvance(int gid) {
            if (vertical != null && gid >= 0 && gid < glyphs && vertical[gid] > 0) {
                return vertical[gid] / unitsPerEm;
            }
            return ascent - descent;
        }

        int glyph(int codePoint) {
            if (cmap == null) {
                return 0;
            }
            int g = cmap.getGlyphId(codePoint);
            if (g == 0 && symbol && codePoint < 0x100) {
                g = cmap.getGlyphId(0xF000 + codePoint);
            }
            return g >= 0 && g < glyphs ? g : 0;
        }

        boolean map(int gid, String unicode) {
            used.set(gid);
            if (unicode == null || unicode.isEmpty()) {
                return !text.containsKey(gid);
            }
            String known = text.putIfAbsent(gid, unicode);
            return known == null || known.equals(unicode);
        }
    }

    Font font(String part) throws IOException {
        String key = part.toLowerCase(Locale.ROOT);
        Font known = fonts.get(key);
        if (known != null) {
            return known;
        }
        if (failed.containsKey(key)) {
            return null;
        }
        try {
            Font f = load(part);
            fonts.put(key, f);
            return f;
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            failed.put(key, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return null;
        }
    }

    Map<String, String> failed() {
        Map<String, String> uris = new LinkedHashMap<>(failed);
        uris.keySet().removeIf(k -> k.startsWith("part:"));
        return uris;
    }

    int count() {
        return fonts.size();
    }

    private Font load(String uri) throws IOException {
        if (fonts.size() >= MAX_FONTS) {
            throw new IOException("the document has more than " + MAX_FONTS + " fonts");
        }
        int hash = uri.indexOf('#');
        String part = hash < 0 ? uri : uri.substring(0, hash);
        int index = 0;
        if (hash >= 0) {
            try {
                index = Integer.parseInt(uri.substring(hash + 1).strip());
            } catch (NumberFormatException e) {
                index = 0;
            }
        }
        String partKey = "part:" + part.toLowerCase(Locale.ROOT);
        if (failed.containsKey(partKey)) {
            throw new IOException(failed.get(partKey));
        }
        if (!pkg.exists(part)) {
            failed.put(partKey, "the font part " + part + " is missing");
            throw new IOException("the font part " + part + " is missing");
        }
        long size = pkg.size(part);
        if (size > MAX_FONT_BYTES || loadedBytes + Math.max(0, size) > MAX_ALL_FONT_BYTES) {
            failed.put(partKey, "the document's fonts are larger than " + (MAX_ALL_FONT_BYTES >> 20) + " MB");
            throw new IOException(failed.get(partKey));
        }
        byte[] data = pkg.read(part, MAX_FONT_BYTES);
        if (loadedBytes + data.length > MAX_ALL_FONT_BYTES) {
            throw new IOException("the document's fonts are larger than " + (MAX_ALL_FONT_BYTES >> 20) + " MB");
        }
        loadedBytes += data.length;
        String type = pkg.contentType(part);
        boolean obfuscated = (type != null && type.toLowerCase(Locale.ROOT).contains("obfuscated"))
                || part.toLowerCase(Locale.ROOT).endsWith(".odttf");
        if (obfuscated || !looksLikeFont(data)) {
            byte[] key = guidKey(part);
            if (key != null && data.length >= 32) {
                byte[] copy = data.clone();
                for (int i = 0; i < 32; i++) {
                    copy[i] ^= key[i % 16];
                }
                if (looksLikeFont(copy) || obfuscated) {
                    data = copy;
                }
            }
        }
        boolean collection = data.length >= 4 && data[0] == 't' && data[1] == 't' && data[2] == 'c' && data[3] == 'f';
        TrueTypeFont ttf;
        if (collection) {
            ttf = fromCollection(data, index);
        } else if (data.length >= 4 && data[0] == 'O' && data[1] == 'T' && data[2] == 'T' && data[3] == 'O') {
            ttf = new OTFParser(true).parse(new RandomAccessReadBuffer(data));
        } else {
            ttf = new TTFParser(true).parse(new RandomAccessReadBuffer(data));
        }
        return new Font(part, ttf, data, collection);
    }

    private static TrueTypeFont fromCollection(byte[] data, int index) throws IOException {
        TrueTypeCollection c = new TrueTypeCollection(new ByteArrayInputStream(data));
        TrueTypeFont[] found = new TrueTypeFont[1];
        int[] at = {0};
        c.processAllFonts(f -> {
            if (at[0]++ == index) {
                found[0] = f;
            }
        });
        if (found[0] == null) {
            throw new IOException("the font collection has no font " + index);
        }
        return found[0];
    }

    static boolean looksLikeFont(byte[] d) {
        if (d.length < 4) {
            return false;
        }
        int tag = (d[0] & 0xFF) << 24 | (d[1] & 0xFF) << 16 | (d[2] & 0xFF) << 8 | d[3] & 0xFF;
        return tag == 0x00010000 || tag == 0x4F54544F || tag == 0x74727565 || tag == 0x74746366;
    }

    static byte[] guidKey(String part) {
        int slash = part.lastIndexOf('/');
        String name = part.substring(slash + 1);
        int dot = name.indexOf('.');
        if (dot >= 0) {
            name = name.substring(0, dot);
        }
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.digit(c, 16) >= 0) {
                hex.append(c);
            } else if (c != '-' && c != '{' && c != '}') {
                return null;
            }
        }
        if (hex.length() != 32) {
            return null;
        }
        byte[] s = new byte[16];
        for (int i = 0; i < 16; i++) {
            s[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        byte[] net = {s[3], s[2], s[1], s[0], s[5], s[4], s[7], s[6], s[8], s[9], s[10], s[11], s[12], s[13], s[14],
            s[15]};
        byte[] key = new byte[16];
        for (int i = 0; i < 16; i++) {
            key[i] = net[15 - i];
        }
        return key;
    }

    void finish() throws IOException {
        for (Font f : fonts.values()) {
            OfficeZip.checkNotInterrupted();
            write(f);
        }
        if (fallback != null) {
            write(fallback);
        }
    }

    private void write(Font f) throws IOException {
        if (f.used.isEmpty()) {
            f.used.set(0);
        }
        f.used.set(0);
        String base = postScriptName(f);
        COSDictionary cid = new COSDictionary();
        COSDictionary descriptor = new COSDictionary();
        descriptor.setItem(COSName.TYPE, COSName.FONT_DESC);
        boolean embedded = false;
        String tag = tag();
        if (!f.cff) {
            try {
                Set<Integer> gids = new HashSet<>();
                for (int g = f.used.nextSetBit(0); g >= 0; g = f.used.nextSetBit(g + 1)) {
                    gids.add(g);
                }
                TTFSubsetter subsetter = new TTFSubsetter(f.ttf, List.of("head", "hhea", "loca", "maxp", "cvt ", "prep",
                        "glyf", "hmtx", "fpgm", "gasp"));
                subsetter.addGlyphIds(gids);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                subsetter.writeToStream(out);
                Map<Integer, Integer> newToOld = subsetter.getGIDMap();
                int max = f.used.length() - 1;
                byte[] map = new byte[2 * (max + 1)];
                for (Map.Entry<Integer, Integer> e : newToOld.entrySet()) {
                    int old = e.getValue();
                    int now = e.getKey();
                    if (old >= 0 && old <= max) {
                        map[2 * old] = (byte) (now >> 8);
                        map[2 * old + 1] = (byte) now;
                    }
                }
                COSStream file = stream(out.toByteArray());
                file.setInt(COSName.LENGTH1, out.size());
                descriptor.setItem(COSName.FONT_FILE2, file);
                cid.setItem(COSName.CID_TO_GID_MAP, stream(map));
                base = tag + "+" + base;
                embedded = true;
            } catch (IOException | RuntimeException e) {
                embedded = false;
            }
            if (!embedded && !f.collection && f.data != null) {
                COSStream file = stream(f.data);
                file.setInt(COSName.LENGTH1, f.data.length);
                descriptor.setItem(COSName.FONT_FILE2, file);
                cid.setItem(COSName.CID_TO_GID_MAP, COSName.IDENTITY);
                embedded = true;
            }
            cid.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE2);
        } else if (f.data != null) {
            COSStream file = stream(f.data);
            file.setItem(COSName.SUBTYPE, COSName.getPDFName("OpenType"));
            descriptor.setItem(COSName.FONT_FILE3, file);
            cid.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE0);
            embedded = true;
        }
        if (!embedded) {
            cid.setItem(COSName.CID_TO_GID_MAP, COSName.IDENTITY);
        }
        descriptor.setName(COSName.FONT_NAME, base);
        descriptor.setInt(COSName.FLAGS, 4);
        float scale = 1000f / f.unitsPerEm;
        COSArray bbox = new COSArray();
        try {
            BoundingBox b = f.ttf.getFontBBox();
            bbox.add(COSInteger.get(Math.round(b.getLowerLeftX() * scale)));
            bbox.add(COSInteger.get(Math.round(b.getLowerLeftY() * scale)));
            bbox.add(COSInteger.get(Math.round(b.getUpperRightX() * scale)));
            bbox.add(COSInteger.get(Math.round(b.getUpperRightY() * scale)));
        } catch (IOException | RuntimeException e) {
            bbox = new COSArray(List.of(COSInteger.get(0), COSInteger.get(-250), COSInteger.get(1000),
                    COSInteger.get(1000)));
        }
        descriptor.setItem(COSName.FONT_BBOX, bbox);
        float italic = 0;
        try {
            italic = f.ttf.getPostScript() == null ? 0 : f.ttf.getPostScript().getItalicAngle();
        } catch (IOException | RuntimeException e) {
            italic = 0;
        }
        descriptor.setItem(COSName.ITALIC_ANGLE, new COSFloat(Float.isFinite(italic) ? italic : 0));
        descriptor.setInt(COSName.ASCENT, Math.round(f.ascent * 1000));
        descriptor.setInt(COSName.DESCENT, Math.round(f.descent * 1000));
        descriptor.setInt(COSName.CAP_HEIGHT, Math.round(f.ascent * 700));
        descriptor.setInt(COSName.STEM_V, 80);
        cid.setItem(COSName.TYPE, COSName.FONT);
        cid.setName(COSName.BASE_FONT, base);
        COSDictionary info = new COSDictionary();
        info.setString(COSName.REGISTRY, "Adobe");
        info.setString(COSName.ORDERING, "Identity");
        info.setInt(COSName.SUPPLEMENT, 0);
        cid.setItem(COSName.CIDSYSTEMINFO, info);
        cid.setItem(COSName.FONT_DESC, descriptor);
        cid.setItem(COSName.W, widths(f));
        cid.setInt(COSName.DW, 0);
        f.dict.setItem(COSName.TYPE, COSName.FONT);
        f.dict.setItem(COSName.SUBTYPE, COSName.TYPE0);
        f.dict.setName(COSName.BASE_FONT, base);
        f.dict.setItem(COSName.ENCODING, COSName.IDENTITY_H);
        f.dict.setItem(COSName.DESCENDANT_FONTS, new COSArray(List.of(cid)));
        if (!f.text.isEmpty()) {
            f.dict.setItem(COSName.TO_UNICODE, stream(toUnicode(f)));
        }
    }

    private static COSArray widths(Font f) {
        COSArray w = new COSArray();
        float scale = 1000f / f.unitsPerEm;
        int g = f.used.nextSetBit(0);
        while (g >= 0) {
            COSArray run = new COSArray();
            int start = g;
            while (g >= 0 && g == start + run.size()) {
                run.add(COSInteger.get(Math.round(f.advances[g] * scale)));
                g = f.used.nextSetBit(g + 1);
            }
            w.add(COSInteger.get(start));
            w.add(run);
        }
        return w;
    }

    private static byte[] toUnicode(Font f) {
        StringBuilder b = new StringBuilder();
        b.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n");
        b.append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n");
        b.append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n");
        b.append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n");
        List<Integer> keys = new ArrayList<>(f.text.keySet());
        keys.sort(null);
        for (int i = 0; i < keys.size(); i += 100) {
            int n = Math.min(100, keys.size() - i);
            b.append(n).append(" beginbfchar\n");
            for (int k = i; k < i + n; k++) {
                int g = keys.get(k);
                b.append('<');
                hex(b, g);
                b.append("> <");
                String t = f.text.get(g);
                for (int c = 0; c < t.length() && c < 64; c++) {
                    hex(b, t.charAt(c));
                }
                b.append(">\n");
            }
            b.append("endbfchar\n");
        }
        b.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        return b.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static void hex(StringBuilder b, int v) {
        String h = Integer.toHexString(v & 0xFFFF).toUpperCase(Locale.ROOT);
        for (int i = h.length(); i < 4; i++) {
            b.append('0');
        }
        b.append(h);
    }

    private COSStream stream(byte[] data) throws IOException {
        COSStream s = document.getDocument().createCOSStream();
        try (OutputStream os = XpsOps.flate(s)) {
            os.write(data);
        }
        return s;
    }

    private static String postScriptName(Font f) {
        String name = null;
        try {
            name = f.ttf.getName();
        } catch (IOException | RuntimeException e) {
            name = null;
        }
        if (name == null || name.isBlank()) {
            String p = f.part.substring(f.part.lastIndexOf('/') + 1);
            int dot = p.lastIndexOf('.');
            name = dot > 0 ? p.substring(0, dot) : p;
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < name.length() && b.length() < 100; i++) {
            char c = name.charAt(i);
            if (c > 32 && c < 127 && "()<>[]{}/%#".indexOf(c) < 0) {
                b.append(c);
            }
        }
        return b.isEmpty() ? "XpsFont" : b.toString();
    }

    private static String tag() {
        StringBuilder b = new StringBuilder();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) {
            b.append((char) ('A' + r.nextInt(26)));
        }
        return b.toString();
    }

    private static OS2WindowsMetricsTable safeOs2(TrueTypeFont ttf) {
        try {
            return ttf.getOS2Windows();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
