package stirling.software.officeconvert.topdf.dml;

import java.awt.Color;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** DrawingML colour names and colour transforms, shared by the DOCX, PPTX and XLSX renderers. */
public final class DmlColors {

    private static final Map<String, Color> PRESET = new HashMap<>();

    static {
        String[] preset = {"aliceBlue F0F8FF", "antiqueWhite FAEBD7", "aqua 00FFFF", "aquamarine 7FFFD4",
            "azure F0FFFF", "beige F5F5DC", "bisque FFE4C4", "black 000000", "blanchedAlmond FFEBCD", "blue 0000FF",
            "blueViolet 8A2BE2", "brown A52A2A", "burlyWood DEB887", "cadetBlue 5F9EA0", "chartreuse 7FFF00",
            "chocolate D2691E", "coral FF7F50", "cornflowerBlue 6495ED", "cornsilk FFF8DC", "crimson DC143C",
            "cyan 00FFFF", "darkBlue 00008B", "darkCyan 008B8B", "darkGoldenrod B8860B", "darkGray A9A9A9",
            "darkGrey A9A9A9", "darkGreen 006400", "darkKhaki BDB76B", "darkMagenta 8B008B", "darkOliveGreen 556B2F",
            "darkOrange FF8C00", "darkOrchid 9932CC", "darkRed 8B0000", "darkSalmon E9967A", "darkSeaGreen 8FBC8F",
            "darkSlateBlue 483D8B", "darkSlateGray 2F4F4F", "darkSlateGrey 2F4F4F", "darkTurquoise 00CED1",
            "darkViolet 9400D3", "deepPink FF1493", "deepSkyBlue 00BFFF", "dimGray 696969", "dimGrey 696969",
            "dodgerBlue 1E90FF", "firebrick B22222", "floralWhite FFFAF0", "forestGreen 228B22", "fuchsia FF00FF",
            "gainsboro DCDCDC", "ghostWhite F8F8FF", "gold FFD700", "goldenrod DAA520", "gray 808080", "grey 808080",
            "green 008000", "greenYellow ADFF2F", "honeydew F0FFF0", "hotPink FF69B4", "indianRed CD5C5C",
            "indigo 4B0082", "ivory FFFFF0", "khaki F0E68C", "lavender E6E6FA", "lavenderBlush FFF0F5",
            "lawnGreen 7CFC00", "lemonChiffon FFFACD", "lightBlue ADD8E6", "lightCoral F08080", "lightCyan E0FFFF",
            "lightGoldenrodYellow FAFAD2", "lightGray D3D3D3", "lightGrey D3D3D3", "lightGreen 90EE90",
            "lightPink FFB6C1", "lightSalmon FFA07A", "lightSeaGreen 20B2AA", "lightSkyBlue 87CEFA",
            "lightSlateGray 778899", "lightSlateGrey 778899", "lightSteelBlue B0C4DE", "lightYellow FFFFE0",
            "lime 00FF00", "limeGreen 32CD32", "linen FAF0E6", "magenta FF00FF", "maroon 800000",
            "medAquamarine 66CDAA", "medBlue 0000CD", "medOrchid BA55D3", "medPurple 9370DB", "medSeaGreen 3CB371",
            "medSlateBlue 7B68EE", "medSpringGreen 00FA9A", "medTurquoise 48D1CC", "medVioletRed C71585",
            "midnightBlue 191970", "mintCream F5FFFA", "mistyRose FFE4E1", "moccasin FFE4B5", "navajoWhite FFDEAD",
            "navy 000080", "oldLace FDF5E6", "olive 808000", "oliveDrab 6B8E23", "orange FFA500", "orangeRed FF4500",
            "orchid DA70D6", "paleGoldenrod EEE8AA", "paleGreen 98FB98", "paleTurquoise AFEEEE", "paleVioletRed DB7093",
            "papayaWhip FFEFD5", "peachPuff FFDAB9", "peru CD853F", "pink FFC0CB", "plum DDA0DD", "powderBlue B0E0E6",
            "purple 800080", "red FF0000", "rosyBrown BC8F8F", "royalBlue 4169E1", "saddleBrown 8B4513",
            "salmon FA8072", "sandyBrown F4A460", "seaGreen 2E8B57", "seaShell FFF5EE", "sienna A0522D",
            "silver C0C0C0", "skyBlue 87CEEB", "slateBlue 6A5ACD", "slateGray 708090", "slateGrey 708090",
            "snow FFFAFA", "springGreen 00FF7F", "steelBlue 4682B4", "tan D2B48C", "teal 008080", "thistle D8BFD8",
            "tomato FF6347", "turquoise 40E0D0", "violet EE82EE", "wheat F5DEB3", "white FFFFFF", "whiteSmoke F5F5F5",
            "yellow FFFF00", "yellowGreen 9ACD32"};
        for (String p : preset) {
            int sp = p.indexOf(' ');
            PRESET.put(p.substring(0, sp).toLowerCase(Locale.ROOT), new Color(Integer.parseInt(p.substring(sp + 1), 16)));
        }
    }

    private DmlColors() {}

    /** The colour of an {@code a:prstClr} name, or null. */
    public static Color preset(String name) {
        return name == null ? null : PRESET.get(name.toLowerCase(Locale.ROOT));
    }

    /** An {@code a:hslClr}: hue in 60000ths of a degree, saturation and luminance in 1000ths of a percent. */
    public static Color hsl(long hue, long sat, long lum) {
        float h = (float) (Math.floorMod(hue, 21_600_000L) / 21_600_000.0);
        return rgb(new float[] {h, unit(sat), unit(lum)}, 255);
    }

    /** Applies one colour transform element ({@code lumMod}, {@code tint}, ...) with its value in 1000ths of a percent. */
    public static Color modify(Color c, String name, long value) {
        float v = value / 100000f;
        int alpha = c.getAlpha();
        return switch (name) {
            case "lumMod", "lumOff", "satMod", "satOff", "hueMod", "hueOff", "lum", "sat", "hue" -> {
                float[] hsl = hsl(c);
                switch (name) {
                    case "lumMod" -> hsl[2] = clamp(hsl[2] * v);
                    case "lumOff" -> hsl[2] = clamp(hsl[2] + v);
                    case "lum" -> hsl[2] = clamp(v);
                    case "satMod" -> hsl[1] = clamp(hsl[1] * v);
                    case "satOff" -> hsl[1] = clamp(hsl[1] + v);
                    case "sat" -> hsl[1] = clamp(v);
                    case "hueMod" -> hsl[0] = ((hsl[0] * v) % 1 + 1) % 1;
                    case "hueOff" -> hsl[0] = (float) (((hsl[0] + value / 21_600_000.0) % 1 + 1) % 1);
                    default -> hsl[0] = (float) (Math.floorMod(value, 21_600_000L) / 21_600_000.0);
                }
                yield rgb(hsl, alpha);
            }
            case "tint" -> new Color(light(c.getRed(), 1, v), light(c.getGreen(), 1, v), light(c.getBlue(), 1, v),
                    alpha);
            case "shade" -> new Color(light(c.getRed(), 0, v), light(c.getGreen(), 0, v), light(c.getBlue(), 0, v),
                    alpha);
            case "alpha" -> new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(clamp(v) * 255));
            case "alphaMod" -> new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(clamp(alpha / 255f * v)
                    * 255));
            case "alphaOff" -> new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(clamp(alpha / 255f + v)
                    * 255));
            case "gray" -> {
                int g = Math.round(c.getRed() * 0.3f + c.getGreen() * 0.59f + c.getBlue() * 0.11f);
                yield new Color(g, g, g, alpha);
            }
            case "inv" -> new Color(255 - c.getRed(), 255 - c.getGreen(), 255 - c.getBlue(), alpha);
            case "comp" -> {
                float[] hsl = hsl(c);
                hsl[0] = (hsl[0] + 0.5f) % 1;
                yield rgb(hsl, alpha);
            }
            case "redMod", "greenMod", "blueMod" -> channel(c, name.charAt(0), v, true);
            case "redOff", "greenOff", "blueOff" -> channel(c, name.charAt(0), v, false);
            default -> c;
        };
    }

    private static Color channel(Color c, char which, float v, boolean multiply) {
        int r = c.getRed();
        int g = c.getGreen();
        int b = c.getBlue();
        switch (which) {
            case 'r' -> r = scale(r, v, multiply);
            case 'g' -> g = scale(g, v, multiply);
            default -> b = scale(b, v, multiply);
        }
        return new Color(r, g, b, c.getAlpha());
    }

    private static int scale(int c, float v, boolean multiply) {
        return Math.max(0, Math.min(255, Math.round(multiply ? c * v : c + v * 255)));
    }

    // Office mixes tints and shades in linear light: 4472C4 at a 50% shade is 2F528F, not 223962
    private static int light(int c, float target, float keep) {
        float v = c / 255f;
        double linear = v <= 0.04045f ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        double mixed = Math.max(0, Math.min(1, linear * clamp(keep) + target * (1 - clamp(keep))));
        double out = mixed <= 0.0031308 ? mixed * 12.92 : 1.055 * Math.pow(mixed, 1 / 2.4) - 0.055;
        return Math.max(0, Math.min(255, (int) Math.round(out * 255)));
    }

    private static float unit(long v) {
        return clamp(v / 100000f);
    }

    private static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    /** Hue, saturation and luminance of a colour, each 0 to 1. */
    public static float[] hsl(Color c) {
        float r = c.getRed() / 255f;
        float g = c.getGreen() / 255f;
        float b = c.getBlue() / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2;
        float h = 0;
        float s = 0;
        if (max != min) {
            float d = max - min;
            s = l > 0.5f ? d / (2 - max - min) : d / (max + min);
            if (max == r) {
                h = (g - b) / d + (g < b ? 6 : 0);
            } else if (max == g) {
                h = (b - r) / d + 2;
            } else {
                h = (r - g) / d + 4;
            }
            h /= 6;
        }
        return new float[] {h, s, l};
    }

    /** The colour for hue, saturation and luminance (0 to 1) with the given alpha (0 to 255). */
    public static Color rgb(float[] hsl, int alpha) {
        float h = hsl[0];
        float s = hsl[1];
        float l = hsl[2];
        float r;
        float g;
        float b;
        if (s == 0) {
            r = g = b = l;
        } else {
            float q = l < 0.5f ? l * (1 + s) : l + s - l * s;
            float p = 2 * l - q;
            r = hue(p, q, h + 1f / 3);
            g = hue(p, q, h);
            b = hue(p, q, h - 1f / 3);
        }
        return new Color(Math.round(clamp(r) * 255), Math.round(clamp(g) * 255), Math.round(clamp(b) * 255), alpha);
    }

    private static float hue(float p, float q, float t) {
        if (t < 0) {
            t += 1;
        }
        if (t > 1) {
            t -= 1;
        }
        if (t < 1f / 6) {
            return p + (q - p) * 6 * t;
        }
        if (t < 0.5f) {
            return q;
        }
        if (t < 2f / 3) {
            return p + (q - p) * (2f / 3 - t) * 6;
        }
        return p;
    }
}
