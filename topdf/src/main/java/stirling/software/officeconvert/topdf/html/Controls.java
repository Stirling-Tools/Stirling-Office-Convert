package stirling.software.officeconvert.topdf.html;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import stirling.software.officeconvert.model.FormField;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.Table;

final class Controls {

    static final Set<String> TAGS = Set.of("input", "select", "textarea", "button");

    private static final Set<String> BUTTONS = Set.of("submit", "reset", "button");

    private static final Set<String> NOT_DRAWN = Set.of("hidden", "image");

    private static final int LIGHT_GREY = 0xD3D3D3;

    private static final int PLACEHOLDER_GREY = 0x757575;

    private static final int SELECTED_GREY = 0xCECECE;

    private static final int MAX_VALUE_CHARS = 100_000;

    private static final int MAX_CHOICE_CHARS = 200_000;

    private static final float MAX_SIDE = 1_440f;

    private Controls() {}

    static boolean drawn(Element e) {
        return !(e.normalName().equals("input") && NOT_DRAWN.contains(type(e)));
    }

    static boolean holdsBox(Element e) {
        for (Element c : e.select("input, select, textarea")) {
            String kind = kind(c);
            if (drawn(c) && !kind.equals("checkbox") && !kind.equals("radio") && !kind.equals("button")) {
                return true;
            }
        }
        return false;
    }

    static String type(Element e) {
        return e.attr("type").strip().toLowerCase(Locale.ROOT);
    }

    static Map<String, String> withDefaults(Element e, Map<String, String> declared) {
        Map<String, String> d = new HashMap<>();
        d.put("font-size", "0.85em");
        d.put("text-align", "left");
        String kind = kind(e);
        switch (kind) {
            case "checkbox", "radio" -> d.put("vertical-align", "-0.2em");
            case "button", "select" -> {
                d.put("background-color", "lightgrey");
                d.put("border-radius", "0.25em");
                if (kind.equals("button")) {
                    d.put("text-align", "center");
                }
            }
            default -> { }
        }
        d.putAll(declared);
        d.put("white-space", kind.equals("textarea") ? "pre-wrap" : "pre");
        d.remove("text-indent");
        return d;
    }

    private static String kind(Element e) {
        String tag = e.normalName();
        if (!tag.equals("input")) {
            return tag;
        }
        String t = type(e);
        if (BUTTONS.contains(t)) {
            return "button";
        }
        return t.equals("checkbox") || t.equals("radio") ? t : "text";
    }

    static Inline.FormControl control(Element e, Map<String, String> d, Look look, float room, String label) {
        return control(e, kind(e), d, look, room, label, null, null);
    }

    static Inline.FormControl button(Element e, Map<String, String> d, Look look, float room, String text, String link) {
        return control(e, "button", d, look, room, null, text, link);
    }

    private static Inline.FormControl control(Element e, String kind, Map<String, String> d, Look look, float room,
            String label, String own, String link) {
        float em = look.size;
        boolean check = kind.equals("checkbox") || kind.equals("radio");
        float pad = own != null ? 0 : 0.2f * em;
        float padLeft = side(d, "padding-left", em, room, pad);
        float padRight = side(d, "padding-right", em, room, pad);
        float padTop = side(d, "padding-top", em, room, pad);
        float padBottom = side(d, "padding-bottom", em, room, pad);
        Table.Border[] borders = own != null
                ? new Table.Border[] {HtmlTables.border(d, "top", 0), HtmlTables.border(d, "right", 0),
                        HtmlTables.border(d, "bottom", 0), HtmlTables.border(d, "left", 0)}
                : new Table.Border[] {edge(d, "top"), edge(d, "right"), edge(d, "bottom"), edge(d, "left")};
        boolean bottomOnly = !borders[0].visible() && !borders[1].visible() && borders[2].visible()
                && !borders[3].visible();
        Table.Border line = bottomOnly ? Table.Border.NONE : strongest(borders);
        float edge = line.width();
        Paragraph shown = new Paragraph();
        shown.align = look.align == Paragraph.Align.JUSTIFY ? Paragraph.Align.LEFT : look.align;
        RunStyle style = new RunStyle(look.font, look.size, look.bold, look.italic, look.underline, look.strike,
                look.color, -1, 0, false, look.spacing, 100, look.smallCaps);
        shown.markStyle = style;
        if (bottomOnly) {
            shown.borderBottom = borders[2].width();
            shown.borderBottomRgb = borders[2].rgb();
        }
        FormField field = field(e, kind, label);
        String text = own != null ? own : shownText(e, kind, field);
        boolean placeholder = field != null && field.placeholderShown();
        if (field != null && field.kind() == FormField.Kind.LIST) {
            listLines(shown, field, style);
        } else {
            lines(shown, text, placeholder ? withColor(style, PLACEHOLDER_GREY) : style);
        }
        if (link != null) {
            shown.inlines.replaceAll(i -> i instanceof Inline.Text t ? new Inline.Text(t.text(), t.style(), link, -1) : i);
        }
        Float width = cssLength(d.get("width"), em, room);
        Float height = cssLength(d.get("height"), em, 0);
        boolean borderBox = "border-box".equalsIgnoreCase(d.getOrDefault("box-sizing", "").strip());
        float outsideX = padLeft + padRight + 2 * edge;
        float outsideY = padTop + padBottom + 2 * edge + (bottomOnly ? shown.borderBottom : 0);
        float contentW;
        if (width != null) {
            contentW = borderBox ? width - outsideX : width;
        } else if (check) {
            contentW = 0.7f * em;
        } else if (kind.equals("button")) {
            float measured = own != null ? labelWidth(text, look) : textWidth(text, em);
            contentW = Math.min(room - outsideX, measured + 0.1f * em);
        } else if (kind.equals("textarea") && digits(e.attr("cols"))) {
            contentW = Integer.parseInt(e.attr("cols").strip()) * 0.55f * em;
        } else if (kind.equals("text") && digits(e.attr("size"))) {
            contentW = Integer.parseInt(e.attr("size").strip()) * 0.55f * em;
        } else {
            contentW = 20 * em;
        }
        float contentH;
        if (height != null) {
            contentH = borderBox ? height - outsideY : height;
        } else if (check) {
            contentH = 0.7f * em;
        } else if (kind.equals("textarea")) {
            contentH = digits(e.attr("rows")) ? Integer.parseInt(e.attr("rows").strip()) * 1.2f * em : 3 * em;
        } else if (kind.equals("select") && e.hasAttr("multiple")) {
            contentH = 3.6f * em;
        } else if (own != null) {
            contentH = lines(text, look, clamp(contentW, 1));
        } else {
            contentH = 1.2f * em;
        }
        contentW = clamp(contentW, 1);
        contentH = clamp(contentH, 1);
        float outerW = Math.min(MAX_SIDE, contentW + outsideX);
        float outerH = Math.min(MAX_SIDE, contentH + outsideY);
        Integer fill = Css.color(d.get("background-color"));
        boolean round = kind.equals("radio");
        Float radius = cssLength(d.get("border-radius"), em, outerW);
        float corner = round || radius == null || radius < 0.5f ? 0 : Math.min(radius, Math.min(outerW, outerH) / 2);
        float lower;
        if (check) {
            Float v = Css.length(d.getOrDefault("vertical-align", "0"), em, 0);
            lower = v == null ? 0 : Math.max(0, -v);
        } else if (kind.equals("textarea") || kind.equals("select") && e.hasAttr("multiple")) {
            lower = 0;
        } else {
            lower = Math.max(0, contentH / 2 - 0.355f * em + padBottom + edge);
        }
        return new Inline.FormControl(field, shown, outerW, outerH, padLeft + edge, padTop + edge, padRight + edge,
                padBottom + edge + (bottomOnly ? shown.borderBottom : 0), fill == null ? -1 : fill,
                line.visible() ? line.rgb() : -1, edge, corner, round, lower, own != null,
                own != null && height != null);
    }

    private static FormField field(Element e, String kind, String label) {
        if (kind.equals("button")) {
            return null;
        }
        String name = cap(e.attr("name").strip());
        boolean disabled = e.hasAttr("disabled");
        boolean required = e.hasAttr("required");
        String t = type(e);
        return switch (kind) {
            case "checkbox", "radio" -> new FormField(kind.equals("radio") ? FormField.Kind.RADIO : FormField.Kind.CHECKBOX,
                    name, label, e.hasAttr("value") ? e.attr("value") : "on", null, e.hasAttr("checked"), false, disabled,
                    required, 0);
            case "select" -> {
                List<FormField.Choice> choices = choices(e);
                yield new FormField(e.hasAttr("multiple") ? FormField.Kind.LIST : FormField.Kind.COMBO, name, label,
                        selectedValue(choices), choices, false, false, disabled, required, 0);
            }
            default -> {
                String value = typed(e, kind);
                String hint = e.attr("placeholder").strip();
                FormField.Kind k = kind.equals("textarea") ? FormField.Kind.MULTILINE
                        : t.equals("password") ? FormField.Kind.PASSWORD : FormField.Kind.TEXT;
                int max = digits(e.attr("maxlength")) && e.attr("maxlength").strip().length() < 9
                        ? Integer.parseInt(e.attr("maxlength").strip()) : 0;
                yield new FormField(k, name, label, k == FormField.Kind.PASSWORD ? "" : value, null, false,
                        value.isEmpty() && !hint.isEmpty(),
                        disabled || e.hasAttr("readonly"), required, max);
            }
        };
    }

    private static List<FormField.Choice> choices(Element select) {
        List<FormField.Choice> out = new ArrayList<>();
        List<Element> options = select.select("option");
        int chosen = -1;
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).hasAttr("selected")) {
                chosen = i;
            }
        }
        boolean multiple = select.hasAttr("multiple");
        if (chosen < 0 && !multiple) {
            chosen = 0;
        }
        int budget = MAX_CHOICE_CHARS;
        for (int i = 0; i < options.size() && out.size() < FormField.MAX_CHOICES && budget > 0; i++) {
            Element o = options.get(i);
            String label = cap(o.hasAttr("label") ? o.attr("label") : o.text());
            String value = cap(o.hasAttr("value") ? o.attr("value") : o.text());
            budget -= label.length() + value.length();
            boolean on = multiple ? o.hasAttr("selected") : i == chosen;
            out.add(new FormField.Choice(value, label, on));
        }
        return out;
    }

    private static String selectedValue(List<FormField.Choice> choices) {
        for (FormField.Choice c : choices) {
            if (c.selected()) {
                return c.value();
            }
        }
        return "";
    }

    private static String shownText(Element e, String kind, FormField field) {
        if (field == null) {
            if (e.normalName().equals("button")) {
                return cap(e.text());
            }
            if (e.hasAttr("value")) {
                return cap(e.attr("value"));
            }
            return switch (type(e)) {
                case "submit" -> "Submit";
                case "reset" -> "Reset";
                default -> "";
            };
        }
        return switch (field.kind()) {
            case CHECKBOX -> field.checked() ? "✓" : "";
            case RADIO -> field.checked() ? "●" : "";
            case PASSWORD -> {
                String typed = typed(e, kind);
                yield field.placeholderShown() ? e.attr("placeholder").strip()
                        : "•".repeat(typed.codePointCount(0, typed.length()));
            }
            case COMBO -> {
                for (FormField.Choice c : field.choices()) {
                    if (c.selected()) {
                        yield c.label();
                    }
                }
                yield "";
            }
            default -> field.placeholderShown() ? e.attr("placeholder").strip() : field.value();
        };
    }

    private static void lines(Paragraph p, String text, RunStyle style) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                p.inlines.add(new Inline.Break());
            }
            if (!lines[i].isEmpty()) {
                p.inlines.add(new Inline.Text(lines[i], style, null, -1));
            }
        }
    }

    private static void listLines(Paragraph p, FormField field, RunStyle style) {
        RunStyle chosen = new RunStyle(style.font(), style.size(), style.bold(), style.italic(), style.underline(),
                style.strike(), style.rgb(), SELECTED_GREY, 0, false, style.spacing(), 100, style.smallCaps());
        for (FormField.Choice c : field.choices()) {
            if (!p.inlines.isEmpty()) {
                p.inlines.add(new Inline.Break());
            }
            if (!c.label().isEmpty()) {
                p.inlines.add(new Inline.Text(c.label(), c.selected() ? chosen : style, null, -1));
            }
        }
    }

    private static RunStyle withColor(RunStyle s, int rgb) {
        return new RunStyle(s.font(), s.size(), s.bold(), s.italic(), s.underline(), s.strike(), rgb, s.highlight(),
                s.vertAlign(), s.symbol(), s.spacing(), s.scale(), s.smallCaps());
    }

    private static String typed(Element e, String kind) {
        return cap(kind.equals("textarea") ? textareaText(e) : e.attr("value").replaceAll("[\r\n]", ""));
    }

    private static String textareaText(Element e) {
        StringBuilder sb = new StringBuilder();
        for (Node n : e.childNodes()) {
            if (n instanceof TextNode t) {
                sb.append(t.getWholeText());
            }
        }
        return sb.toString().replace("\r\n", "\n").replace('\r', '\n');
    }

    static String label(Element e, Element owner) {
        for (Element p = e.parent(); owner == null && p != null; p = p.parent()) {
            if (p.normalName().equals("label")) {
                owner = p;
            }
        }
        if (owner != null) {
            String text = ownText(owner, new StringBuilder(), 0).toString().replaceAll("\\s+", " ").strip();
            if (!text.isEmpty()) {
                return cap(text);
            }
        }
        for (String attr : new String[] {"title", "placeholder", "name"}) {
            String v = e.attr(attr).strip();
            if (!v.isEmpty()) {
                return cap(v);
            }
        }
        return "";
    }

    private static StringBuilder ownText(Element e, StringBuilder sb, int depth) {
        if (depth > 32) {
            return sb;
        }
        for (Node n : e.childNodes()) {
            if (n instanceof TextNode t) {
                sb.append(t.getWholeText());
            } else if (n instanceof Element c && !TAGS.contains(c.normalName()) && !Flow.SKIPPED.contains(c.normalName())) {
                ownText(c, sb, depth + 1);
            }
        }
        return sb;
    }

    private static float side(Map<String, String> d, String key, float em, float room, float fallback) {
        String v = d.get(key);
        if (v == null) {
            return fallback;
        }
        Float pt = Css.length(v, em, room);
        return pt == null ? fallback : clamp(pt, 0);
    }

    private static Table.Border edge(Map<String, String> d, String side) {
        if (!d.containsKey("border-" + side) && !d.containsKey("border-" + side + "-style")
                && !d.containsKey("border-" + side + "-width") && !d.containsKey("border-" + side + "-color")) {
            return new Table.Border(0.75f, 0);
        }
        Map<String, String> sides = new HashMap<>(d);
        if (!d.containsKey("border-" + side) && !d.containsKey("border-" + side + "-style")) {
            sides.put("border-" + side + "-style", "solid");
        }
        return HtmlTables.border(sides, side, 0);
    }

    private static Table.Border strongest(Table.Border[] borders) {
        Table.Border best = Table.Border.NONE;
        for (Table.Border b : borders) {
            if (b.width() > best.width()) {
                best = b;
            }
        }
        return best;
    }

    private static Float cssLength(String v, float em, float room) {
        if (v == null) {
            return null;
        }
        String t = v.strip().toLowerCase(Locale.ROOT);
        if (t.isEmpty() || t.equals("auto") || t.contains("content") || t.startsWith("calc")) {
            return null;
        }
        return Css.length(t, em, room);
    }

    private static float textWidth(String text, float em) {
        float widest = 0;
        for (String line : text.split("\n")) {
            widest = Math.max(widest, line.codePointCount(0, line.length()) * 0.55f * em);
        }
        return widest;
    }

    static float linePitch(Look look) {
        return look.linePoints > 0 ? look.linePoints : look.lineMultiple * look.size;
    }

    private static float lines(String text, Look look, float width) {
        float pitch = linePitch(look) > 0 ? linePitch(look) : 1.2f * look.size;
        float space = labelWidth(" ", look);
        int lines = 0;
        for (String line : text.split("\n")) {
            lines++;
            float used = 0;
            for (String word : line.strip().split(" +")) {
                float w = labelWidth(word, look);
                if (used > 0 && used + space + w > width) {
                    lines++;
                    used = 0;
                }
                if (used == 0 && w > width) {
                    lines += (int) Math.ceil(w / width) - 1;
                    used = width;
                } else {
                    used += (used > 0 ? space : 0) + w;
                }
            }
        }
        return Math.min(MAX_SIDE / pitch, Math.max(1, lines)) * pitch;
    }

    private static float labelWidth(String text, Look look) {
        float widest = 0;
        for (String line : text.split("\n")) {
            float measured = Glyphs.measured(line, look.size, look.font, look.bold, look.italic);
            widest = Math.max(widest, measured > 0 ? measured + look.spacing * line.codePointCount(0, line.length())
                    : Math.max(textWidth(line, look.size), drawnWidth(line, look)));
        }
        return widest;
    }

    private static float drawnWidth(String text, Look look) {
        java.awt.Font f = new java.awt.Font(look.font, look.bold ? java.awt.Font.BOLD : java.awt.Font.PLAIN, 1)
                .deriveFont(look.size);
        java.awt.font.FontRenderContext frc = new java.awt.font.FontRenderContext(null, true, true);
        float widest = 0;
        for (String line : text.split("\n")) {
            widest = Math.max(widest, (float) f.getStringBounds(line, frc).getWidth());
        }
        return widest * 1.05f;
    }

    private static boolean digits(String v) {
        String s = v.strip();
        return !s.isEmpty() && s.length() < 6 && s.chars().allMatch(Character::isDigit) && Integer.parseInt(s) > 0;
    }

    private static float clamp(float v, float least) {
        return Float.isFinite(v) ? Math.max(least, Math.min(MAX_SIDE, v)) : least;
    }

    private static String cap(String s) {
        return s.length() > MAX_VALUE_CHARS ? s.substring(0, MAX_VALUE_CHARS) : s;
    }
}
