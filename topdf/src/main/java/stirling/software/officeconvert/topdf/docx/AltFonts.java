package stirling.software.officeconvert.topdf.docx;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.io.Relationship;

final class AltFonts {

    private AltFonts() {}

    static Map<String, String> read(DocxPackage pkg) {
        Relationship table = pkg.first(pkg.main, "fontTable");
        if (table == null) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        try {
            XEl root = pkg.partXml(table);
            if (root == null) {
                return Map.of();
            }
            for (XEl font : root.children("w:font")) {
                XEl alt = font.child("w:altName");
                String name = font.attr("name");
                String value = alt == null ? null : alt.val();
                if (name != null && value != null && !value.isBlank() && !embedded(font) && western(font)) {
                    out.put(FontLibrary.normalize(name), value.strip());
                }
            }
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
        return out;
    }

    private static boolean western(XEl font) {
        XEl charset = font.child("w:charset");
        return charset == null || "00".equals(charset.val());
    }

    private static boolean embedded(XEl font) {
        for (XEl k : font.kids) {
            if (k.name.startsWith("w:embed")) {
                return true;
            }
        }
        return false;
    }
}
