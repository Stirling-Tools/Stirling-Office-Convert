package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.fontbox.ttf.CmapSubtable;
import org.apache.fontbox.ttf.CmapTable;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.encoding.Encoding;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
import org.apache.pdfbox.pdmodel.font.encoding.MacOSRomanEncoding;

final class SimpleGlyphs {

    private static final int[] SYMBOL_RANGES = {0, 0xF000, 0xF100, 0xF200};

    private static final Map<String, Integer> MAC_ROMAN = new HashMap<>();

    static {
        MacOSRomanEncoding.INSTANCE.getCodeToNameMap().forEach((code, name) -> MAC_ROMAN.putIfAbsent(name, code));
    }

    private SimpleGlyphs() {}

    static boolean collect(PDTrueTypeFont font, TrueTypeFont ttf, Set<Integer> codes, Set<Integer> keep)
            throws IOException {
        CmapTable table = ttf.getCmap();
        CmapSubtable[] subs = table == null ? new CmapSubtable[0] : table.getCmaps();
        Encoding enc = font.getEncoding();
        for (int code : codes) {
            Set<Integer> keys = new HashSet<>();
            for (int base : SYMBOL_RANGES) {
                keys.add(base + code);
            }
            String name = enc == null ? null : enc.getName(code);
            if (name != null && !".notdef".equals(name)) {
                String u = GlyphList.getAdobeGlyphList().toUnicode(name);
                if (u != null && !u.isEmpty()) {
                    keys.add(u.codePointAt(0));
                }
                Integer mac = MAC_ROMAN.get(name);
                if (mac != null) {
                    keys.add(mac);
                }
            }
            Set<Integer> found = new HashSet<>();
            for (CmapSubtable s : subs) {
                for (int k : keys) {
                    int gid = s.getGlyphId(k);
                    if (gid > 0) {
                        found.add(gid);
                    }
                }
            }
            int used = font.codeToGID(code);
            if (used > 0 && !found.contains(used)) {
                return false;
            }
            keep.addAll(found);
        }
        return true;
    }
}
