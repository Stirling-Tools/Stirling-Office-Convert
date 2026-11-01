package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

import org.apache.fontbox.cmap.CMap;
import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.font.PDCIDFont;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDCIDSystemInfo;
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
        String t = of(font, code);
        if (t != null || bytesPerCode != 1 || code < 0x20 || code > 0xFF || font instanceof PDType3Font
                || named(font, code)) {
            return t;
        }
        String name = WinAnsiEncoding.INSTANCE.getName(code);
        String u = name == null ? null : GlyphList.getAdobeGlyphList().toUnicode(name);
        return ToUnicodeWriter.valid(u) ? u : null;
    }

    private static final Map<COSStream, Optional<CMap>> MAPS = Collections.synchronizedMap(new WeakHashMap<>());

    static String declared(PDFont font, int code) {
        COSBase tu = font.getCOSObject().getDictionaryObject(COSName.TO_UNICODE);
        if (tu instanceof COSStream s) {
            CMap cmap = MAPS.computeIfAbsent(s, k -> Optional.ofNullable(CodeReader.cmap(k))).orElse(null);
            return cmap == null ? null : cmap.toUnicode(code);
        }
        if (font instanceof PDType0Font t0) {
            return adobeCjk(t0) || t0.getCMapUCS2() != null ? cjk(t0, code) : null;
        }
        if (font instanceof PDType3Font) {
            return null;
        }
        try {
            return font.toUnicode(code);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final Set<String> CJK_ORDERINGS = Set.of("GB1", "CNS1", "Japan1", "Korea1");

    private static final Pattern UNICODE_CMAP = Pattern.compile("Uni[A-Za-z0-9]+-(UCS2|UTF16)(-HW)?-[HV]");

    /** Unicode CMaps carry the text in the code itself; the CID round trip would lose some of it. */
    private static String cjk(PDType0Font font, int code) {
        String cmap = font.getCOSObject().getNameAsString(COSName.ENCODING);
        String t;
        if (cmap != null && UNICODE_CMAP.matcher(cmap).matches()) {
            t = (code & 0xFFFF0000) == 0 ? String.valueOf((char) code)
                    : new String(new char[] {(char) (code >>> 16), (char) code});
            if (privateUse(t) && font.getCMapUCS2() != null) {
                String collected = font.getCMapUCS2().toUnicode(CidLookup.cid(font, code));
                t = collected != null && !privateUse(collected) ? collected : t;
            }
        } else {
            try {
                t = font.getCMapUCS2() != null ? font.getCMapUCS2().toUnicode(CidLookup.cid(font, code))
                        : font.toUnicode(code);
            } catch (RuntimeException e) {
                return null;
            }
        }
        return t == null || t.indexOf('\uFFFD') >= 0 || !pairedSurrogates(t) ? null : t;
    }

    private static boolean privateUse(String t) {
        int cp = t.codePointAt(0);
        return t.length() == Character.charCount(cp) && Character.getType(cp) == Character.PRIVATE_USE;
    }

    private static boolean pairedSurrogates(String t) {
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < t.length() && Character.isLowSurrogate(t.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    /** An Adobe CJK collection fixes each CID's meaning, so its UCS2 map is the spec's own answer. */
    private static boolean adobeCjk(PDType0Font font) {
        PDCIDFont cid = font.getDescendantFont();
        PDCIDSystemInfo info = cid == null ? null : cid.getCIDSystemInfo();
        return info != null && "Adobe".equals(info.getRegistry()) && CJK_ORDERINGS.contains(info.getOrdering());
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
        if (font instanceof PDTrueTypeFont tt) {
            ttf = tt.getTrueTypeFont();
            gid = tt.codeToGID(code);
        } else if (font instanceof PDType0Font t0 && t0.getDescendantFont() instanceof PDCIDFontType2 c2) {
            if (!c2.isEmbedded()) {
                // An unembedded CID font's glyph ids index PDFBox's stand-in, not the author's font
                return null;
            }
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

    private static boolean named(PDFont font, int code) {
        return font instanceof PDSimpleFont simple && simple.getEncoding() instanceof DictionaryEncoding e
                && e.getDifferences().containsKey(code);
    }

    private static String glyphName(String name) {
        if (name == null || ".notdef".equals(name)) {
            return null;
        }
        String u = GlyphList.getAdobeGlyphList().toUnicode(name);
        return ToUnicodeWriter.valid(u) ? u : null;
    }
}
