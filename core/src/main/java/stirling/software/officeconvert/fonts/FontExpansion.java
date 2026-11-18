package stirling.software.officeconvert.fonts;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.IntUnaryOperator;

import org.apache.fontbox.cff.CFFCIDFont;
import org.apache.fontbox.cff.CFFFont;
import org.apache.fontbox.cff.CFFType1Font;
import org.apache.fontbox.encoding.StandardEncoding;
import org.apache.fontbox.type1.Type1Font;

import stirling.software.officeconvert.extract.PdfFiles;

public final class FontExpansion {

    private static final String NOTDEF = ".notdef";

    private FontExpansion() {}

    public static void checkType1(Type1Font font) throws IOException {
        Map<String, byte[]> dict = font.getCharStringsDict();
        List<String> glyphs = new ArrayList<>();
        if (dict.containsKey(NOTDEF)) {
            glyphs.add(NOTDEF);
        }
        for (String name : new TreeSet<>(dict.keySet())) {
            if (!NOTDEF.equals(name)) {
                glyphs.add(name);
            }
        }
        checkType1(font, dict, glyphs);
    }

    static void checkType1(Type1Font font, Map<String, byte[]> dict, List<String> glyphs) throws IOException {
        Map<String, Integer> index = new HashMap<>();
        CharStringExpansion.Result[] results = new CharStringExpansion.Result[glyphs.size()];
        long tokens = 0;
        for (int gid = 0; gid < glyphs.size(); gid++) {
            PdfFiles.stopIfInterrupted();
            byte[] cs = dict.get(glyphs.get(gid));
            results[gid] = CharStringExpansion.type1(cs == null ? new byte[] {14} : cs, font.getSubrsArray());
            tokens += results[gid].tokens();
            if (tokens > CharStringExpansion.MAX_FONT_TOKENS) {
                throw new IOException("The font's charstrings expand beyond " + CharStringExpansion.MAX_FONT_TOKENS
                        + " tokens");
            }
            index.put(glyphs.get(gid), gid);
        }
        int notdef = index.getOrDefault(NOTDEF, -1);
        CharStringExpansion.check(results,
                code -> index.getOrDefault(StandardEncoding.INSTANCE.getName(code), notdef));
    }

    public static void checkCff(CFFFont font) throws IOException {
        List<byte[]> global = font.getGlobalSubrIndex();
        byte[][] globalSubrs = global == null ? new byte[0][] : global.toArray(new byte[0][]);
        List<byte[]> charStrings = font.getCharStringBytes();
        CharStringExpansion.Result[] results = new CharStringExpansion.Result[charStrings.size()];
        long tokens = 0;
        for (int gid = 0; gid < results.length; gid++) {
            PdfFiles.stopIfInterrupted();
            results[gid] = CharStringExpansion.type2(charStrings.get(gid), globalSubrs, localSubrs(font, gid));
            tokens += results[gid].tokens();
            if (tokens > CharStringExpansion.MAX_FONT_TOKENS) {
                throw new IOException("The font's charstrings expand beyond " + CharStringExpansion.MAX_FONT_TOKENS
                        + " tokens");
            }
        }
        CharStringExpansion.check(results, components(font));
    }

    private static IntUnaryOperator components(CFFFont font) {
        if (!(font instanceof CFFType1Font)) {
            return code -> 0;
        }
        return code -> {
            String name = StandardEncoding.INSTANCE.getName(code);
            int sid = name == null ? -1 : font.getCharset().getSID(name);
            return sid < 0 ? 0 : Math.max(0, font.getCharset().getGIDForSID(sid));
        };
    }

    private static byte[][] localSubrs(CFFFont font, int gid) {
        Map<String, Object> priv = null;
        if (font instanceof CFFCIDFont cid) {
            List<Map<String, Object>> dicts = cid.getPrivDicts();
            int fd = cid.getFdSelect() == null ? 0 : cid.getFdSelect().getFDIndex(gid);
            priv = fd >= 0 && fd < dicts.size() ? dicts.get(fd) : null;
        } else if (font instanceof CFFType1Font t1) {
            priv = t1.getPrivateDict();
        }
        return priv != null && priv.get("Subrs") instanceof byte[][] local ? local : null;
    }
}
