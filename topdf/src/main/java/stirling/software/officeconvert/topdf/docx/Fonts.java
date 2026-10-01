package stirling.software.officeconvert.topdf.docx;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.font.CloudFonts;
import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontLibrary;

final class Fonts {

    enum Slot {
        ASCII,
        HANSI,
        EAST_ASIA,
        COMPLEX
    }

    private final RenderJob job;

    private final Theme theme;

    private final Map<String, FontFace> cache = new HashMap<>();

    private final Map<FontFace, Optional<CloudFonts.Emulation>> emulations = new HashMap<>();

    private CloudFonts cloud;

    private final String defaultLang;

    private String bidiLang;

    private Map<String, String> alternatives = Map.of();

    Fonts(RenderJob job, Theme theme, String defaultLang) {
        this.job = job;
        this.theme = theme;
        this.defaultLang = defaultLang;
    }

    Fonts bidi(String lang) {
        bidiLang = lang;
        return this;
    }

    Fonts alternatives(Map<String, String> altNames) {
        alternatives = altNames;
        return this;
    }

    FontFace face(String family, boolean bold, boolean italic) {
        String key = family + "|" + bold + "|" + italic;
        FontFace f = cache.get(key);
        if (f == null) {
            f = job.fonts().find(family, bold, italic);
            String alt = alternatives.get(FontLibrary.normalize(family));
            if (f.substituted() && alt != null && !windowsFont(family) && FontLibrary.officeFont(alt)) {
                f = job.fonts().find(alt, bold, italic);
            }
            f = synthetic(f, bold, italic);
            cache.put(key, f);
        }
        return f;
    }

    // A missing Office cloud font (Aptos and so on) keeps its own width and line height on the stand-in face
    CloudFonts.Emulation emulation(FontFace face) {
        if (!face.substituted()) {
            return null;
        }
        float[] symbol = SymbolChars.vertical(face.requestedFamily());
        if (symbol != null) {
            return new CloudFonts.Emulation(face, 100, symbol, null, null);
        }
        float[] asian = eastAsianVertical(face.requestedFamily());
        if (asian != null) {
            return new CloudFonts.Emulation(face, 100, asian, null, null);
        }
        return emulations.computeIfAbsent(face, f -> {
            if (cloud == null) {
                cloud = new CloudFonts(job.fonts());
            }
            CloudFonts.Emulation e = cloud.emulate(f.requestedFamily(), f.boldStyle(), f.italicStyle());
            boolean useful = e.vertical() != null && e.face().sameProgram(f);
            return useful ? Optional.of(e) : Optional.empty();
        }).orElse(null);
    }

    private static boolean windowsFont(String family) {
        if (SymbolChars.vertical(family) != null) {
            return true;
        }
        String name = family.strip();
        while (true) {
            if (FontLibrary.officeFont(name)) {
                return true;
            }
            int space = name.lastIndexOf(' ');
            if (space <= 0) {
                return false;
            }
            name = name.substring(0, space);
        }
    }

    // Win ascent and descent in ems of common Windows East Asian fonts, with the extra Word gives such fonts
    static float[] eastAsianVertical(String family) {
        if (family == null) {
            return null;
        }
        float[] m = switch (FontLibrary.english(family).strip().toLowerCase(Locale.ROOT)) {
            case "ms gothic", "ms pgothic", "ms ui gothic", "ms mincho", "ms pmincho", "simsun", "nsimsun", "simhei",
                    "batang", "batangche", "gulim", "gulimche", "dotum", "dotumche", "gungsuh", "gungsuhche" ->
                    new float[] {220f / 256, 36f / 256};
            case "malgun gothic" -> new float[] {2229f / 2048, 495f / 2048};
            case "yu gothic", "yu gothic ui" -> new float[] {2017f / 2048, 619f / 2048};
            case "dengxian", "dengxian light" -> new float[] {0.81f, 0.232f};
            default -> null;
        };
        if (m == null) {
            return null;
        }
        float extra = Look.EAST_ASIAN_EXTRA * (m[0] + m[1]);
        return new float[] {m[0] + extra, m[1] + extra};
    }

    // True when an emulation's line metrics already hold the extra Word gives East Asian fonts
    static boolean withEastAsianExtra(CloudFonts.Emulation e) {
        return e != null && e.vertical() != null
                && Arrays.equals(e.vertical(), eastAsianVertical(e.face().requestedFamily()));
    }

    FontFace fallback(int codePoint, FontFace like) {
        String key = "fallback|" + Character.UnicodeScript.of(codePoint) + "|" + like.boldStyle() + "|"
                + like.italicStyle() + "|" + like.requestedFamily();
        FontFace f = cache.get(key);
        if (f == null || !f.covers(codePoint)) {
            f = job.fonts().fallback(codePoint, like);
            if (f == null) {
                return like;
            }
            f = synthetic(f, like.boldStyle(), like.italicStyle());
            cache.put(key, f);
        }
        return f;
    }

    private static FontFace synthetic(FontFace f, boolean bold, boolean italic) {
        boolean sb = bold && !f.bold();
        boolean si = italic && !f.italic();
        return f.withSynthetic(sb || f.syntheticBold(), si || f.syntheticItalic());
    }

    String family(RunProps rp, Slot slot) {
        String name = switch (slot) {
            case ASCII -> pick(rp.asciiTheme, rp.ascii);
            case HANSI -> pick(rp.hAnsiTheme, rp.hAnsi);
            case EAST_ASIA -> pick(rp.eastAsiaTheme, rp.eastAsia, rp.eastAsiaLang != null ? rp.eastAsiaLang
                    : defaultLang);
            case COMPLEX -> pick(rp.csTheme, rp.cs, rp.bidiLang != null ? rp.bidiLang : bidiLang);
        };
        if (name == null && slot == Slot.HANSI) {
            name = pick(rp.asciiTheme, rp.ascii);
        }
        if (name == null && slot == Slot.ASCII) {
            name = pick(rp.hAnsiTheme, rp.hAnsi);
        }
        if (name == null && slot == Slot.EAST_ASIA) {
            name = pick(rp.hAnsiTheme, rp.hAnsi);
        }
        if (name == null) {
            name = slot == Slot.COMPLEX ? pick(rp.asciiTheme, rp.ascii) : null;
        }
        return name == null || name.isBlank() ? "Times New Roman" : name;
    }

    private String pick(String themeName, String name) {
        return pick(themeName, name, defaultLang);
    }

    private String pick(String themeName, String name, String lang) {
        if (themeName != null) {
            String t = theme.font(themeName, lang);
            if (t != null) {
                return t;
            }
        }
        return name;
    }

    static Slot slot(int cp, RunProps rp) {
        boolean cs = Boolean.TRUE.equals(rp.complex) || Boolean.TRUE.equals(rp.rtl);
        if (cs && !Character.isWhitespace(cp)) {
            return Slot.COMPLEX;
        }
        if (cp < 0x80) {
            return Slot.ASCII;
        }
        if (complexScript(cp)) {
            return Slot.COMPLEX;
        }
        if (eastAsian(cp)) {
            return Slot.EAST_ASIA;
        }
        if ("eastAsia".equals(rp.hint) && ambiguous(cp)) {
            return Slot.EAST_ASIA;
        }
        return Slot.HANSI;
    }

    static boolean complexScript(int cp) {
        return cp >= 0x0590 && cp <= 0x08FF || cp >= 0x0900 && cp <= 0x0DFF || cp >= 0x0E00 && cp <= 0x0EFF
                || cp >= 0x0F00 && cp <= 0x0FFF || cp >= 0x1000 && cp <= 0x109F || cp >= 0x1780 && cp <= 0x17FF
                || cp >= 0xFB1D && cp <= 0xFDFF || cp >= 0xFE70 && cp <= 0xFEFF;
    }

    static boolean eastAsian(int cp) {
        return cp >= 0x1100 && cp <= 0x11FF || cp >= 0x2E80 && cp <= 0x2FDF || cp >= 0x2FF0 && cp <= 0x9FFF
                || cp >= 0xA000 && cp <= 0xA4CF || cp >= 0xAC00 && cp <= 0xD7AF || cp >= 0xF900 && cp <= 0xFAFF
                || cp >= 0xFE30 && cp <= 0xFE4F || cp >= 0xFF00 && cp <= 0xFFEF || cp >= 0x20000 && cp <= 0x2FFFF
                || cp >= 0x3000 && cp <= 0x303F;
    }

    private static boolean ambiguous(int cp) {
        return cp >= 0x2000 && cp <= 0x206F || cp >= 0x00A1 && cp <= 0x00FF || cp >= 0x2100 && cp <= 0x27FF;
    }
}
