package stirling.software.officeconvert.topdf.odf;

import java.util.Locale;

final class WordRun {

    private WordRun() {}

    static boolean bold(String v) {
        if (v == null) {
            return false;
        }
        String w = v.trim().toLowerCase(Locale.ROOT);
        if (w.equals("bold")) {
            return true;
        }
        try {
            return Integer.parseInt(w) >= 600;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static boolean italic(String v) {
        return v != null && (v.trim().equalsIgnoreCase("italic") || v.trim().equalsIgnoreCase("oblique"));
    }

    static String font(Props p, Styles styles, String suffix) {
        String name = p.get("style:font-name" + suffix);
        String fam = p.get(suffix.isEmpty() ? "fo:font-family" : "style:font-family" + suffix);
        if (name != null) {
            return styles.font(name);
        }
        if (fam == null || Styles.unquote(fam).isBlank()) {
            return null;
        }
        return Styles.unquote(fam);
    }

    /** The run properties (the inside of w:rPr) for resolved ODF text properties. */
    static String rPr(Props p, Styles styles) {
        StringBuilder b = new StringBuilder();
        String latin = font(p, styles, "");
        String asian = font(p, styles, "-asian");
        String complex = font(p, styles, "-complex");
        if (latin != null || asian != null || complex != null) {
            b.append("<w:rFonts");
            if (latin != null) {
                b.append(" w:ascii=\"").append(Xml.esc(latin)).append("\" w:hAnsi=\"").append(Xml.esc(latin))
                        .append('"');
            }
            if (asian != null) {
                b.append(" w:eastAsia=\"").append(Xml.esc(asian)).append('"');
            }
            if (complex != null) {
                b.append(" w:cs=\"").append(Xml.esc(complex)).append('"');
            }
            b.append("/>");
        }
        if (p.has("fo:font-weight")) {
            b.append(bold(p.get("fo:font-weight")) ? "<w:b/>" : "<w:b w:val=\"0\"/>");
        }
        if (p.has("style:font-weight-complex")) {
            b.append(bold(p.get("style:font-weight-complex")) ? "<w:bCs/>" : "<w:bCs w:val=\"0\"/>");
        }
        if (p.has("fo:font-style")) {
            b.append(italic(p.get("fo:font-style")) ? "<w:i/>" : "<w:i w:val=\"0\"/>");
        }
        if (p.has("style:font-style-complex")) {
            b.append(italic(p.get("style:font-style-complex")) ? "<w:iCs/>" : "<w:iCs w:val=\"0\"/>");
        }
        if (p.is("fo:text-transform", "uppercase")) {
            b.append("<w:caps/>");
        }
        if (p.is("fo:font-variant", "small-caps")) {
            b.append("<w:smallCaps/>");
        }
        strike(p, b);
        if (p.is("style:text-outline", "true")) {
            b.append("<w:outline/>");
        }
        String shadow = p.get("fo:text-shadow");
        if (shadow != null && !shadow.equals("none")) {
            b.append("<w:shadow/>");
        }
        if (p.is("style:font-relief", "embossed")) {
            b.append("<w:emboss/>");
        } else if (p.is("style:font-relief", "engraved")) {
            b.append("<w:imprint/>");
        }
        if (p.is("text:display", "none")) {
            b.append("<w:vanish/>");
        }
        String color = Colors.fill(p.get("fo:color"));
        if (color != null) {
            b.append("<w:color w:val=\"").append(color).append("\"/>");
        }
        String spacing = p.get("fo:letter-spacing");
        if (spacing != null) {
            double pt = spacing.equals("normal") ? 0 : Length.pt(spacing, 0);
            b.append("<w:spacing w:val=\"").append(Length.twips(pt)).append("\"/>");
        }
        double scale = Length.percent(p.get("style:text-scale"), 100);
        if (scale != 100 && scale > 0) {
            b.append("<w:w w:val=\"").append(Math.max(1, Math.min(600, Math.round(scale)))).append("\"/>");
        }
        if (p.is("style:letter-kerning", "true")) {
            b.append("<w:kern w:val=\"2\"/>");
        }
        double size = p.pt("fo:font-size", Double.NaN);
        String position = position(p.get("style:text-position"), Double.isNaN(size) ? 12 : size);
        if (position != null && position.startsWith("<w:position")) {
            b.append(position);
        }
        if (!Double.isNaN(size) && size > 0) {
            b.append("<w:sz w:val=\"").append(Math.max(1, Length.halfPoints(size))).append("\"/>");
        }
        double cs = p.pt("style:font-size-complex", size);
        if (!Double.isNaN(cs) && cs > 0) {
            b.append("<w:szCs w:val=\"").append(Math.max(1, Length.halfPoints(cs))).append("\"/>");
        }
        underline(p, b);
        String bg = Colors.fill(p.get("fo:background-color"));
        if (bg != null) {
            b.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(bg).append("\"/>");
        }
        if (position != null && position.startsWith("<w:vertAlign")) {
            b.append(position);
        }
        lang(p, b);
        return b.toString();
    }

    private static void strike(Props p, StringBuilder b) {
        String style = p.get("style:text-line-through-style");
        String type = p.get("style:text-line-through-type");
        boolean on = style != null && !style.equals("none") && !"none".equals(type)
                || p.get("style:text-line-through-text") != null && !p.get("style:text-line-through-text").isEmpty();
        if (on) {
            b.append("double".equals(type) ? "<w:dstrike/>" : "<w:strike/>");
        } else if ("none".equals(style)) {
            b.append("<w:strike w:val=\"0\"/>");
        }
    }

    private static void underline(Props p, StringBuilder b) {
        String style = p.get("style:text-underline-style");
        if (style == null) {
            return;
        }
        String type = p.get("style:text-underline-type", "single");
        if (style.equals("none") || type.equals("none")) {
            b.append("<w:u w:val=\"none\"/>");
            return;
        }
        boolean dbl = type.equals("double");
        boolean heavy = "bold".equals(p.get("style:text-underline-width"));
        String val = switch (style) {
            case "dotted" -> heavy ? "dottedHeavy" : "dotted";
            case "dash" -> heavy ? "dashedHeavy" : "dash";
            case "long-dash" -> heavy ? "dashLongHeavy" : "dashLong";
            case "dot-dash" -> heavy ? "dashDotHeavy" : "dotDash";
            case "dot-dot-dash" -> heavy ? "dashDotDotHeavy" : "dotDotDash";
            case "wave" -> dbl ? "wavyDouble" : heavy ? "wavyHeavy" : "wave";
            default -> dbl ? "double" : heavy ? "thick" : "single";
        };
        if (val.equals("single") && "skip-white-space".equals(p.get("style:text-underline-mode"))) {
            val = "words";
        }
        b.append("<w:u w:val=\"").append(val).append('"');
        String color = p.get("style:text-underline-color");
        if (color != null && !color.equals("font-color")) {
            String hex = Colors.fill(color);
            if (hex != null) {
                b.append(" w:color=\"").append(hex).append('"');
            }
        }
        b.append("/>");
    }

    static String position(String spec, double size) {
        if (spec == null) {
            return null;
        }
        String[] t = spec.trim().split("\\s+");
        if (t.length == 0 || t[0].isEmpty()) {
            return null;
        }
        double offset = switch (t[0]) {
            case "super" -> 33;
            case "sub" -> -33;
            default -> Length.percent(t[0], 0);
        };
        double rel = t.length > 1 ? Length.percent(t[1], 100) : 100;
        if (offset == 0) {
            return null;
        }
        if (rel < 100) {
            return offset > 0 ? "<w:vertAlign w:val=\"superscript\"/>" : "<w:vertAlign w:val=\"subscript\"/>";
        }
        return "<w:position w:val=\"" + Length.halfPoints(size * offset / 100) + "\"/>";
    }

    private static void lang(Props p, StringBuilder b) {
        String latin = lang(p.get("fo:language"), p.get("fo:country"));
        String asian = lang(p.get("style:language-asian"), p.get("style:country-asian"));
        String complex = lang(p.get("style:language-complex"), p.get("style:country-complex"));
        if (latin == null && asian == null && complex == null) {
            return;
        }
        b.append("<w:lang");
        if (latin != null) {
            b.append(" w:val=\"").append(Xml.esc(latin)).append('"');
        }
        if (asian != null) {
            b.append(" w:eastAsia=\"").append(Xml.esc(asian)).append('"');
        }
        if (complex != null) {
            b.append(" w:bidi=\"").append(Xml.esc(complex)).append('"');
        }
        b.append("/>");
    }

    private static String lang(String language, String country) {
        if (language == null || language.equals("none") || language.isBlank()) {
            return null;
        }
        return country == null || country.equals("none") || country.isBlank() ? language : language + "-" + country;
    }
}
