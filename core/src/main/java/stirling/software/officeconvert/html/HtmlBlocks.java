package stirling.software.officeconvert.html;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.StyleSheet;
import stirling.software.officeconvert.model.Table;
import stirling.software.officeconvert.sink.Links;
import stirling.software.officeconvert.sink.MediaStore;

/** Paragraphs and tables as flowing HTML: floating pictures and text boxes join the flow next to their paragraph. */
final class HtmlBlocks {

    static final char MARK = '\0';

    // Small repeated pictures (logos, icons) are encoded once
    private static final int CACHED_URI_CHARS = 64 << 10;

    private static final float HALF_PAGE = 297f;

    private final MediaStore media;
    private final Map<String, String> dataUris = new HashMap<>();
    private StyleSheet styles;

    HtmlBlocks(MediaStore media) {
        this.media = media;
    }

    void styles(StyleSheet styles) {
        this.styles = styles;
    }

    static String styleClass(String id) {
        return "s-" + id.replaceAll("[^A-Za-z0-9_-]", "");
    }

    void paragraph(StringBuilder sb, Paragraph p) throws IOException {
        List<Inline> before = new ArrayList<>();
        List<Inline> after = new ArrayList<>();
        for (Inline in : p.inlines) {
            if (floating(in)) {
                (below(in, p) ? after : before).add(in);
            }
        }
        floats(sb, before);
        String tag = tag(p.style);
        sb.append('<').append(tag);
        if (p.bookmark != null) {
            sb.append(" id=\"");
            HtmlText.escape(sb, p.bookmark);
            sb.append('"');
        }
        if (!"Normal".equals(p.style)) {
            sb.append(" class=\"").append(styleClass(p.style)).append('"');
        }
        if (p.bidi) {
            sb.append(" dir=\"rtl\"");
        }
        String css = paragraphCss(p);
        if (!css.isEmpty()) {
            sb.append(" style=\"").append(css).append('"');
        }
        sb.append('>');
        if (p.list != null) {
            sb.append("<span class=\"marker\">").append(MARK).append('L').append(p.list.numId()).append(':')
                    .append(p.list.level()).append(MARK).append("</span>");
        }
        int start = sb.length();
        runs(sb, p, base(p));
        if (sb.length() == start && p.list == null) {
            sb.append("<br>");
        }
        sb.append("</").append(tag).append(">\n");
        floats(sb, after);
    }

    private RunStyle base(Paragraph p) {
        return styles.get(p.style).run();
    }

    private static String tag(String style) {
        if (style.matches("Heading[1-6]")) {
            return "h" + style.charAt(7);
        }
        return "Title".equals(style) ? "h1" : "p";
    }

    private static String paragraphCss(Paragraph p) {
        StringBuilder sb = new StringBuilder();
        if (p.align != Paragraph.Align.LEFT) {
            sb.append("text-align:").append(p.align.name().toLowerCase(java.util.Locale.ROOT)).append(';');
        }
        length(sb, "margin-left", p.indentLeft);
        length(sb, "margin-right", p.indentRight);
        length(sb, "text-indent", p.indentFirst);
        length(sb, "margin-top", p.spaceBefore);
        length(sb, "margin-bottom", p.spaceAfter);
        if (p.lineRule != Paragraph.LineRule.AUTO && p.lineHeight > 0) {
            sb.append(p.lineRule == Paragraph.LineRule.EXACT ? "line-height:" : "min-height:")
                    .append(HtmlText.pt(p.lineHeight)).append(';');
        }
        if (p.shading >= 0) {
            sb.append("background-color:").append(HtmlText.hex(p.shading)).append(';');
        }
        if (p.borderBottom > 0) {
            sb.append("border-bottom:").append(HtmlText.pt(p.borderBottom)).append(" solid ")
                    .append(HtmlText.hex(p.borderBottomRgb)).append(';');
        }
        if (p.pageBreakBefore) {
            sb.append("break-before:page;");
        }
        return sb.toString();
    }

    private static void length(StringBuilder sb, String name, float v) {
        if (Math.abs(v) >= 0.05f) {
            sb.append(name).append(':').append(HtmlText.pt(v)).append(';');
        }
    }

    private void runs(StringBuilder sb, Paragraph p, RunStyle base) throws IOException {
        List<Inline> list = p.inlines;
        for (int i = 0; i < list.size(); i++) {
            Inline in = list.get(i);
            switch (in) {
                case Inline.Text t -> {
                    StringBuilder text = new StringBuilder(t.text());
                    while (i + 1 < list.size() && list.get(i + 1) instanceof Inline.Text n
                            && Objects.equals(n.style(), t.style()) && Objects.equals(n.link(), t.link())
                            && n.anchorPage() == t.anchorPage()) {
                        text.append(n.text());
                        i++;
                    }
                    run(sb, text.toString(), t.style(), base, Links.target(t));
                }
                case Inline.Tab tab -> sb.append('\t');
                case Inline.Break br -> sb.append("<br>");
                case Inline.PageBreak br -> sb.append("<br class=\"page-break\">");
                case Inline.Image im -> {
                    if (!floating(in)) {
                        image(sb, im.picture(), true);
                    }
                }
                case Inline.FootnoteRef ref -> sb.append("<sup><a class=\"footnote-ref\" href=\"#fn-").append(ref.id())
                        .append("\" id=\"fnref-").append(ref.id()).append("\">").append(escaped(ref.marker()))
                        .append("</a></sup>");
                case Inline.FootnoteMark mark -> sb.append("<sup>").append(escaped(mark.marker())).append("</sup> ");
                default -> { }
            }
        }
    }

    private static String escaped(String s) {
        StringBuilder sb = new StringBuilder();
        HtmlText.escape(sb, s == null ? "" : s);
        return sb.toString();
    }

    private static void run(StringBuilder sb, String text, RunStyle s, RunStyle base, String href) {
        if (text.isEmpty()) {
            return;
        }
        List<String> close = new ArrayList<>();
        if (href != null) {
            sb.append("<a href=\"");
            HtmlText.escape(sb, href);
            sb.append("\">");
            close.add("</a>");
        }
        if (s != null) {
            String css = HtmlText.diff(s, base);
            if (!css.isEmpty()) {
                sb.append("<span style=\"").append(css).append("\">");
                close.add("</span>");
            }
            open(sb, close, s.bold() && !base.bold(), "b");
            open(sb, close, s.italic() && !base.italic(), "i");
            open(sb, close, s.underline() && href == null, "u");
            open(sb, close, s.strike(), "s");
            open(sb, close, s.vertAlign() > 0, "sup");
            open(sb, close, s.vertAlign() < 0, "sub");
        }
        HtmlText.escape(sb, text);
        for (int i = close.size() - 1; i >= 0; i--) {
            sb.append(close.get(i));
        }
    }

    private static void open(StringBuilder sb, List<String> close, boolean on, String tag) {
        if (on) {
            sb.append('<').append(tag).append('>');
            close.add("</" + tag + ">");
        }
    }

    private static boolean floating(Inline in) {
        if (in instanceof Inline.Image im) {
            return im.picture().wrap != Picture.Wrap.INLINE;
        }
        return in instanceof Inline.TextBox;
    }

    private static boolean below(Inline in, Paragraph p) {
        if (Float.isNaN(p.sourceBottom)) {
            return false;
        }
        if (in instanceof Inline.TextBox t) {
            return t.y() >= p.sourceBottom;
        }
        return in instanceof Inline.Image im && !im.picture().fromParagraph && im.picture().y >= p.sourceBottom;
    }

    private void floats(StringBuilder sb, List<Inline> inlines) throws IOException {
        for (Inline in : inlines) {
            if (in instanceof Inline.Image im && im.picture().wrap == Picture.Wrap.SQUARE && !im.picture().backdrop) {
                // Text wraps a picture in the PDF, so it floats on the side of the page it sat on
                boolean right = im.picture().x + im.picture().width / 2 > HALF_PAGE;
                sb.append(right ? "<div class=\"float-right\">" : "<div class=\"float-left\">");
                image(sb, im.picture(), false);
                sb.append("</div>\n");
            } else if (in instanceof Inline.Image im && !im.picture().backdrop) {
                sb.append("<p class=\"figure\">");
                image(sb, im.picture(), false);
                sb.append("</p>\n");
            } else if (in instanceof Inline.TextBox box && hasText(box.paragraphs())) {
                sb.append("<div class=\"box\"");
                StringBuilder css = new StringBuilder();
                if (box.fillRgb() >= 0) {
                    css.append("background-color:").append(HtmlText.hex(box.fillRgb())).append(';');
                }
                if (box.lineRgb() >= 0 && box.lineWidth() > 0) {
                    css.append("border:").append(HtmlText.pt(box.lineWidth())).append(" solid ")
                            .append(HtmlText.hex(box.lineRgb())).append(';');
                }
                if (box.rounded()) {
                    css.append("border-radius:6pt;");
                }
                if (!css.isEmpty()) {
                    sb.append(" style=\"").append(css).append('"');
                }
                sb.append(">\n");
                for (Paragraph bp : box.paragraphs()) {
                    paragraph(sb, bp);
                }
                sb.append("</div>\n");
            }
        }
    }

    private static boolean hasText(List<Paragraph> paragraphs) {
        for (Paragraph p : paragraphs) {
            for (Inline in : p.inlines) {
                if (in instanceof Inline.Text t && !t.text().isBlank() || in instanceof Inline.Image) {
                    return true;
                }
            }
        }
        return false;
    }

    private void image(StringBuilder sb, Picture pic, boolean inline) throws IOException {
        Picture.MediaRef ref = media.shaped(pic);
        String uri = dataUris.get(ref.name());
        if (uri == null) {
            uri = "data:" + ref.contentType() + ";base64," + Base64.getEncoder().encodeToString(media.bytes(ref));
            if (uri.length() <= CACHED_URI_CHARS) {
                dataUris.put(ref.name(), uri);
            }
        }
        sb.append("<img src=\"").append(uri).append("\" alt=\"");
        HtmlText.escape(sb, pic.description == null ? "" : pic.description);
        sb.append("\" style=\"width:").append(HtmlText.pt(pic.width)).append(";height:").append(HtmlText.pt(pic.height));
        if (inline && Math.abs(pic.baselineShift) >= 0.5f) {
            sb.append(";vertical-align:").append(HtmlText.pt(-pic.baselineShift));
        }
        sb.append("\">");
    }

    void table(StringBuilder sb, Table t) throws IOException {
        float width = 0;
        for (float w : t.columnWidths) {
            width += w;
        }
        sb.append("<table");
        if (t.rightToLeft) {
            sb.append(" dir=\"rtl\"");
        }
        sb.append(" style=\"width:").append(HtmlText.pt(width)).append(';');
        length(sb, "margin-left", t.indent);
        if (t.pageBreakBefore) {
            sb.append("break-before:page;");
        }
        sb.append("\"><colgroup>");
        for (float w : t.columnWidths) {
            sb.append("<col style=\"width:").append(HtmlText.pt(w)).append("\">");
        }
        sb.append("</colgroup>\n");
        for (int r = 0; r < t.rows.size(); r++) {
            Table.Row row = t.rows.get(r);
            sb.append("<tr");
            if (row.height > 0) {
                sb.append(" style=\"height:").append(HtmlText.pt(row.height)).append('"');
            }
            sb.append('>');
            int col = 0;
            for (Table.Cell cell : row.cells) {
                if (cell.vMerge != 2) {
                    cell(sb, t, r, col, cell, row.header);
                }
                col += Math.max(1, cell.gridSpan);
            }
            sb.append("</tr>\n");
        }
        sb.append("</table>\n");
    }

    private void cell(StringBuilder sb, Table t, int r, int col, Table.Cell cell, boolean header) throws IOException {
        String tag = header ? "th" : "td";
        sb.append('<').append(tag);
        if (cell.gridSpan > 1) {
            sb.append(" colspan=\"").append(cell.gridSpan).append('"');
        }
        int span = cell.vMerge == 1 ? rowSpan(t, r, col) : 1;
        if (span > 1) {
            sb.append(" rowspan=\"").append(span).append('"');
        }
        sb.append(" style=\"");
        border(sb, "top", cell.top);
        border(sb, "bottom", cell.bottom);
        border(sb, "left", cell.left);
        border(sb, "right", cell.right);
        if (cell.shading >= 0) {
            sb.append("background-color:").append(HtmlText.hex(cell.shading)).append(';');
        }
        if (cell.vAlign != Table.VAlign.TOP) {
            sb.append("vertical-align:").append(cell.vAlign == Table.VAlign.CENTER ? "middle" : "bottom").append(';');
        }
        sb.append("padding:0 ").append(HtmlText.pt(t.cellMarginRight)).append(" 0 ").append(HtmlText.pt(t.cellMarginLeft))
                .append("\">");
        for (Paragraph p : cell.paragraphs) {
            paragraph(sb, p);
        }
        sb.append("</").append(tag).append('>');
    }

    private static void border(StringBuilder sb, String side, Table.Border b) {
        if (b != null && b.visible()) {
            sb.append("border-").append(side).append(':').append(HtmlText.pt(b.width())).append(" solid ")
                    .append(HtmlText.hex(b.rgb())).append(';');
        }
    }

    private static int rowSpan(Table t, int r, int col) {
        int span = 1;
        for (int next = r + 1; next < t.rows.size(); next++) {
            Table.Cell below = cellAt(t.rows.get(next), col);
            if (below == null || below.vMerge != 2) {
                break;
            }
            span++;
        }
        return span;
    }

    private static Table.Cell cellAt(Table.Row row, int col) {
        int at = 0;
        for (Table.Cell c : row.cells) {
            if (at == col) {
                return c;
            }
            at += Math.max(1, c.gridSpan);
            if (at > col) {
                return null;
            }
        }
        return null;
    }
}
