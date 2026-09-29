package stirling.software.officeconvert.topdf.docx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.io.ActiveContent;
import stirling.software.officeconvert.topdf.io.Relationship;

final class EmbeddedFonts {

    private static final String[] KINDS = {"w:embedRegular", "w:embedBold", "w:embedItalic", "w:embedBoldItalic"};

    private EmbeddedFonts() {}

    static void load(DocxPackage pkg) {
        Relationship table = pkg.first(pkg.main, "fontTable");
        if (table == null) {
            return;
        }
        List<byte[]> fonts = new ArrayList<>();
        try {
            XEl root = pkg.partXml(table);
            if (root == null) {
                return;
            }
            for (XEl font : root.children("w:font")) {
                for (String kind : KINDS) {
                    XEl embed = font.child(kind);
                    if (embed == null || fonts.size() >= FontLibrary.MAX_DOCUMENT_FONTS) {
                        continue;
                    }
                    Relationship r = pkg.relationship(table.part(), embed.attr("r:id"));
                    if (r == null || !ActiveContent.mayFollow(r) || !pkg.zip.exists(r.part())
                            || pkg.zip.size(r.part()) > FontLibrary.MAX_DOCUMENT_FONT_BYTES) {
                        continue;
                    }
                    byte[] data = pkg.zip.read(r);
                    byte[] plain = deobfuscate(data, embed.attr("fontKey"));
                    if (plain != null) {
                        fonts.add(plain);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            pkg.job.warn("The fonts embedded in the document could not be read: " + e.getMessage());
            return;
        }
        if (!fonts.isEmpty()) {
            try {
                pkg.job.addDocumentFonts(fonts);
            } catch (RuntimeException e) {
                pkg.job.warn("The fonts embedded in the document could not be used: " + e.getMessage());
            }
        }
    }

    static byte[] deobfuscate(byte[] data, String fontKey) {
        if (data == null || data.length < 32) {
            return null;
        }
        byte[] out = data.clone();
        if (fontKey == null) {
            return out;
        }
        String hex = fontKey.replace("{", "").replace("}", "").replace("-", "").trim();
        if (hex.length() != 32) {
            return out;
        }
        byte[] key = new byte[16];
        try {
            for (int i = 0; i < 16; i++) {
                key[15 - i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
        } catch (NumberFormatException e) {
            return out;
        }
        for (int i = 0; i < 32; i++) {
            out[i] ^= key[i % 16];
        }
        return out;
    }
}
