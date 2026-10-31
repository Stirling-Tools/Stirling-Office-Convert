package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.apache.fontbox.cmap.CMap;
import org.apache.fontbox.cmap.CMapParser;
import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1CFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.encoding.DictionaryEncoding;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;

import stirling.software.officeconvert.fonts.CodeReader;

final class UnicodeGuess {

    private UnicodeGuess() {}

    static String of(PDFont font, int code, int bytesPerCode) {
        return of(font, code, bytesPerCode, true);
    }

    static String text(PDFont font, int code, int bytesPerCode) {
        return of(font, code, bytesPerCode, !standIn(font) || unreadableMap(font));
    }

    private static boolean unreadableMap(PDFont font) {
        return font.getCOSObject().getDictionaryObject(COSName.TO_UNICODE) instanceof COSStream s
                && MAPS.computeIfAbsent(s, k -> Optional.ofNullable(readable(k))).isEmpty();
    }

    static String extracted(PDFont font, int code) {
        String t;
        if (font instanceof PDType0Font t0) {
            t = collection(t0, code);
        } else {
            t = nameText(font, code);
            if (t == null && code > 0 && code <= 0xFF) {
                t = String.valueOf((char) code);
            }
        }
        return ToUnicodeWriter.legal(t) ? t : null;
    }

    static String nameText(PDFont font, int code) {
        String name = encodingName(font, code);
        if (name == null) {
            return null;
        }
        String t = glyphName(name);
        if (t != null) {
            return t;
        }
        int n = numeric(name, hexNames(font));
        t = n > 0 && n <= Character.MAX_CODE_POINT ? new String(Character.toChars(n)) : null;
        return ToUnicodeWriter.valid(t) ? t : null;
    }

    private static String of(PDFont font, int code, int bytesPerCode, boolean program) {
        String t = program ? of(font, code) : valid(declared(font, code));
        if (t != null || bytesPerCode != 1 || code < 0x20 || code > 0xFF || font instanceof PDType3Font
                || named(font, code)) {
            return t;
        }
        String name = WinAnsiEncoding.INSTANCE.getName(code);
        String u = name == null ? null : GlyphList.getAdobeGlyphList().toUnicode(name);
        return ToUnicodeWriter.valid(u) ? u : null;
    }

    private static final int MAX_MAP_BYTES = 4 << 20;

    private static final Map<COSStream, Optional<CMap>> MAPS = Collections.synchronizedMap(new WeakHashMap<>());

    static String searchable(PDFont font, int code, String text) {
        boolean authored = font.getCOSObject().getDictionaryObject(COSName.TO_UNICODE) instanceof COSStream
                && ToUnicodeWriter.valid(declared(font, code));
        return authored ? text : ToUnicodeWriter.plain(text);
    }

    private static CMap readable(COSStream s) {
        CMap cmap = CodeReader.cmap(s);
        COSBase filter = s.getDictionaryObject(COSName.FILTER);
        boolean flate = COSName.FLATE_DECODE.equals(filter) || filter instanceof COSArray a && a.size() == 1
                && COSName.FLATE_DECODE.equals(a.getObject(0));
        if (cmap != null && cmap.hasUnicodeMappings() || !flate) {
            return cmap;
        }
        byte[] raw;
        try (InputStream in = s.createRawInputStream()) {
            raw = in.readNBytes(MAX_MAP_BYTES);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        byte[] text = inflatedPrefix(raw);
        CMap partial = parsed(text);
        return partial != null && partial.hasUnicodeMappings() ? partial : parsed(raw);
    }

    private static CMap parsed(byte[] text) {
        try {
            CMap cmap = text.length == 0 ? null : new CMapParser().parse(new RandomAccessReadBuffer(text));
            return cmap != null && cmap.hasUnicodeMappings() ? cmap : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] inflatedPrefix(byte[] raw) {
        Inflater inflater = new Inflater();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            inflater.setInput(raw);
            byte[] buffer = new byte[16];
            while (!inflater.finished() && out.size() < MAX_MAP_BYTES) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, n);
            }
        } catch (DataFormatException e) {
            return out.toByteArray();
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    static String declared(PDFont font, int code) {
        COSBase tu = font.getCOSObject().getDictionaryObject(COSName.TO_UNICODE);
        if (tu instanceof COSStream s) {
            CMap cmap = MAPS.computeIfAbsent(s, k -> Optional.ofNullable(readable(k))).orElse(null);
            if (cmap == null) {
                return null;
            }
            String t = cmap.toUnicode(code);
            return t != null || code > 0xFFFFFF ? t : cmap.toUnicode(code, 4);
        }
        if (font instanceof PDType0Font || font instanceof PDType3Font) {
            return null;
        }
        try {
            return font.toUnicode(code);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String of(PDFont font, int code) {
        String t = declared(font, code);
        if (ToUnicodeWriter.valid(t)) {
            return t;
        }
        try {
            return fromProgram(font, code);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String fromProgram(PDFont font, int code) throws IOException {
        TrueTypeFont ttf = null;
        int gid = 0;
        if (font instanceof PDType0Font t0 && t0.getCMapUCS2() != null && (!t0.isEmbedded() || !identity(t0))) {
            String u = t0.getCMapUCS2().toUnicode(CidLookup.cid(t0, code));
            if (ToUnicodeWriter.valid(u) && u.indexOf('�') < 0) {
                return u;
            }
        }
        if (font instanceof PDTrueTypeFont tt) {
            ttf = tt.getTrueTypeFont();
            gid = tt.codeToGID(code);
        } else if (font instanceof PDType0Font t0 && t0.getDescendantFont() instanceof PDCIDFontType2 c2) {
            ttf = c2.getTrueTypeFont();
            gid = c2.codeToGID(code);
        } else if (font instanceof PDType1Font t1) {
            return glyphName(t1.codeToName(code));
        } else if (font instanceof PDType1CFont c) {
            return glyphName(c.codeToName(code));
        } else if (font instanceof PDType3Font t3 && t3.getEncoding() != null) {
            return glyphName(t3.getEncoding().getName(code));
        }
        if (ttf == null || gid <= 0) {
            return null;
        }
        CmapLookup cmap = ttf.getUnicodeCmapLookup(false);
        List<Integer> codes = cmap == null ? null : cmap.getCharCodes(gid);
        int symbol = -1;
        if (codes != null) {
            for (int cp : codes) {
                if (cp > 0 && cp <= Character.MAX_CODE_POINT && !(cp >= 0xE000 && cp <= 0xF8FF)) {
                    return new String(Character.toChars(cp));
                }
                if (cp >= 0xF020 && cp <= 0xF0FF) {
                    symbol = cp & 0xFF;
                }
            }
        }
        if (symbol > 0) {
            String s = ToUnicodeWriter.symbol(null, font.getName(), symbol);
            if (s != null) {
                return s;
            }
            String name = WinAnsiEncoding.INSTANCE.getName(symbol);
            String u = name == null ? null : GlyphList.getAdobeGlyphList().toUnicode(name);
            if (ToUnicodeWriter.valid(u)) {
                return u;
            }
        }
        if (ttf.getPostScript() != null) {
            return glyphName(ttf.getPostScript().getName(gid));
        }
        return null;
    }

    private static String valid(String t) {
        return ToUnicodeWriter.valid(t) ? t : null;
    }

    static boolean standIn(PDFont font) {
        return font instanceof PDType0Font t0 && t0.getDescendantFont() instanceof PDCIDFontType2 && !t0.isEmbedded();
    }

    private static String collection(PDType0Font t0, int code) {
        int cid = CidLookup.cid(t0, code);
        if (t0.getCMapUCS2() != null) {
            String u = t0.getCMapUCS2().toUnicode(cid);
            return u != null && u.indexOf('\uFFFD') < 0 ? u : null;
        }
        String ordering = t0.getDescendantFont() == null || t0.getDescendantFont().getCIDSystemInfo() == null ? null
                : t0.getDescendantFont().getCIDSystemInfo().getOrdering();
        if (ordering != null && !"Identity".equals(ordering) && !"UCS".equals(ordering)
                || t0.getCOSObject().getDictionaryObject(COSName.TO_UNICODE) instanceof COSStream) {
            return null;
        }
        return cid > 0 && cid <= Character.MAX_CODE_POINT ? new String(Character.toChars(cid)) : null;
    }

    private static String encodingName(PDFont font, int code) {
        try {
            if (font instanceof PDType1Font t1) {
                return t1.codeToName(code);
            }
            if (font instanceof PDType1CFont c) {
                return c.codeToName(code);
            }
            if (font instanceof PDType3Font t3) {
                return t3.getEncoding() == null ? null : t3.getEncoding().getName(code);
            }
            if (font instanceof PDSimpleFont simple) {
                return simple.getEncoding() == null ? null : simple.getEncoding().getName(code);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    private static boolean hexNames(PDFont font) {
        for (int code = 0; code < 256; code++) {
            String n = encodingName(font, code);
            if (n != null && n.length() == 3 && Character.isLetter(n.charAt(0)) && hexDigit(n.charAt(1))
                    && hexDigit(n.charAt(2)) && (hexLetter(n.charAt(1)) || hexLetter(n.charAt(2)))) {
                return true;
            }
        }
        return false;
    }

    static int numeric(String name, boolean hex) {
        int dot = name.indexOf('.');
        String s = dot >= 0 ? name.substring(0, dot) : name;
        if (hex) {
            if (s.length() == 3 && Character.isLetter(s.charAt(0))) {
                s = s.substring(1);
            } else if (s.length() != 2) {
                return -1;
            }
            return s.chars().allMatch(UnicodeGuess::hexDigit) ? Integer.parseInt(s, 16) : -1;
        }
        int i = 0;
        while (i < 2 && i < s.length() && Character.isLetter(s.charAt(i))) {
            i++;
        }
        s = s.substring(i);
        return !s.isEmpty() && s.length() <= 6 && s.chars().allMatch(c -> c >= '0' && c <= '9')
                ? Integer.parseInt(s) : -1;
    }

    private static boolean hexDigit(int c) {
        return c >= '0' && c <= '9' || hexLetter(c);
    }

    private static boolean hexLetter(int c) {
        return c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
    }

    private static boolean identity(PDType0Font font) {
        return font.getCOSObject().getDictionaryObject(COSName.ENCODING) instanceof COSName n
                && n.getName().startsWith("Identity-");
    }

    private static boolean named(PDFont font, int code) {
        return font instanceof PDSimpleFont simple && simple.getEncoding() instanceof DictionaryEncoding e
                && e.getDifferences().containsKey(code);
    }

    static String glyphName(String name) {
        if (name == null || ".notdef".equals(name)) {
            return null;
        }
        String u = GlyphList.getAdobeGlyphList().toUnicode(name);
        if (ToUnicodeWriter.valid(u)) {
            return u;
        }
        int dot = name.indexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        if (base.isEmpty() || base.indexOf('_') < 0 && !base.startsWith("uni")) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        for (String part : base.split("_", -1)) {
            String p = component(part);
            if (p == null) {
                return null;
            }
            b.append(p);
        }
        return ToUnicodeWriter.valid(b.toString()) ? b.toString() : null;
    }

    private static String component(String part) {
        if (part.isEmpty()) {
            return null;
        }
        if (part.startsWith("uni") && part.length() > 3 && (part.length() - 3) % 4 == 0) {
            StringBuilder b = new StringBuilder();
            for (int i = 3; i < part.length(); i += 4) {
                int cp = hex(part.substring(i, i + 4));
                if (cp < 0 || cp >= 0xD800 && cp <= 0xDFFF) {
                    return null;
                }
                b.append((char) cp);
            }
            return b.toString();
        }
        if (part.length() >= 5 && part.length() <= 7 && part.charAt(0) == 'u') {
            int cp = hex(part.substring(1));
            if (cp >= 0 && cp <= Character.MAX_CODE_POINT && !(cp >= 0xD800 && cp <= 0xDFFF)) {
                return new String(Character.toChars(cp));
            }
        }
        String u = GlyphList.getAdobeGlyphList().toUnicode(part);
        return u == null || u.isEmpty() ? null : u;
    }

    private static int hex(String s) {
        return !s.isEmpty() && s.chars().allMatch(UnicodeGuess::hexDigit) ? Integer.parseInt(s, 16) : -1;
    }
}
