package stirling.software.officeconvert.odp;

import java.util.List;
import java.util.Set;

import stirling.software.officeconvert.layout.Marker;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.Paragraph.Align;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.Scripts;
import stirling.software.officeconvert.slides.Bullet;

final class OdpText {

    private final OdpStyles styles;
    private final Links links;

    interface Links {
        int slideOf(int page);
    }

    OdpText(OdpStyles styles, Links links) {
        this.styles = styles;
        this.links = links;
    }

    void paragraph(StringBuilder sb, Paragraph content, Align align, float marginLeft, float indent, float marginRight,
            float lineHeight, float spaceBefore, Bullet bullet, Set<Integer> linkLines) {
        float left = bullet != null ? marginLeft + Math.min(0, indent) : marginLeft;
        float first = bullet != null ? Math.max(0, indent) : indent;
        StringBuilder props = new StringBuilder("<style:paragraph-properties");
        props.append(" fo:margin-left=\"").append(Odf.cm(Math.max(0, left))).append("\" fo:margin-right=\"")
                .append(Odf.cm(marginRight)).append("\" fo:text-indent=\"").append(Odf.cm(first))
                .append("\" fo:margin-top=\"").append(Odf.cm(spaceBefore)).append("\" fo:margin-bottom=\"0cm\"");
        if (lineHeight > 0) {
            props.append(" fo:line-height=\"").append(Odf.cm(lineHeight)).append('"');
        }
        props.append(" fo:text-align=\"").append(align(align, content.bidi)).append('"');
        if (content.bidi) {
            props.append(" style:writing-mode=\"rl-tb\"");
        }
        if (content.noHangingPunctuation) {
            props.append(" style:punctuation-wrap=\"simple\"");
        }
        props.append('>');
        tabs(props, content.tabs);
        props.append("</style:paragraph-properties>");
        String style = styles.style("paragraph", "P", props.toString());
        if (bullet != null) {
            sb.append("<text:list text:style-name=\"").append(list(bullet, indent, contentSize(content))).append("\">")
                    .append("<text:list-item");
            if (bullet.numbered() && bullet.marker().value() != 1) {
                sb.append(" text:start-value=\"").append(bullet.marker().value()).append('"');
            }
            sb.append('>');
        }
        sb.append("<text:p text:style-name=\"").append(style).append("\">");
        runs(sb, content.inlines, linkLines);
        sb.append("</text:p>");
        if (bullet != null) {
            sb.append("</text:list-item></text:list>");
        }
    }

    private static String align(Align a, boolean rtl) {
        return switch (a) {
            case CENTER -> "center";
            case RIGHT -> rtl ? "start" : "end";
            case JUSTIFY -> "justify";
            default -> rtl ? "end" : "start";
        };
    }

    private static float contentSize(Paragraph p) {
        for (Inline in : p.inlines) {
            if (in instanceof Inline.Text t) {
                return t.style().size();
            }
        }
        return 0;
    }

    private String list(Bullet b, float indent, float textSize) {
        RunStyle s = b.style();
        Marker m = b.marker();
        StringBuilder level = new StringBuilder();
        String text;
        if (b.numbered()) {
            level.append("<text:list-level-style-number text:level=\"1\" style:num-format=\"").append(format(m))
                    .append('"');
            if (!m.prefix().isEmpty()) {
                level.append(" style:num-prefix=\"").append(Odf.esc(m.prefix())).append('"');
            }
            level.append(" style:num-suffix=\"").append(Odf.esc(m.suffix())).append("\">");
            text = "</text:list-level-style-number>";
        } else {
            String c = m.text().substring(0, Character.isHighSurrogate(m.text().charAt(0)) && m.text().length() > 1 ? 2 : 1);
            level.append("<text:list-level-style-bullet text:level=\"1\" text:bullet-char=\"").append(Odf.esc(c)).append("\">");
            text = "</text:list-level-style-bullet>";
        }
        level.append("<style:list-level-properties text:space-before=\"0cm\" text:min-label-width=\"")
                .append(Odf.cm(Math.max(0, -indent))).append("\"/><style:text-properties");
        if (s.font() != null) {
            level.append(" fo:font-family=\"").append(Odf.esc(s.font())).append('"');
            if (s.symbol() || isPrivateUse(m.text())) {
                level.append(" style:font-charset=\"x-symbol\"");
            }
        }
        if (s.rgb() >= 0) {
            level.append(" fo:color=\"").append(Odf.colour(s.rgb())).append("\" style:use-window-font-color=\"false\"");
        }
        if (textSize > 0) {
            level.append(" fo:font-size=\"").append(Math.clamp(Math.round(100 * s.size() / textSize), 25, 400)).append("%\"");
        }
        level.append("/>").append(text);
        return styles.list(level.toString());
    }

    private static String format(Marker m) {
        return Numbering.odfFormat(m.wordFormat());
    }

    private static void tabs(StringBuilder sb, List<Paragraph.TabStop> tabs) {
        if (tabs.isEmpty()) {
            return;
        }
        sb.append("<style:tab-stops>");
        for (Paragraph.TabStop t : tabs) {
            String kind = switch (t.kind()) {
                case CENTER -> "center";
                case RIGHT -> "right";
                case DECIMAL -> "char";
                default -> "left";
            };
            sb.append("<style:tab-stop style:position=\"").append(Odf.cm(t.pos())).append("\" style:type=\"").append(kind)
                    .append('"');
            if (t.kind() == Paragraph.TabStop.Kind.DECIMAL) {
                sb.append(" style:char=\".\"");
            }
            if (t.leader() != 0) {
                sb.append(" style:leader-style=\"").append(t.leader() == '_' ? "solid" : "dotted").append("\" style:leader-text=\"")
                        .append(Odf.esc(String.valueOf(t.leader()))).append('"');
            }
            sb.append("/>");
        }
        sb.append("</style:tab-stops>");
    }

    void runs(StringBuilder sb, List<Inline> inlines, Set<Integer> linkLines) {
        for (int i = 0; i < inlines.size(); i++) {
            Inline in = inlines.get(i);
            if (i > 0 && linkLines.contains(i)) {
                sb.append("<text:line-break/>");
            }
            switch (in) {
                case Inline.Text t -> span(sb, t.text(), t.style(), t.link(), t.anchorPage());
                case Inline.Tab t -> sb.append("<text:tab/>");
                case Inline.Break b -> sb.append("<text:line-break/>");
                case Inline.FootnoteRef f -> span(sb, f.marker(), f.style().withVertAlign(1), null, -1);
                case Inline.FootnoteMark f -> span(sb, f.marker(), f.style().withVertAlign(1), null, -1);
                case Inline.PageNumber n -> span(sb, "#", n.style(), null, -1);
                default -> {
                }
            }
        }
    }

    private void span(StringBuilder sb, String text, RunStyle style, String link, int anchorPage) {
        if (text.isEmpty() || style == null) {
            return;
        }
        String href = link != null ? stirling.software.officeconvert.sink.Links.safeUrl(link) : null;
        if (href == null && anchorPage >= 0) {
            int slide = links.slideOf(anchorPage);
            href = slide > 0 ? "#page" + slide : null;
        }
        sb.append("<text:span text:style-name=\"").append(styles.style("text", "T", textProperties(style, text))).append("\">");
        if (href != null) {
            sb.append("<text:a xlink:type=\"simple\" xlink:href=\"").append(Odf.esc(href)).append("\">");
        }
        spaces(sb, text);
        if (href != null) {
            sb.append("</text:a>");
        }
        sb.append("</text:span>");
    }

    private static void spaces(StringBuilder sb, String text) {
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == ' ' && (i == 0 || text.charAt(i - 1) == ' ')) {
                int run = 0;
                while (i < n && text.charAt(i) == ' ') {
                    run++;
                    i++;
                }
                sb.append(run == 1 ? "<text:s/>" : "<text:s text:c=\"" + run + "\"/>");
                continue;
            }
            if (c == '\t') {
                sb.append("<text:tab/>");
            } else {
                Odf.text(sb, String.valueOf(c));
            }
            i++;
        }
    }

    String textProperties(RunStyle s) {
        return textProperties(s, null);
    }

    String textProperties(RunStyle s, String text) {
        StringBuilder p = new StringBuilder("<style:text-properties");
        if (s.font() != null) {
            Scripts.Fonts slots = Scripts.fonts(s.font(), text, styles.scripts);
            p.append(" style:font-name=\"").append(Odf.esc(styles.font(slots.latin()))).append("\" style:font-name-asian=\"")
                    .append(Odf.esc(styles.font(slots.eastAsian()))).append("\" style:font-name-complex=\"")
                    .append(Odf.esc(styles.font(slots.complex()))).append('"');
        }
        p.append(Scripts.odfLanguages(Scripts.languages(text, styles.scripts)));
        String size = Odf.pt(s.size());
        p.append(" fo:font-size=\"").append(size).append("\" style:font-size-asian=\"").append(size)
                .append("\" style:font-size-complex=\"").append(size).append('"');
        String weight = s.bold() ? "bold" : "normal";
        String slant = s.italic() ? "italic" : "normal";
        p.append(" fo:font-weight=\"").append(weight).append("\" style:font-weight-asian=\"").append(weight)
                .append("\" style:font-weight-complex=\"").append(weight).append("\" fo:font-style=\"").append(slant)
                .append("\" style:font-style-asian=\"").append(slant).append("\" style:font-style-complex=\"").append(slant)
                .append('"');
        p.append(" fo:color=\"").append(Odf.colour(Math.max(0, s.rgb()))).append('"');
        if (s.underline()) {
            p.append(" style:text-underline-style=\"solid\" style:text-underline-width=\"auto\" style:text-underline-color=\"font-color\"");
        }
        if (s.strike()) {
            p.append(" style:text-line-through-style=\"solid\"");
        }
        if (s.smallCaps()) {
            p.append(" fo:font-variant=\"small-caps\"");
        }
        if (Math.abs(s.spacing()) >= 0.01f) {
            p.append(" fo:letter-spacing=\"").append(Odf.pt(s.spacing())).append('"');
        }
        if (s.scale() != 100) {
            p.append(" style:text-scale=\"").append(s.scale()).append("%\"");
        }
        if (s.vertAlign() != 0) {
            p.append(" style:text-position=\"").append(s.vertAlign() > 0 ? "super" : "sub").append(" 58%\"");
        }
        if (s.highlight() >= 0) {
            p.append(" fo:background-color=\"").append(Odf.colour(s.highlight())).append('"');
        }
        return p.append("/>").toString();
    }

    private static boolean isPrivateUse(String s) {
        return !s.isEmpty() && s.charAt(0) >= 0xE000 && s.charAt(0) <= 0xF8FF;
    }
}
