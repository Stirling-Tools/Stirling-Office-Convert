package stirling.software.officeconvert.topdf.psd;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Effects {

    static final int MAX_SCAN = 1 << 20;

    static final int MAX_OCCURRENCES = 64;

    private static final Map<String, String> NAMES = Map.ofEntries(Map.entry("DrSh", "drop shadow"),
            Map.entry("dropShadowMulti", "drop shadow"), Map.entry("IrSh", "inner shadow"),
            Map.entry("innerShadowMulti", "inner shadow"), Map.entry("OrGl", "outer glow"),
            Map.entry("IrGl", "inner glow"), Map.entry("ebbl", "bevel and emboss"), Map.entry("ChFX", "satin"),
            Map.entry("SoFi", "color overlay"), Map.entry("solidFillMulti", "color overlay"),
            Map.entry("GrFl", "gradient overlay"), Map.entry("gradientFillMulti", "gradient overlay"),
            Map.entry("patternFill", "pattern overlay"), Map.entry("FrFX", "stroke"),
            Map.entry("frameFXMulti", "stroke"));

    private Effects() {}

    record Overlay(float[] color, float opacity, int blend, Descriptor gradient, Descriptor pattern) {}

    record Found(Overlay overlay, LayerEffects.Stroke stroke, LayerEffects.Shadow shadow) {}

    static Found scan(Source.Cursor c, long len, Set<String> into) throws IOException {
        byte[] b = new byte[(int) Math.min(len, MAX_SCAN)];
        c.fully(b);
        Descriptor d = b.length > 8 ? Descriptor.read(b, 8) : null;
        if (d != null) {
            return parsed(d, into);
        }
        scanText(b, into);
        return null;
    }

    private static Found parsed(Descriptor d, Set<String> into) {
        if (!d.flag("masterFXSwitch", true)) {
            return null;
        }
        Overlay overlay = null;
        LayerEffects.Stroke stroke = null;
        LayerEffects.Shadow shadow = null;
        for (Map.Entry<String, String> e : NAMES.entrySet()) {
            Object v = d.get(e.getKey());
            List<Object> all = new ArrayList<>();
            if (v instanceof Descriptor one) {
                all.add(one);
            } else if (v instanceof List<?> many) {
                all.addAll(many);
            }
            for (Object o : all) {
                if (!(o instanceof Descriptor fx) || !fx.flag("enab", false) || !fx.flag("present", true)) {
                    continue;
                }
                float[] color = fx.color("Clr ");
                float opacity = (float) (fx.number("Opct", 100) / 100);
                int mode = blend(fx.enumValue("Md  "));
                if (e.getValue().equals("color overlay") && color != null && (overlay == null || overlay.gradient() != null)) {
                    if (overlay != null) {
                        into.add("gradient overlay");
                    }
                    overlay = new Overlay(color, opacity, mode, null, null);
                    continue;
                }
                if (e.getValue().equals("gradient overlay") && overlay == null && fx.object("Grad") != null) {
                    overlay = new Overlay(null, opacity, mode, fx, null);
                    continue;
                }
                if (e.getValue().equals("pattern overlay") && overlay == null && fx.object("Ptrn") != null) {
                    overlay = new Overlay(null, opacity, mode, null, fx);
                    continue;
                }
                String paint = fx.enumValue("PntT") == null ? "SClr" : fx.enumValue("PntT");
                boolean solidPaint = "SClr".equals(paint) && color != null;
                boolean gradientPaint = "GrFl".equals(paint) && fx.object("Grad") != null;
                if (e.getValue().equals("stroke") && stroke == null && (solidPaint || gradientPaint)) {
                    String position = fx.enumValue("Styl");
                    stroke = new LayerEffects.Stroke((float) fx.number("Sz  ", 3), position == null ? "OutF" : position,
                            solidPaint ? color : new float[] {0, 0, 0}, opacity, mode, gradientPaint ? fx : null);
                    continue;
                }
                if (e.getValue().equals("drop shadow") && shadow == null && color != null) {
                    shadow = new LayerEffects.Shadow(color, opacity, mode, fx.number("lagl", 120),
                            fx.flag("uglg", true), (float) fx.number("Dstn", 5), (float) fx.number("blur", 5),
                            (float) (fx.number("Ckmt", 0) / 100), fx.flag("layerConceals", true));
                    continue;
                }
                into.add(e.getValue());
            }
        }
        return new Found(overlay, stroke, shadow);
    }

    private static int blend(String mode) {
        if (mode == null) {
            return Blend.NORMAL;
        }
        return switch (mode) {
            case "Mltp" -> Blend.MULTIPLY;
            case "Scrn" -> Blend.SCREEN;
            case "Ovrl" -> Blend.OVERLAY;
            case "Drkn" -> Blend.DARKEN;
            case "Lghn" -> Blend.LIGHTEN;
            case "SftL" -> Blend.SOFT_LIGHT;
            case "HrdL" -> Blend.HARD_LIGHT;
            case "Dfrn" -> Blend.DIFFERENCE;
            case "Xclu" -> Blend.EXCLUSION;
            case "CDdg" -> Blend.COLOR_DODGE;
            case "CBrn" -> Blend.COLOR_BURN;
            case "H   " -> Blend.HUE;
            case "Strt" -> Blend.SATURATION;
            case "Clr " -> Blend.COLOR;
            case "Lmns" -> Blend.LUMINOSITY;
            default -> Blend.NORMAL;
        };
    }

    private static void scanText(byte[] b, Set<String> into) {
        String s = new String(b, StandardCharsets.ISO_8859_1);
        int master = s.indexOf("masterFXSwitchbool");
        if (master >= 0 && master + 18 < s.length() && s.charAt(master + 18) == 0) {
            return;
        }
        for (Map.Entry<String, String> e : NAMES.entrySet()) {
            String key = e.getKey();
            int from = 0;
            int seen = 0;
            while (seen++ < MAX_OCCURRENCES && (from = s.indexOf(key, from)) >= 0) {
                int after = from + key.length();
                from = after;
                if (after + 4 > s.length()) {
                    break;
                }
                String type = s.substring(after, after + 4);
                if (!type.equals("Objc") && !type.equals("VlLs")) {
                    continue;
                }
                String window = s.substring(after, Math.min(s.length(), after + 4096));
                int end = window.length();
                for (String other : NAMES.keySet()) {
                    int o = window.indexOf(other);
                    if (o >= 0 && o < end && !other.equals(key)) {
                        end = o;
                    }
                }
                String body = window.substring(0, end);
                if (flag(body, "presentbool") == 0) {
                    continue;
                }
                if (flag(body, "enabbool") == 1) {
                    into.add(e.getValue());
                    break;
                }
            }
        }
    }

    private static int flag(String body, String key) {
        int at = body.indexOf(key);
        if (at < 0 || at + key.length() >= body.length()) {
            return -1;
        }
        return body.charAt(at + key.length()) != 0 ? 1 : 0;
    }

    static void legacy(Source.Cursor c, long len, Set<String> into) throws IOException {
        if (len < 4) {
            return;
        }
        c.u16();
        int n = c.u16();
        long left = len - 4;
        for (int i = 0; i < n && i < 16 && left >= 12; i++) {
            c.tag();
            int key = c.tag();
            long size = c.u32();
            left -= 12;
            if (size > left) {
                return;
            }
            String name = switch (key) {
                case 0x64736477 -> "drop shadow";
                case 0x69736477 -> "inner shadow";
                case 0x6F676C77 -> "outer glow";
                case 0x69676C77 -> "inner glow";
                case 0x6265766C -> "bevel and emboss";
                case 0x736F6669 -> "color overlay";
                default -> null;
            };
            byte[] body = new byte[(int) Math.min(size, MAX_SCAN)];
            c.fully(body);
            c.skip(size - body.length);
            left -= size;
            if (key == 0x636D6E53 && body.length > 4 && body[4] == 0) {
                return;
            }
            int flag = switch (key) {
                case 0x64736477, 0x69736477 -> 38;
                case 0x6F676C77, 0x69676C77 -> 30;
                case 0x6265766C -> 55;
                case 0x736F6669 -> 23;
                default -> -1;
            };
            if (name != null && flag >= 0 && flag < body.length && body[flag] != 0) {
                into.add(name);
            }
        }
    }

    static float[] solidColor(Source.Cursor c, long len) throws IOException {
        byte[] b = new byte[(int) Math.min(len, 1 << 16)];
        c.fully(b);
        double r = number(b, "Rd  ");
        double g = number(b, "Grn ");
        double bl = number(b, "Bl  ");
        if (!Double.isNaN(r) && !Double.isNaN(g) && !Double.isNaN(bl)) {
            return new float[] {(float) (r / 255), (float) (g / 255), (float) (bl / 255)};
        }
        double cy = number(b, "Cyn ");
        double m = number(b, "Mgnt");
        double y = number(b, "Ylw ");
        double k = number(b, "Blck");
        if (!Double.isNaN(cy) && !Double.isNaN(m) && !Double.isNaN(y) && !Double.isNaN(k)) {
            return new float[] {(float) (cy / 100), (float) (m / 100), (float) (y / 100), (float) (k / 100), -1};
        }
        double gray = number(b, "Gry ");
        if (!Double.isNaN(gray)) {
            return new float[] {(float) (1 - gray / 100)};
        }
        return null;
    }

    static double number(byte[] b, String key) {
        byte[] k = (key + "doub").getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i + k.length + 8 <= b.length; i++) {
            for (int j = 0; j < k.length; j++) {
                if (b[i + j] != k[j]) {
                    continue outer;
                }
            }
            long bits = 0;
            for (int j = 0; j < 8; j++) {
                bits = bits << 8 | b[i + k.length + j] & 0xFF;
            }
            double v = Double.longBitsToDouble(bits);
            return Double.isFinite(v) ? v : Double.NaN;
        }
        return Double.NaN;
    }
}
