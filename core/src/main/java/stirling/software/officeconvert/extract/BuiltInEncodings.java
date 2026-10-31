package stirling.software.officeconvert.extract;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.fontbox.ttf.WGL4Names;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
import org.apache.pdfbox.pdmodel.font.encoding.SymbolEncoding;

final class BuiltInEncodings {

    private static final String RESOURCE = "builtin-encodings.txt";

    private static final int CODES = 256;

    private static final String[] SYMBOL_NAMES = {"standardsyml", "standardsymbolsps", "standardsymbolsl"};

    private static final char SYMBOL_PLANE = '\uF000';

    private static final int SPACE_GID = 3;

    private static final float BOLD_WEIGHT = 600;

    private static final Set<String> CORE_FONT_FAMILIES = Set.of("Arial", "Arial Narrow", "Times New Roman", "Courier New",
            "Verdana", "Tahoma", "Georgia");

    private static final String[] CORE_FONT_ORDER = coreFontOrder();

    private static final String[] PRIVATE_USE = privateUse();

    private static volatile Map<String, String[]> byFont;

    private BuiltInEncodings() {}

    static String[] of(PDFont font) {
        if (font.isEmbedded() || font.getName() == null || font.getCOSObject().containsKey(COSName.TO_UNICODE)) {
            return null;
        }
        String name = FontNames.stripSubset(font.getName());
        if (font instanceof PDType0Font composite) {
            return inCoreFontOrder(composite, name) ? CORE_FONT_ORDER : null;
        }
        if (!(font instanceof PDType1Font) && !(font instanceof PDTrueTypeFont)) {
            return null;
        }
        String letters = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (letters.startsWith("wingdings") || letters.startsWith("webdings")) {
            return withDifferences(font, PRIVATE_USE);
        }
        if (font instanceof PDTrueTypeFont) {
            PDFontDescriptor fd = font.getFontDescriptor();
            return "Symbol".equals(FontNames.family(null, name)) && fd != null && fd.isSymbolic()
                    ? withDifferences(font, fonts().get(SYMBOL_NAMES[0])) : null;
        }
        COSDictionary dict = font.getCOSObject();
        String[] builtin = fonts().get(name.toLowerCase(Locale.ROOT));
        if (builtin == null) {
            return null;
        }
        COSBase encoding = dict.getDictionaryObject(COSName.ENCODING);
        if (encoding == null) {
            return builtin;
        }
        if (!(encoding instanceof COSDictionary differences) || differences.containsKey(COSName.BASE_ENCODING)) {
            return null;
        }
        String[] out = builtin.clone();
        if (differences.getDictionaryObject(COSName.DIFFERENCES) instanceof COSArray array) {
            int code = -1;
            for (int i = 0; i < array.size(); i++) {
                COSBase item = array.getObject(i);
                if (item instanceof COSNumber n) {
                    code = n.intValue();
                } else if (item instanceof COSName && code >= 0 && code < CODES) {
                    out[code++] = null;
                }
            }
        }
        return out;
    }

    private static String[] withDifferences(PDFont font, String[] fallback) {
        if (!(font.getCOSObject().getDictionaryObject(COSName.ENCODING) instanceof COSDictionary encoding)
                || !(encoding.getDictionaryObject(COSName.DIFFERENCES) instanceof COSArray array)) {
            return fallback;
        }
        String[] out = fallback.clone();
        int code = -1;
        for (int i = 0; i < array.size(); i++) {
            COSBase item = array.getObject(i);
            if (item instanceof COSNumber n) {
                code = n.intValue();
            } else if (item instanceof COSName glyph && code >= 0 && code < CODES) {
                String text = glyphText(glyph.getName());
                if (text != null) {
                    out[code] = text;
                }
                code++;
            }
        }
        return out;
    }

    private static String glyphText(String glyph) {
        String text = GlyphList.getAdobeGlyphList().toUnicode(glyph);
        return text != null ? text : GlyphList.getZapfDingbats().toUnicode(glyph);
    }

    private static boolean inCoreFontOrder(PDType0Font font, String name) {
        if (!(font.getDescendantFont() instanceof PDCIDFontType2 cid) || font.getCMap() == null
                || font.getCMap().getName() == null || !font.getCMap().getName().startsWith("Identity")) {
            return false;
        }
        COSBase map = cid.getCOSObject().getDictionaryObject(COSName.CID_TO_GID_MAP);
        if (map != null && !COSName.IDENTITY.equals(map)) {
            return false;
        }
        String ordering = cid.getCIDSystemInfo() == null ? null : cid.getCIDSystemInfo().getOrdering();
        if (ordering != null && !"Identity".equals(ordering)) {
            return false;
        }
        String family = FontNames.family(null, name.replace("-Identity-H", ""));
        return CORE_FONT_FAMILIES.contains(family) && !("Arial Narrow".equals(family) && bold(cid, name));
    }

    private static boolean bold(PDCIDFontType2 cid, String name) {
        PDFontDescriptor fd = cid.getFontDescriptor();
        return name.toLowerCase(Locale.ROOT).contains("bold")
                || fd != null && (fd.isForceBold() || fd.getFontWeight() >= BOLD_WEIGHT);
    }

    private static String[] coreFontOrder() {
        List<String> names = new ArrayList<>(WGL4Names.NUMBER_OF_MAC_GLYPHS);
        for (int gid = 0; gid < WGL4Names.NUMBER_OF_MAC_GLYPHS; gid++) {
            String name = WGL4Names.getGlyphName(gid);
            if (!"nonbreakingspace".equals(name) && !"apple".equals(name)) {
                names.add("currency".equals(name) ? "Euro" : name);
            }
        }
        String[] vector = new String[names.size()];
        for (int gid = SPACE_GID; gid < vector.length; gid++) {
            vector[gid] = GlyphList.getAdobeGlyphList().toUnicode(names.get(gid));
        }
        return vector;
    }

    private static String[] privateUse() {
        String[] vector = new String[CODES];
        for (int code = ' '; code < CODES; code++) {
            vector[code] = String.valueOf((char) (SYMBOL_PLANE + code));
        }
        return vector;
    }

    private static Map<String, String[]> fonts() {
        Map<String, String[]> map = byFont;
        if (map == null) {
            synchronized (BuiltInEncodings.class) {
                map = byFont;
                if (map == null) {
                    map = load();
                    byFont = map;
                }
            }
        }
        return map;
    }

    private static Map<String, String[]> load() {
        Map<String, String[]> map = new HashMap<>();
        try (InputStream in = BuiltInEncodings.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return Map.of();
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
            for (String names = reader.readLine(); names != null; names = reader.readLine()) {
                String codes = reader.readLine();
                if (codes == null) {
                    break;
                }
                String[] vector = parse(codes);
                for (String name : names.split(" ")) {
                    map.put(name, vector);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String[] symbol = symbol();
        for (String name : SYMBOL_NAMES) {
            map.put(name, symbol);
        }
        return Map.copyOf(map);
    }

    private static String[] symbol() {
        String[] vector = new String[CODES];
        for (Map.Entry<Integer, String> e : SymbolEncoding.INSTANCE.getCodeToNameMap().entrySet()) {
            if (e.getKey() >= 0 && e.getKey() < CODES) {
                vector[e.getKey()] = GlyphList.getAdobeGlyphList().toUnicode(e.getValue());
            }
        }
        return vector;
    }

    private static String[] parse(String line) {
        String[] vector = new String[CODES];
        for (String entry : line.split(" ")) {
            int eq = entry.indexOf('=');
            StringBuilder text = new StringBuilder();
            for (String cp : entry.substring(eq + 1).split("\\.")) {
                text.appendCodePoint(Integer.parseInt(cp, 16));
            }
            vector[Integer.parseInt(entry.substring(0, eq), 16)] = text.toString();
        }
        return vector;
    }
}
