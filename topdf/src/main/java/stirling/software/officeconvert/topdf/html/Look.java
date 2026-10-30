package stirling.software.officeconvert.topdf.html;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.RunStyle;

/** The inherited part of an element's computed style: what its text looks like and how its lines are set. */
final class Look {

    static final float ROOT_SIZE = 12f;

    private static final int MAX_LINES = 20;

    String font = generic("serif");
    float size = ROOT_SIZE;
    boolean bold;
    boolean italic;
    boolean underline;
    boolean strike;
    int color;
    int highlight = -1;
    int vertAlign;
    boolean smallCaps;
    float spacing;
    Paragraph.Align align = Paragraph.Align.LEFT;
    boolean alignFixed;
    float lineMultiple;
    float linePoints;
    float textIndent;
    String whiteSpace = "normal";
    boolean rightToLeft;
    String transform = "none";
    String listStyle;
    boolean hidden;
    boolean centerBlocks;
    boolean legacyAlign;
    Vars.Scope vars = Vars.Scope.root();
    // the keyword size in px the font size still follows (medium = 16), or 0 once a length sets it
    float keywordPx = 16;
    // the family is the bare generic monospace, which browsers set at 13px where the keyword says 16
    boolean monoOnly;
    float opacity = 1;
    boolean hyphens;
    String lang = "";
    int widows = 2;
    int orphans = 2;

    Look copy() {
        Look l = new Look();
        l.font = font;
        l.size = size;
        l.bold = bold;
        l.italic = italic;
        l.underline = underline;
        l.strike = strike;
        l.color = color;
        l.highlight = highlight;
        l.vertAlign = vertAlign;
        l.smallCaps = smallCaps;
        l.spacing = spacing;
        l.align = align;
        l.alignFixed = alignFixed;
        l.lineMultiple = lineMultiple;
        l.hyphens = hyphens;
        l.lang = lang;
        l.widows = widows;
        l.orphans = orphans;
        l.linePoints = linePoints;
        l.textIndent = textIndent;
        l.whiteSpace = whiteSpace;
        l.rightToLeft = rightToLeft;
        l.transform = transform;
        l.listStyle = listStyle;
        l.hidden = hidden;
        l.centerBlocks = centerBlocks;
        l.legacyAlign = legacyAlign;
        l.vars = vars;
        l.keywordPx = keywordPx;
        l.monoOnly = monoOnly;
        l.opacity = opacity;
        return l;
    }

    /** This look as a child with {@code d} declared on it sees it; {@code width} is the room 100% means. */
    Look child(Map<String, String> d, float width, boolean inline) {
        Look l = copy();
        l.vars = Vars.scope(vars, d);
        l.opacity = opacity * Css.opacity(d.get("opacity"));
        if (!inline) {
            // A background is painted by the box that sets it, so a block inside an inline does not inherit it
            l.vertAlign = 0;
            l.highlight = -1;
        }
        String v;
        if ((v = d.get("hyphens")) != null || (v = d.get("-webkit-hyphens")) != null) {
            String h = v.strip().toLowerCase(Locale.ROOT);
            l.hyphens = h.equals("auto") || !h.equals("manual") && !h.equals("none") && hyphens;
        }
        l.widows = lines(d.get("widows"), widows);
        l.orphans = lines(d.get("orphans"), orphans);
        if ((v = d.get(Presentation.LANG)) != null) {
            l.lang = v.strip().toLowerCase(Locale.ROOT);
        }
        if ((v = d.get("font-family")) != null) {
            String f = family(v);
            if (f != null) {
                l.font = f;
            }
            l.monoOnly = v.strip().equalsIgnoreCase("monospace");
        }
        if ((v = d.get("font-size")) != null) {
            l.keywordPx = keywordPx(v, keywordPx);
            l.size = Math.max(1f, Math.min(400f, fontSize(v, size)));
        }
        boolean resized = l.monoOnly != monoOnly || d.containsKey("font-size");
        if (l.keywordPx > 0 && resized) {
            l.size = l.keywordPx * 0.75f * (l.monoOnly ? 13f / 16f : 1f);
        }
        if ((v = d.get("font-weight")) != null) {
            l.bold = weight(v, bold);
        }
        if ((v = d.get("font-style")) != null) {
            l.italic = !v.toLowerCase(Locale.ROOT).startsWith("normal");
        }
        if ((v = d.get("font-variant")) != null) {
            l.smallCaps = v.toLowerCase(Locale.ROOT).contains("small-caps");
        }
        if ((v = d.get("text-decoration")) != null) {
            String t = v.toLowerCase(Locale.ROOT);
            l.underline = t.contains("underline") || !t.contains("none") && underline;
            l.strike = t.contains("line-through") || !t.contains("none") && strike;
        }
        Integer c;
        if ((c = Css.color(d.get("color"))) != null && c >= 0) {
            l.color = c;
        }
        if (inline && (c = Css.color(d.get("background-color"))) != null) {
            l.highlight = c;
        }
        if (inline && (v = d.get("vertical-align")) != null) {
            l.vertAlign = switch (v.strip().toLowerCase(Locale.ROOT)) {
                case "super", "text-top" -> 1;
                case "sub" -> -1;
                default -> 0;
            };
        }
        if ((v = d.get("letter-spacing")) != null) {
            Float pt = Css.length(v, l.size, l.size);
            l.spacing = pt == null ? 0 : Math.max(-20, Math.min(20, pt));
        }
        if ((v = d.get("text-align")) != null) {
            l.align = align(v, align);
            String keyword = v.strip().toLowerCase(Locale.ROOT);
            l.alignFixed = keyword.contains("left") || keyword.contains("right")
                    || !keyword.equals("start") && !keyword.equals("end") && l.align == align && alignFixed;
            l.centerBlocks = keyword.equals("-webkit-center");
            l.legacyAlign = keyword.startsWith("-webkit-");
        }
        if ((v = d.get("line-height")) != null) {
            lineHeight(l, v.strip().toLowerCase(Locale.ROOT));
        }
        if ((v = d.get("text-indent")) != null) {
            Float pt = Css.length(v, l.size, width);
            l.textIndent = pt == null ? 0 : pt;
        }
        if ((v = d.get("white-space")) != null) {
            l.whiteSpace = v.strip().toLowerCase(Locale.ROOT);
        }
        if ((v = d.get("direction")) != null) {
            l.rightToLeft = "rtl".equalsIgnoreCase(v.strip());
        }
        if ((v = d.get("text-transform")) != null) {
            l.transform = v.strip().toLowerCase(Locale.ROOT);
        }
        if ((v = d.get("list-style-type")) != null) {
            l.listStyle = v.strip().toLowerCase(Locale.ROOT);
        }
        if ((v = d.get("visibility")) != null) {
            l.hidden = "hidden".equalsIgnoreCase(v.strip()) || "collapse".equalsIgnoreCase(v.strip());
        }
        return l;
    }

    private static void lineHeight(Look l, String v) {
        if (v.equals("normal") || v.isEmpty()) {
            l.lineMultiple = 0;
            l.linePoints = 0;
            return;
        }
        if (v.matches("[0-9]*[.]?[0-9]+")) {
            l.lineMultiple = Math.max(0.5f, Math.min(10f, Float.parseFloat(v)));
            l.linePoints = 0;
            return;
        }
        Float pt = Css.length(v, l.size, l.size);
        if (pt != null && pt > 0) {
            l.linePoints = pt;
            l.lineMultiple = 0;
        }
    }

    private static boolean weight(String v, boolean parent) {
        String t = v.strip().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "bold", "bolder" -> true;
            case "normal", "lighter" -> false;
            default -> t.matches("[0-9]{1,4}") ? Integer.parseInt(t) >= 600 : parent;
        };
    }

    private static Paragraph.Align align(String v, Paragraph.Align parent) {
        return switch (v.strip().toLowerCase(Locale.ROOT)) {
            case "center", "-webkit-center", "middle" -> Paragraph.Align.CENTER;
            case "right", "end", "-webkit-right" -> Paragraph.Align.RIGHT;
            case "justify" -> Paragraph.Align.JUSTIFY;
            case "left", "start", "-webkit-left" -> Paragraph.Align.LEFT;
            default -> parent;
        };
    }

    // A keyword, or ems and percents of a keyword size, still follow it; any other length breaks the chain
    private static float keywordPx(String v, float parent) {
        String t = v.strip().toLowerCase(Locale.ROOT);
        float keyword = keywordPx(t);
        if (keyword > 0 || parent <= 0) {
            return keyword;
        }
        if (t.matches("[0-9]*[.]?[0-9]+(em|%)")) {
            Float factor = Css.length(t, 1, 1);
            return factor == null || factor <= 0 ? 0 : Math.min(400, parent * factor);
        }
        return 0;
    }

    private static float keywordPx(String v) {
        return switch (v.strip().toLowerCase(Locale.ROOT)) {
            case "xx-small" -> 9;
            case "x-small" -> 10;
            case "small" -> 13;
            case "medium" -> 16;
            case "large" -> 18;
            case "x-large" -> 24;
            case "xx-large" -> 32;
            case "xxx-large" -> 48;
            default -> 0;
        };
    }

    private static float fontSize(String v, float parent) {
        String t = v.strip().toLowerCase(Locale.ROOT);
        float px = keywordPx(t);
        if (px > 0) {
            return px * 0.75f;
        }
        if (t.equals("smaller")) {
            return parent / 1.2f;
        }
        if (t.equals("larger")) {
            return parent * 1.2f;
        }
        Float pt = Css.length(t, parent, parent);
        return pt == null || pt <= 0 ? parent : pt;
    }

    // Which families can be drawn while one document converts; a browser skips web fonts it never loaded
    private static final ThreadLocal<Predicate<String>> DRAWABLE = ThreadLocal.withInitial(() -> f -> true);

    static Predicate<String> drawable() {
        return DRAWABLE.get();
    }

    static void drawable(Predicate<String> test) {
        DRAWABLE.set(test);
    }

    static void clearDrawable() {
        DRAWABLE.remove();
    }

    /** The first family of a CSS font stack that can be drawn, as a browser picks it. */
    boolean hyphenates() {
        return hyphens && (lang.equals("en") || lang.startsWith("en-") || lang.startsWith("en_"));
    }

    private static int lines(String v, int inherited) {
        if (v == null) {
            return inherited;
        }
        String s = v.strip().toLowerCase(Locale.ROOT);
        if (s.equals("initial") || s.equals("unset")) {
            return 2;
        }
        try {
            int n = Integer.parseInt(s);
            return n < 1 ? inherited : Math.min(n, MAX_LINES);
        } catch (NumberFormatException e) {
            return inherited;
        }
    }

    static String family(String list) {
        for (String raw : Css.splitTopLevel(list, ',')) {
            String f = raw.strip().replaceAll("^[\"']|[\"']$", "").strip();
            if (f.isEmpty() || f.startsWith("-") || f.equalsIgnoreCase("inherit") || f.equalsIgnoreCase("initial")) {
                continue;
            }
            String generic = generic(f.toLowerCase(Locale.ROOT));
            if (generic != null) {
                return generic;
            }
            if (f.length() <= 64 && DRAWABLE.get().test(f)) {
                return f;
            }
        }
        return null;
    }

    private static final ThreadLocal<Map<String, String>> GENERICS = ThreadLocal.withInitial(Map::of);

    static final Map<String, List<String>> FONTCONFIG_ORDER = Map.of(
            "serif", List.of("Noto Serif", "DejaVu Serif", "Times New Roman", "Thorndale AMT", "Luxi Serif",
                    "Nimbus Roman No9 L", "Nimbus Roman", "Times"),
            "sans-serif", List.of("Noto Sans", "DejaVu Sans", "Verdana", "Arial", "Albany AMT", "Luxi Sans",
                    "Nimbus Sans L", "Nimbus Sans", "Helvetica", "Lucida Sans Unicode", "Tahoma"),
            "monospace", List.of("Noto Sans Mono", "DejaVu Sans Mono", "Inconsolata", "Andale Mono", "Courier New",
                    "Cumberland AMT", "Luxi Mono", "Nimbus Mono L", "Nimbus Mono", "Nimbus Mono PS", "Courier"));

    static void generics(Map<String, String> installed) {
        GENERICS.set(installed);
    }

    static void clearGenerics() {
        GENERICS.remove();
    }

    private static String generic(String f) {
        String key = switch (f) {
            case "serif", "ui-serif" -> "serif";
            case "sans-serif", "system-ui", "ui-sans-serif", "ui-rounded", "blinkmacsystemfont" -> "sans-serif";
            case "monospace", "ui-monospace" -> "monospace";
            default -> f;
        };
        String installed = GENERICS.get().get(key);
        if (installed != null) {
            return installed;
        }
        return switch (key) {
            case "serif" -> "Times New Roman";
            case "sans-serif" -> "Arial";
            case "monospace" -> "Courier New";
            case "cursive" -> "Comic Sans MS";
            case "fantasy" -> "Impact";
            default -> null;
        };
    }

    Paragraph.Align visualAlign() {
        if (!rightToLeft || alignFixed) {
            return align;
        }
        return switch (align) {
            case LEFT -> Paragraph.Align.RIGHT;
            case RIGHT -> Paragraph.Align.LEFT;
            default -> align;
        };
    }

    RunStyle run() {
        return new RunStyle(font, size, bold, italic, underline, strike, color, highlight, vertAlign, false, spacing, 100,
                smallCaps);
    }

    String transform(String text) {
        return switch (transform) {
            case "uppercase" -> text.toUpperCase(Locale.ROOT);
            case "lowercase" -> text.toLowerCase(Locale.ROOT);
            case "capitalize" -> capitalize(text);
            default -> text;
        };
    }

    private static String capitalize(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean start = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(start && Character.isLetter(c) ? Character.toUpperCase(c) : c);
            start = Character.isWhitespace(c);
        }
        return sb.toString();
    }
}
