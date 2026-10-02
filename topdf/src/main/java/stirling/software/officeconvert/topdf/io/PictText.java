package stirling.software.officeconvert.topdf.io;

import java.awt.Font;
import java.util.Locale;

final class PictText {

    private static final String MAC_ROMAN_HIGH = "ÄÅÇÉÑÖÜáàâäãåçéèêëíìîïñóòôöõúùûü†°¢£§•¶ß®©™´¨≠ÆØ∞±≤≥¥µ∂∑∏π∫ªºΩæø"
            + "¿¡¬√ƒ≈∆«»…\u00A0ÀÃÕŒœ\u2013\u2014“”‘’÷◊ÿŸ⁄€‹›ﬁﬂ‡·‚„‰ÂÊÁËÈÍÎÏÌÓÔÒÚÛÙıˆ˜¯˘˙˚¸˝˛ˇ";

    private PictText() {}

    static String decode(byte[] b) {
        StringBuilder s = new StringBuilder(b.length);
        for (byte x : b) {
            int c = x & 0xFF;
            if (c >= 0x80) {
                s.append(MAC_ROMAN_HIGH.charAt(c - 0x80));
            } else if (c >= 0x20 || c == '\t') {
                s.append((char) c);
            }
        }
        return s.toString();
    }

    static String family(int id, String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.contains("courier") || n.contains("monaco") || n.contains("mono") || id == 4 || id == 22) {
            return Font.MONOSPACED;
        }
        if (n.contains("times") || n.contains("new york") || n.contains("palatino") || n.contains("serif")
                && !n.contains("sans") || id == 2 || id == 20) {
            return Font.SERIF;
        }
        return Font.SANS_SERIF;
    }

    static int style(int face) {
        return ((face & 1) != 0 ? Font.BOLD : 0) | ((face & 2) != 0 ? Font.ITALIC : 0);
    }
}
