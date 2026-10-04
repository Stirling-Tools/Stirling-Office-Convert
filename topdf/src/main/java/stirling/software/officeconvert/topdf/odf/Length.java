package stirling.software.officeconvert.topdf.odf;

import java.util.Locale;

final class Length {

    private Length() {}

    static double pt(String v) {
        return pt(v, Double.NaN);
    }

    static double pt(String v, double fallback) {
        if (v == null) {
            return fallback;
        }
        String s = v.trim().toLowerCase(Locale.ROOT);
        int i = 0;
        while (i < s.length() && "+-.0123456789eE".indexOf(s.charAt(i)) >= 0) {
            if ((s.charAt(i) == 'e' || s.charAt(i) == 'E') && i + 1 < s.length() && s.charAt(i + 1) == 'm') {
                break;
            }
            i++;
        }
        double n;
        try {
            n = Double.parseDouble(s.substring(0, i));
        } catch (NumberFormatException e) {
            return fallback;
        }
        if (!Double.isFinite(n)) {
            return fallback;
        }
        return switch (s.substring(i).trim()) {
            case "in", "inch" -> n * 72;
            case "cm" -> n * 72 / 2.54;
            case "mm" -> n * 72 / 25.4;
            case "pt", "" -> n;
            case "pc" -> n * 12;
            case "px" -> n * 0.75;
            default -> fallback;
        };
    }

    static double percent(String v, double fallback) {
        if (v == null || !v.trim().endsWith("%")) {
            return fallback;
        }
        try {
            double n = Double.parseDouble(v.trim().substring(0, v.trim().length() - 1));
            return Double.isFinite(n) ? n : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static boolean isPercent(String v) {
        return v != null && v.trim().endsWith("%");
    }

    static long twips(double pt) {
        return Math.round(clamp(pt) * 20);
    }

    static long emu(double pt) {
        return Math.round(clamp(pt) * 12700);
    }

    static long halfPoints(double pt) {
        return Math.round(clamp(pt) * 2);
    }

    private static double clamp(double pt) {
        if (!Double.isFinite(pt)) {
            return 0;
        }
        return Math.max(-50_000, Math.min(50_000, pt));
    }
}
