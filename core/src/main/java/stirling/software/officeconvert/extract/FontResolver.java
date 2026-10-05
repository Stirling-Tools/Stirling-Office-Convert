package stirling.software.officeconvert.extract;

import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import org.apache.fontbox.ttf.NamingTable;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.font.PDCIDFont;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
public final class FontResolver {

    private final Map<COSDictionary, FontInfo> cache = new WeakHashMap<>();
    private Boolean namelessSerif;

    public FontInfo resolve(PDFont font) {
        if (font == null) {
            return FontInfo.DEFAULT;
        }
        FontInfo info = cache.get(font.getCOSObject());
        if (info == null) {
            info = compute(font);
            cache.put(font.getCOSObject(), info);
        }
        return info;
    }

    private FontInfo compute(PDFont font) {
        String ps = FontNames.stripSubset(font.getName());
        if (ps == null) {
            ps = "";
        }
        PDFontDescriptor fd = font.getFontDescriptor();
        String style = FontNames.stylePart(ps);
        String lowerPs = ps.toLowerCase(Locale.ROOT);

        String candidate = null;
        if (fd != null && fd.getFontFamily() != null && isSane(fd.getFontFamily())) {
            candidate = fd.getFontFamily();
        }
        if (candidate == null) {
            candidate = embeddedFamily(font);
        }
        String family = FontNames.family(candidate, ps);
        boolean unnamed = family == null || family.isBlank() || font instanceof PDType3Font && ps.isBlank();
        if (unnamed) {
            family = fd != null && fd.isFixedPitch() ? "Courier New" : "Times New Roman";
        }

        boolean bold = FontNames.styleIsBold(style) || texBold(lowerPs);
        boolean italic = FontNames.styleIsItalic(style) || texItalic(lowerPs);
        if (fd != null) {
            bold |= fd.isForceBold() || fd.getFontWeight() >= 600;
            italic |= !texSymbols(ps, family) && (fd.isItalic() || Math.abs(fd.getItalicAngle()) > 4);
        }
        String lowerFamily = family.toLowerCase(Locale.ROOT);
        if (lowerFamily.contains("black") || lowerFamily.contains("bold")) {
            bold = true;
        }
        boolean ocrFont = lowerPs.startsWith("glyphless");
        boolean mono = !ocrFont && (FontNames.looksMono(family) || fd != null && fd.isFixedPitch());
        boolean serif = !mono && (FontNames.looksSerif(family) || fd != null && fd.isSerif());
        boolean symbolic = FontNames.isSymbolFamily(family);
        boolean substituted = FontNames.isTexFont(ps) && !"Cambria Math".equals(family);
        if (!symbolic && (unnamed || !FontNames.isOfficeFont(family))) {
            Boolean shapes = mono || italic || ocrFont || FontNames.isTexFont(ps) ? null : GlyphShapes.serifs(font);
            if (unnamed) {
                shapes = shapes != null ? (namelessSerif = shapes) : namelessSerif;
            }
            serif = shapes != null ? shapes : serif;
            family = mono ? "Courier New" : serif ? "Times New Roman" : "Arial";
            substituted = true;
        }
        boolean exact = !substituted && !symbolic && font.isEmbedded() && FontNames.isOfficeFont(family);
        return new FontInfo(family, bold, italic, serif, mono, symbolic, ps, substituted, FontNames.isSmallCaps(ps),
                FontNames.isIconFont(ps), exact);
    }

    private static boolean texSymbols(String ps, String family) {
        return FontNames.isTexFont(ps) && "Cambria Math".equals(family);
    }

    private static boolean texBold(String ps) {
        return ps.matches("(cm|ec|sf|tc)(bx|b|ssbx|ssdc)[a-z]*[0-9]*") || ps.startsWith("cmbx");
    }

    private static boolean texItalic(String ps) {
        return ps.matches("(cm|ec|sf|tc)(ti|sl|mi|bxti|bxsl|itt|ssi|u)[0-9]*")
                || ps.matches("cmmib?[0-9]*");
    }

    private static boolean isSane(String name) {
        if (name.length() < 2 || name.length() > 64) {
            return false;
        }
        int letters = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
            } else if (c != ' ' && c != '-' && !Character.isDigit(c)) {
                return false;
            }
        }
        return letters >= 2;
    }

    private static String embeddedFamily(PDFont font) {
        try {
            TrueTypeFont ttf = null;
            if (font instanceof PDTrueTypeFont tt) {
                ttf = tt.getTrueTypeFont();
            } else if (font instanceof PDType0Font t0) {
                PDCIDFont cid = t0.getDescendantFont();
                if (cid instanceof PDCIDFontType2 cid2) {
                    ttf = cid2.getTrueTypeFont();
                }
            }
            if (ttf == null) {
                return null;
            }
            NamingTable naming = ttf.getNaming();
            if (naming == null) {
                return null;
            }
            String family = naming.getFontFamily();
            return family != null && isSane(family) ? family : null;
        } catch (Exception e) {
            return null;
        }
    }
}
