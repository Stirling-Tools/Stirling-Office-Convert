package stirling.software.officeconvert.topdf.io;

import java.awt.Font;
import java.awt.Graphics2D;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.poi.common.usermodel.fonts.FontCharset;
import org.apache.poi.common.usermodel.fonts.FontInfo;
import org.apache.poi.sl.draw.DrawFontManagerDefault;

import stirling.software.officeconvert.topdf.font.FontLibrary;

final class MetafileFonts extends DrawFontManagerDefault {

    static final MetafileFonts INSTANCE = new MetafileFonts();

    private static final int MAX_KNOWN = 512;

    private final Map<String, String> known = new ConcurrentHashMap<>();

    private MetafileFonts() {}

    @Override
    public FontInfo getMappedFont(Graphics2D graphics, FontInfo fontInfo) {
        String name = fontInfo == null ? null : fontInfo.getTypeface();
        if (name == null || name.isBlank() || fontInfo.getCharset() == FontCharset.SYMBOL
                || knownSymbolFonts.contains(name)) {
            return fontInfo;
        }
        String key = name.toLowerCase(Locale.ROOT);
        String mapped = known.get(key);
        if (mapped == null) {
            mapped = standIn(name);
            if (known.size() < MAX_KNOWN) {
                known.put(key, mapped);
            }
        }
        String family = mapped;
        return family.equals(name) ? fontInfo : () -> family;
    }

    private static String standIn(String name) {
        if (installed(name)) {
            return name;
        }
        for (String candidate : FontLibrary.standIns(name)) {
            if (installed(candidate)) {
                return candidate;
            }
        }
        return name;
    }

    private static boolean installed(String family) {
        try {
            return family.equalsIgnoreCase(new Font(family, Font.PLAIN, 10).getFamily(Locale.ROOT));
        } catch (RuntimeException | InternalError e) {
            return false;
        }
    }
}
