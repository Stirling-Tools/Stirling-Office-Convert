package stirling.software.officeconvert.odt;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.StyleSheet;
import stirling.software.officeconvert.sink.Borders;
import stirling.software.officeconvert.sink.Links;
import stirling.software.officeconvert.sink.NoteHold;

final class OdtBody {

    private static final int MAX_TABS = 64;

    private static final float JUSTIFY_ROOM = 3f;

    enum Place {
        FLOW,
        NESTED,
        NOTE
    }

    private final OdtStyles styles;
    private final StyleSheet sheet;
    private final OdtFrames frames;
    private final NoteHold notes;
    private final OdtLists lists;
    private final OdtTables tables;

    OdtBody(OdtStyles styles, StyleSheet sheet, OdtMedia media, NoteHold notes, OdtLists lists) {
        this.styles = styles;
        this.sheet = sheet;
        this.notes = notes;
        this.lists = lists;
        this.frames = new OdtFrames(styles, media, this);
        this.tables = new OdtTables(styles, this);
    }

    OdtTables tables() {
        return tables;
    }

    static String styleName(String id) {
        if (id.startsWith("Heading")) {
            return "Heading_20_" + id.substring(7);
        }
        return switch (id) {
            case "Normal" -> "Standard";
            case "ListParagraph" -> "List_20_Paragraph";
            default -> id.replaceAll("[^A-Za-z0-9_]", "_");
        };
    }

    String paragraph(StringBuilder sb, Paragraph p, Place place, boolean own) throws IOException {
        int cut = place == Place.FLOW ? p.inlines.indexOf(new Inline.PageBreak()) : -1;
        String name = written(sb, p, cut < 0 ? p.inlines : p.inlines.subList(0, cut), place, own);
        if (cut >= 0) {
            paragraph(sb, rest(p, cut), place, false);
        }
        return name;
    }

    private String written(StringBuilder sb, Paragraph p, List<Inline> inlines, Place place, boolean own) throws IOException {
        StyleSheet.Style style = sheet.get(p.style);
        RunStyle base = style.run();
        if (place == Place.FLOW) {
            if (p.list != null) {
                lists.item(sb, p.list.numId(), p.list.level());
            } else {
                lists.close(sb);
            }
        }
        String inner = paragraphProps(p, style, place) + markProps(p, base);
        String parent = place == Place.NOTE && "Normal".equals(p.style) ? "Footnote" : styleName(p.style);
        String name = own ? styles.own("paragraph", parent, inner) : styles.get("paragraph", parent, inner);
        int outline = place == Place.FLOW ? style.outlineLevel() : -1;
        if (outline >= 0) {
            sb.append("<text:h text:style-name=\"").append(name).append("\" text:outline-level=\"")
                    .append(Math.min(10, outline + 1)).append("\">");
        } else {
            sb.append("<text:p text:style-name=\"").append(name).append("\">");
        }
        if (p.bookmark != null) {
            sb.append("<text:bookmark text:name=\"").append(OdtXml.esc(p.bookmark)).append("\"/>");
        }
        inlines(sb, inlines, base);
        sb.append(outline >= 0 ? "</text:h>" : "</text:p>");
        return own ? name : null;
    }

    private static Paragraph rest(Paragraph p, int cut) {
        Paragraph r = new Paragraph();
        r.inlines.addAll(p.inlines.subList(cut + 1, p.inlines.size()));
        r.tabs.addAll(p.tabs);
        r.style = p.style;
        r.align = p.align;
        r.indentLeft = p.indentLeft;
        r.indentRight = p.indentRight;
        r.spaceAfter = p.spaceAfter;
        r.lineHeight = p.lineHeight;
        r.lineRule = p.lineRule;
        r.bidi = p.bidi;
        r.shading = p.shading;
        r.borderBottom = p.borderBottom;
        r.borderBottomRgb = p.borderBottomRgb;
        r.markStyle = p.markStyle;
        r.pageBreakBefore = true;
        return r;
    }

    private String paragraphProps(Paragraph p, StyleSheet.Style style, Place place) {
        StringBuilder a = new StringBuilder();
        float physicalRight = p.indentRight - (p.align == Paragraph.Align.JUSTIFY && hasBreak(p) ? JUSTIFY_ROOM : 0);
        float left = p.bidi ? physicalRight : p.indentLeft;
        float right = p.bidi ? p.indentLeft : physicalRight;
        if (left != 0 || p.list != null) {
            a.append(" fo:margin-left=\"").append(OdtXml.pt(left)).append('"');
        }
        if (right != 0) {
            a.append(" fo:margin-right=\"").append(OdtXml.pt(right)).append('"');
        }
        if (p.indentFirst != 0 || p.list != null) {
            a.append(" fo:text-indent=\"").append(OdtXml.pt(p.indentFirst)).append('"');
        }
        a.append(" fo:margin-top=\"").append(OdtXml.pt(clamp(p.spaceBefore))).append("\" fo:margin-bottom=\"")
                .append(OdtXml.pt(clamp(p.spaceAfter))).append('"');
        switch (p.lineRule) {
            case EXACT -> a.append(" fo:line-height=\"").append(OdtXml.pt(Math.max(1f, p.lineHeight))).append('"');
            case AT_LEAST -> a.append(" style:line-height-at-least=\"").append(OdtXml.pt(Math.max(1f, p.lineHeight))).append('"');
            default -> a.append(" fo:line-height=\"100%\"");
        }
        String align = switch (p.align) {
            case CENTER -> "center";
            case RIGHT -> p.bidi ? "start" : "end";
            case JUSTIFY -> "justify";
            default -> p.bidi ? "end" : "start";
        };
        a.append(" fo:text-align=\"").append(align).append('"');
        if (p.align == Paragraph.Align.JUSTIFY) {
            a.append(" fo:text-align-last=\"start\"");
        }
        if (p.bidi) {
            a.append(" style:writing-mode=\"rl-tb\"");
        }
        if (p.pageBreakBefore) {
            a.append(" fo:break-before=\"page\"");
        }
        if (!p.inlines.isEmpty() && p.inlines.stream().anyMatch(in -> in instanceof Inline.ColumnBreak)) {
            a.append(" fo:break-after=\"column\"");
        }
        if (p.keepNext) {
            a.append(" fo:keep-with-next=\"always\"");
        } else if (style.keepNext() && place == Place.FLOW) {
            a.append(" fo:keep-with-next=\"auto\" fo:keep-together=\"auto\"");
        }
        if (p.shading >= 0) {
            a.append(" fo:background-color=\"").append(OdtXml.colour(p.shading)).append('"');
        }
        if (p.borderBottom > 0) {
            a.append(" fo:border-bottom=\"").append(OdtXml.pt(Borders.width(p.borderBottom))).append(" solid ")
                    .append(OdtXml.colour(p.borderBottomRgb)).append("\" fo:padding-bottom=\"1pt\"");
        }
        String tabs = tabStops(p);
        return tabs.isEmpty() ? "<style:paragraph-properties" + a + "/>"
                : "<style:paragraph-properties" + a + ">" + tabs + "</style:paragraph-properties>";
    }

    private static String tabStops(Paragraph p) {
        if (p.tabs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<style:tab-stops>");
        for (Paragraph.TabStop t : p.tabs.subList(0, Math.min(p.tabs.size(), MAX_TABS))) {
            sb.append("<style:tab-stop style:position=\"").append(OdtXml.pt(t.pos() - p.indentLeft)).append('"');
            switch (t.kind()) {
                case CENTER -> sb.append(" style:type=\"center\"");
                case RIGHT -> sb.append(" style:type=\"right\"");
                case DECIMAL -> sb.append(" style:type=\"char\" style:char=\".\"");
                default -> { }
            }
            if (t.leader() != 0) {
                String leader = switch (t.leader()) {
                    case '_' -> "solid";
                    case '-' -> "dash";
                    default -> "dotted";
                };
                String text = t.leader() == '_' || t.leader() == '-' ? String.valueOf(t.leader())
                        : t.leader() == '\u00B7' ? "\u00B7" : ".";
                sb.append(" style:leader-style=\"").append(leader).append("\" style:leader-text=\"").append(text).append('"');
            }
            sb.append("/>");
        }
        return sb.append("</style:tab-stops>").toString();
    }

    private String markProps(Paragraph p, RunStyle base) {
        if (p.markStyle == null || hasText(p)) {
            return "";
        }
        String t = OdtProps.text(p.markStyle, base, styles.fonts);
        return t.isEmpty() ? "" : "<style:text-properties" + t + "/>";
    }

    private static boolean hasText(Paragraph p) {
        for (Inline in : p.inlines) {
            if (in instanceof Inline.Text || in instanceof Inline.Tab || in instanceof Inline.PageNumber
                    || in instanceof Inline.FootnoteRef) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBreak(Paragraph p) {
        for (Inline in : p.inlines) {
            if (in instanceof Inline.Break) {
                return true;
            }
        }
        return false;
    }

    private static float clamp(float pt) {
        return Math.max(0, Math.min(1584, pt));
    }

    private void inlines(StringBuilder sb, List<Inline> inlines, RunStyle base) throws IOException {
        OdtText text = new OdtText();
        String openLink = null;
        for (Inline in : inlines) {
            String link = in instanceof Inline.Text t ? Links.target(t) : null;
            if (!Objects.equals(link, openLink)) {
                if (openLink != null) {
                    sb.append("</text:a>");
                }
                if (link != null) {
                    sb.append("<text:a xlink:type=\"simple\" xlink:href=\"").append(OdtXml.esc(link)).append("\">");
                }
                openLink = link;
            }
            switch (in) {
                case Inline.Text t -> span(sb, t.style(), base, () -> text.append(sb, t.text()));
                case Inline.Tab tab -> span(sb, tab.style(), base, () -> text.tab(sb));
                case Inline.Break br -> text.lineBreak(sb);
                case Inline.ColumnBreak cb -> { }
                case Inline.PageBreak pb -> { }
                case Inline.PageNumber pn -> span(sb, pn.style(), base, () -> {
                    sb.append(pn.total() ? "<text:page-count>1</text:page-count>"
                            : "<text:page-number text:select-page=\"current\">1</text:page-number>");
                    text.content();
                });
                case Inline.Image img -> frames.picture(sb, img.picture());
                case Inline.Shape shape -> frames.shape(sb, shape);
                case Inline.TextBox box -> frames.textBox(sb, box);
                case Inline.FootnoteRef ref -> {
                    note(sb, ref);
                    text.content();
                }
                case Inline.FootnoteMark mark -> { }
            }
        }
        if (openLink != null) {
            sb.append("</text:a>");
        }
    }

    private interface Content {
        void write() throws IOException;
    }

    private void span(StringBuilder sb, RunStyle style, RunStyle base, Content content) throws IOException {
        String props = style == null ? "" : OdtProps.text(style, base, styles.fonts);
        if (props.isEmpty()) {
            content.write();
            return;
        }
        sb.append("<text:span text:style-name=\"").append(styles.get("text", null, "<style:text-properties" + props + "/>"))
                .append("\">");
        content.write();
        sb.append("</text:span>");
    }

    private void note(StringBuilder sb, Inline.FootnoteRef ref) throws IOException {
        List<Paragraph> body = notes == null ? null : notes.take(ref.id());
        if (body == null) {
            RunStyle s = ref.style() == null ? sheet.normal.withVertAlign(1) : ref.style().withVertAlign(1);
            span(sb, s, sheet.normal, () -> OdtXml.esc(sb, ref.marker()));
            return;
        }
        sb.append("<text:note text:id=\"ftn").append(ref.id()).append("\" text:note-class=\"footnote\"><text:note-citation");
        if (ref.custom()) {
            sb.append(" text:label=\"").append(OdtXml.esc(ref.marker())).append('"');
        }
        sb.append('>');
        OdtXml.esc(sb, ref.marker());
        sb.append("</text:note-citation><text:note-body>");
        if (body.isEmpty()) {
            sb.append("<text:p text:style-name=\"Footnote\"/>");
        }
        for (Paragraph p : body) {
            paragraph(sb, p, Place.NOTE, false);
        }
        sb.append("</text:note-body></text:note>");
    }
}
