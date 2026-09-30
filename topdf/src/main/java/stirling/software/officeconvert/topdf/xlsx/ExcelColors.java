package stirling.software.officeconvert.topdf.xlsx;

import java.awt.Color;

import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.xssf.model.ThemesTable;
import org.apache.poi.xssf.usermodel.DefaultIndexedColorMap;
import org.apache.poi.xssf.usermodel.IndexedColorMap;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTColor;

import stirling.software.officeconvert.topdf.dml.DmlColors;

final class ExcelColors {

    static final int SYSTEM_FOREGROUND = 64;

    static final int SYSTEM_BACKGROUND = 65;

    private final ThemesTable theme;

    private final IndexedColorMap indexed;

    ExcelColors(ThemesTable theme, IndexedColorMap indexed) {
        this.theme = theme;
        this.indexed = indexed == null ? new DefaultIndexedColorMap() : indexed;
    }

    Color resolve(XSSFColor color, Color automatic) {
        return color == null ? automatic : resolve(color.getCTColor(), automatic);
    }

    Color resolve(CTColor c, Color automatic) {
        if (c == null) {
            return automatic;
        }
        Color base = c.isSetTheme() ? themeColor((int) c.getTheme()) : null;
        if (base == null && c.isSetRgb() && c.getRgb() != null) {
            byte[] v = c.getRgb();
            base = v.length >= 4 ? rgb(v[1], v[2], v[3]) : v.length == 3 ? rgb(v[0], v[1], v[2]) : null;
        } else if (base == null && c.isSetIndexed()) {
            base = indexedColor((int) c.getIndexed(), automatic);
        } else if (base == null && c.isSetAuto() && c.getAuto()) {
            return automatic;
        }
        if (base == null) {
            return automatic;
        }
        double tint = c.isSetTint() ? c.getTint() : 0;
        return tint == 0 ? base : tint(base, tint);
    }

    Color themeColor(int index) {
        if (theme == null) {
            return DEFAULT_THEME[Math.floorMod(index, DEFAULT_THEME.length)];
        }
        try {
            XSSFColor x = theme.getThemeColor(index);
            if (x != null && x.getRGB() != null) {
                byte[] v = x.getRGB();
                return rgb(v[0], v[1], v[2]);
            }
        } catch (RuntimeException ignored) {
            return index >= 0 && index < DEFAULT_THEME.length ? DEFAULT_THEME[index] : null;
        }
        return index >= 0 && index < DEFAULT_THEME.length ? DEFAULT_THEME[index] : null;
    }

    Color indexedColor(int index, Color automatic) {
        if (index == SYSTEM_FOREGROUND || index == IndexedColors.AUTOMATIC.getIndex()) {
            return automatic;
        }
        if (index == SYSTEM_BACKGROUND) {
            return automatic;
        }
        byte[] v = indexed.getRGB(index);
        if (v == null) {
            v = DefaultIndexedColorMap.getDefaultRGB(index);
        }
        return v == null ? automatic : rgb(v[0], v[1], v[2]);
    }

    static Color tint(Color c, double tint) {
        float[] hsl = DmlColors.hsl(c);
        double l = hsl[2];
        l = tint < 0 ? l * (1 + tint) : l * (1 - tint) + tint;
        hsl[2] = (float) Math.max(0, Math.min(1, l));
        return DmlColors.rgb(hsl, 255);
    }

    private static Color rgb(byte r, byte g, byte b) {
        return new Color(r & 0xFF, g & 0xFF, b & 0xFF);
    }

    private static final Color[] DEFAULT_THEME = {
        Color.WHITE, Color.BLACK, new Color(0xE8E8E8), new Color(0x0E2841), new Color(0x156082), new Color(0xE97132),
        new Color(0x196B24), new Color(0x0F9ED5), new Color(0xA02B93), new Color(0x4EA72E), new Color(0x467886),
        new Color(0x96607D)
    };
}
