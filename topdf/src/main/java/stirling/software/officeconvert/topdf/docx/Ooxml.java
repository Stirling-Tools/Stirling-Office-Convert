package stirling.software.officeconvert.topdf.docx;

import java.util.Locale;

final class Ooxml {

    private Ooxml() {}

    static boolean on(XEl e) {
        if (e == null) {
            return false;
        }
        String v = e.val();
        return v == null || !(v.equals("0") || v.equalsIgnoreCase("false") || v.equalsIgnoreCase("off")
                || v.equalsIgnoreCase("none"));
    }

    static boolean flag(String v, boolean fallback) {
        if (v == null) {
            return fallback;
        }
        return !(v.equals("0") || v.equalsIgnoreCase("false") || v.equalsIgnoreCase("off") || v.equalsIgnoreCase("f"));
    }

    static Integer integer(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            try {
                double d = Double.parseDouble(s);
                return Double.isFinite(d) ? (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(d)))
                        : null;
            } catch (NumberFormatException e2) {
                return null;
            }
        }
    }

    static int integer(String v, int fallback) {
        Integer i = integer(v);
        return i == null ? fallback : i;
    }

    static Long longValue(String v) {
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            Integer i = integer(v);
            return i == null ? null : i.longValue();
        }
    }

    static long longValue(String v, long fallback) {
        Long l = longValue(v);
        return l == null ? fallback : l;
    }

    static Float twips(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        Float universal = universal(s);
        if (universal != null) {
            return universal;
        }
        Integer i = integer(s);
        return i == null ? null : clamp(i / 20f);
    }

    static float twips(String v, float fallback) {
        Float f = twips(v);
        return f == null ? fallback : f;
    }

    static Float halfPoints(String v) {
        if (v == null) {
            return null;
        }
        Float universal = universal(v.trim().toLowerCase(Locale.ROOT));
        if (universal != null) {
            return universal;
        }
        Integer i = integer(v);
        return i == null ? null : clamp(i / 2f);
    }

    static Float emu(String v) {
        if (v == null) {
            return null;
        }
        Float universal = universal(v.trim().toLowerCase(Locale.ROOT));
        if (universal != null) {
            return universal;
        }
        Long l = longValue(v);
        return l == null ? null : clamp(l / 12700f);
    }

    static float emu(String v, float fallback) {
        Float f = emu(v);
        return f == null ? fallback : f;
    }

    static Float universal(String s) {
        if (s.length() < 3) {
            return null;
        }
        String unit = s.substring(s.length() - 2);
        double factor;
        switch (unit) {
            case "pt" -> factor = 1;
            case "in" -> factor = 72;
            case "cm" -> factor = 72 / 2.54;
            case "mm" -> factor = 72 / 25.4;
            case "pc", "pi" -> factor = 12;
            case "px" -> factor = 0.75;
            default -> {
                return null;
            }
        }
        try {
            double d = Double.parseDouble(s.substring(0, s.length() - 2).trim());
            return Double.isFinite(d) ? clamp((float) (d * factor)) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static float clamp(float v) {
        if (!Float.isFinite(v)) {
            return 0;
        }
        return Math.max(-50_000f, Math.min(50_000f, v));
    }
}
