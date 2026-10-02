package stirling.software.officeconvert.odp;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.Table;
import stirling.software.officeconvert.slides.Frame;
import stirling.software.officeconvert.slides.LineShape;
import stirling.software.officeconvert.slides.PictureShape;
import stirling.software.officeconvert.slides.RectShape;
import stirling.software.officeconvert.slides.Slide;
import stirling.software.officeconvert.slides.SlideShape;
import stirling.software.officeconvert.slides.TableShape;
import stirling.software.officeconvert.slides.TextPara;
import stirling.software.officeconvert.slides.TextShape;

final class OdpSlideXml {

    interface Media {
        String name(Picture pic) throws IOException;
    }

    private final OdpStyles styles;
    private final OdpText text;
    private final Media media;

    OdpSlideXml(OdpStyles styles, OdpText text, Media media) {
        this.styles = styles;
        this.text = text;
        this.media = media;
    }

    void page(StringBuilder sb, Slide slide, int number) throws IOException {
        String bg = slide.background() >= 0
                ? "<style:drawing-page-properties draw:fill=\"solid\" draw:fill-color=\"" + Odf.colour(slide.background())
                        + "\" presentation:background-visible=\"true\" presentation:background-objects-visible=\"true\"/>"
                : "<style:drawing-page-properties draw:fill=\"none\" presentation:background-visible=\"true\""
                        + " presentation:background-objects-visible=\"true\"/>";
        sb.append("<draw:page draw:name=\"page").append(number).append("\" draw:style-name=\"")
                .append(styles.style("drawing-page", "dp", bg)).append("\" draw:master-page-name=\"")
                .append(master(slide.background())).append("\">");
        for (SlideShape s : slide.shapes()) {
            switch (s) {
                case TextShape t -> text(sb, t);
                case PictureShape p -> picture(sb, p);
                case RectShape r -> rect(sb, r);
                case LineShape l -> line(sb, l);
                case TableShape t -> table(sb, t);
            }
        }
        sb.append("</draw:page>");
    }

    static String master(int background) {
        return background < 0 ? "Default" : String.format(Locale.ROOT, "Bg%06X", background & 0xFFFFFF);
    }

    private static void place(StringBuilder sb, Frame f) {
        sb.append(" svg:width=\"").append(Odf.cm(Math.max(0.1f, f.width()))).append("\" svg:height=\"")
                .append(Odf.cm(Math.max(0.1f, f.height()))).append('"');
        if (f.rotation() == 0) {
            sb.append(" svg:x=\"").append(Odf.cm(f.x())).append("\" svg:y=\"").append(Odf.cm(f.y())).append('"');
            return;
        }
        double a = -Math.toRadians(f.rotation());
        double hw = f.width() / 2.0;
        double hh = f.height() / 2.0;
        double cx = f.x() + hw;
        double cy = f.y() + hh;
        double tx = cx - (hw * Math.cos(a) + hh * Math.sin(a));
        double ty = cy - (-hw * Math.sin(a) + hh * Math.cos(a));
        sb.append(String.format(Locale.ROOT, " draw:transform=\"rotate (%.6f) translate (%s %s)\"", a,
                Odf.cm((float) tx), Odf.cm((float) ty)));
    }

    private void text(StringBuilder sb, TextShape t) {
        float top = t.insetTop();
        if (!t.paras().isEmpty()) {
            TextPara first = t.paras().getFirst();
            top = t.insetTop() + first.firstBaseline() - OdpLines.baseline(first.lineHeight(), first.size());
        }
        Frame frame = t.frame();
        if (top < 0) {
            frame = new Frame(frame.x(), frame.y() + top, frame.width(), frame.height() - top, frame.rotation());
            top = 0;
        }
        StringBuilder g = new StringBuilder("<style:graphic-properties");
        fillAndStroke(g, t.fillRgb(), 1f, t.lineRgb(), t.lineWidth());
        g.append(" draw:textarea-horizontal-align=\"").append(t.wrap() ? "justify" : anchor(t))
                .append("\" draw:textarea-vertical-align=\"top\" draw:auto-grow-height=\"false\" draw:auto-grow-width=\"")
                .append(!t.wrap()).append("\" fo:wrap-option=\"").append(t.wrap() ? "wrap" : "no-wrap").append('"')
                .append(" fo:padding-top=\"").append(Odf.cm(top)).append("\" fo:padding-bottom=\"0cm\" fo:padding-left=\"")
                .append(Odf.cm(t.insetLeft())).append("\" fo:padding-right=\"").append(Odf.cm(t.insetRight()))
                .append("\" draw:shadow=\"hidden\"");
        if (t.vertical()) {
            g.append(" style:writing-mode=\"tb-rl\"");
        }

        g.append("/>");
        sb.append("<draw:frame draw:style-name=\"").append(styles.style("graphic", "gr", g.toString())).append('"');
        if (t.radius() > 0.1f) {
            sb.append(" draw:corner-radius=\"").append(Odf.cm(t.radius())).append('"');
        }
        if (t.title()) {
            sb.append(" presentation:class=\"title\" presentation:user-transformed=\"true\"");
        }
        sb.append(" draw:layer=\"layout\"");
        place(sb, frame);
        sb.append("><draw:text-box>");
        TextPara prev = null;
        for (TextPara p : t.paras()) {
            float before = 0;
            if (prev != null) {
                float prevBottom = prev.lastBaseline() + prev.lineHeight() - OdpLines.baseline(prev.lineHeight(), prev.size());
                before = Math.max(0, p.firstBaseline() - OdpLines.baseline(p.lineHeight(), p.size()) - prevBottom);
            }
            text.paragraph(sb, p.content(), p.align(), p.marginLeft(), p.indent(), p.marginRight(), p.lineHeight(), before,
                    p.bullet(), p.linkLines());
            prev = p;
        }
        sb.append("</draw:text-box></draw:frame>");
    }

    private static String anchor(TextShape t) {
        if (t.paras().isEmpty()) {
            return "left";
        }
        return switch (t.paras().getFirst().align()) {
            case CENTER -> "center";
            case RIGHT -> "right";
            default -> "left";
        };
    }

    private static void fillAndStroke(StringBuilder g, int fill, float alpha, int line, float lineWidth) {
        if (fill >= 0) {
            g.append(" draw:fill=\"solid\" draw:fill-color=\"").append(Odf.colour(fill)).append('"');
            if (alpha < 0.995f) {
                g.append(" draw:opacity=\"").append(Math.round(alpha * 100)).append("%\"");
            }
        } else {
            g.append(" draw:fill=\"none\"");
        }
        if (line >= 0 && lineWidth > 0) {
            g.append(" draw:stroke=\"solid\" svg:stroke-color=\"").append(Odf.colour(line)).append("\" svg:stroke-width=\"")
                    .append(Odf.cm(lineWidth)).append('"');
        } else {
            g.append(" draw:stroke=\"none\"");
        }
    }

    private void picture(StringBuilder sb, PictureShape p) throws IOException {
        Picture pic = p.picture();
        String g = "<style:graphic-properties draw:stroke=\"none\" draw:fill=\"none\" draw:shadow=\"hidden\""
                + (pic.flipH ? " style:mirror=\"horizontal\"" : "") + "/>";
        sb.append("<draw:frame draw:style-name=\"").append(styles.style("graphic", "gr", g)).append("\" draw:layer=\"layout\"");
        place(sb, p.frame());
        sb.append("><draw:image xlink:href=\"Pictures/").append(media.name(pic))
                .append("\" xlink:type=\"simple\" xlink:show=\"embed\" xlink:actuate=\"onLoad\"/>");
        if (!pic.description.isEmpty()) {
            sb.append("<svg:desc>").append(String.join("&#10;", pic.description.lines().map(Odf::esc).toList())).append("</svg:desc>");
        }
        sb.append("</draw:frame>");
    }

    private void rect(StringBuilder sb, RectShape r) {
        StringBuilder g = new StringBuilder("<style:graphic-properties");
        fillAndStroke(g, r.fillRgb(), r.alpha(), r.lineRgb(), r.lineWidth());
        g.append(" draw:shadow=\"hidden\"/>");
        sb.append("<draw:rect draw:style-name=\"").append(styles.style("graphic", "gr", g.toString())).append("\" draw:layer=\"layout\"");
        if (r.radius() > 0.1f) {
            sb.append(" draw:corner-radius=\"").append(Odf.cm(r.radius())).append('"');
        }
        place(sb, r.frame());
        sb.append("/>");
    }

    private void line(StringBuilder sb, LineShape l) {
        String g = "<style:graphic-properties draw:stroke=\"solid\" svg:stroke-color=\"" + Odf.colour(Math.max(0, l.rgb()))
                + "\" svg:stroke-width=\"" + Odf.cm(l.width()) + "\" draw:fill=\"none\" draw:shadow=\"hidden\"/>";
        sb.append("<draw:line draw:style-name=\"").append(styles.style("graphic", "gr", g)).append("\" draw:layer=\"layout\"")
                .append(" svg:x1=\"").append(Odf.cm(l.x1())).append("\" svg:y1=\"").append(Odf.cm(l.y1()))
                .append("\" svg:x2=\"").append(Odf.cm(l.x2())).append("\" svg:y2=\"").append(Odf.cm(l.y2())).append("\"/>");
    }

    private void table(StringBuilder sb, TableShape shape) {
        Table t = shape.table();
        if (t.rightToLeft) {
            t.mirror();
        }
        sb.append("<draw:frame draw:layer=\"layout\" svg:width=\"").append(Odf.cm(shape.width())).append("\" svg:height=\"")
                .append(Odf.cm(shape.height())).append("\" svg:x=\"").append(Odf.cm(shape.x())).append("\" svg:y=\"")
                .append(Odf.cm(shape.y())).append("\"><table:table>");
        for (float w : t.columnWidths) {
            sb.append("<table:table-column table:style-name=\"").append(styles.style("table-column", "co",
                    "<style:table-column-properties style:column-width=\"" + Odf.cm(Math.max(1f, w)) + "\"/>")).append("\"/>");
        }
        int cols = t.columnWidths.size();
        for (int r = 0; r < t.rows.size(); r++) {
            Table.Row row = t.rows.get(r);
            sb.append("<table:table-row table:style-name=\"").append(styles.style("table-row", "ro",
                    "<style:table-row-properties style:row-height=\"" + Odf.cm(Math.max(1f, row.height)) + "\"/>")).append("\">");
            int col = 0;
            for (Table.Cell cell : row.cells) {
                if (col >= cols) {
                    break;
                }
                int span = Math.max(1, Math.min(cell.gridSpan, cols - col));
                if (cell.vMerge == 2) {
                    for (int k = 0; k < span; k++) {
                        sb.append("<table:covered-table-cell/>");
                    }
                } else {
                    cell(sb, t, cell, span, cell.vMerge == 1 ? rowSpan(t, r, col) : 1);
                    for (int k = 1; k < span; k++) {
                        sb.append("<table:covered-table-cell/>");
                    }
                }
                col += span;
            }
            for (; col < cols; col++) {
                sb.append("<table:table-cell/>");
            }
            sb.append("</table:table-row>");
        }
        sb.append("</table:table></draw:frame>");
    }

    private void cell(StringBuilder sb, Table t, Table.Cell cell, int span, int rowSpan) {
        StringBuilder props = new StringBuilder("<style:table-cell-properties style:vertical-align=\"")
                .append(switch (cell.vAlign) {
                    case CENTER -> "middle";
                    case BOTTOM -> "bottom";
                    default -> "top";
                }).append('"');
        if (cell.shading >= 0) {
            props.append(" fo:background-color=\"").append(Odf.colour(cell.shading)).append('"');
        }
        border(props, "left", cell.left);
        border(props, "right", cell.right);
        border(props, "top", cell.top);
        border(props, "bottom", cell.bottom);
        props.append(" fo:padding-top=\"0cm\" fo:padding-bottom=\"0cm\" fo:padding-left=\"").append(Odf.cm(t.cellMarginLeft))
                .append("\" fo:padding-right=\"").append(Odf.cm(t.cellMarginRight)).append("\"/><style:graphic-properties");
        if (cell.shading >= 0) {
            props.append(" draw:fill=\"solid\" draw:fill-color=\"").append(Odf.colour(cell.shading)).append('"');
        } else {
            props.append(" draw:fill=\"none\"");
        }
        props.append(" fo:padding-top=\"0cm\" fo:padding-bottom=\"0cm\" fo:padding-left=\"").append(Odf.cm(t.cellMarginLeft))
                .append("\" fo:padding-right=\"").append(Odf.cm(t.cellMarginRight)).append("\" draw:textarea-vertical-align=\"")
                .append(switch (cell.vAlign) {
                    case CENTER -> "middle";
                    case BOTTOM -> "bottom";
                    default -> "top";
                }).append("\"/><style:paragraph-properties");
        border(props, "left", cell.left);
        border(props, "right", cell.right);
        border(props, "top", cell.top);
        border(props, "bottom", cell.bottom);
        props.append("/>");
        sb.append("<table:table-cell table:style-name=\"").append(styles.style("table-cell", "ce", props.toString())).append('"');
        if (span > 1) {
            sb.append(" table:number-columns-spanned=\"").append(span).append('"');
        }
        if (rowSpan > 1) {
            sb.append(" table:number-rows-spanned=\"").append(rowSpan).append('"');
        }
        sb.append('>');
        if (cell.paragraphs.isEmpty()) {
            sb.append("<text:p/>");
        }
        for (int i = 0; i < cell.paragraphs.size(); i++) {
            Paragraph p = cell.paragraphs.get(i);
            float lineHeight = p.lineRule == Paragraph.LineRule.EXACT ? p.lineHeight : 0;
            text.paragraph(sb, p, p.align, Math.max(0, p.indentLeft), p.indentFirst, Math.max(0, p.indentRight), lineHeight,
                    i == 0 ? 0 : p.spaceBefore, null, Set.of());
        }
        sb.append("</table:table-cell>");
    }

    private static void border(StringBuilder sb, String side, Table.Border b) {
        sb.append(" fo:border-").append(side).append("=\"");
        if (b == null || !b.visible()) {
            sb.append("none\"");
        } else {
            sb.append(Odf.pt(b.width())).append(" solid ").append(Odf.colour(Math.max(0, b.rgb()))).append('"');
        }
    }

    private static int rowSpan(Table t, int row, int col) {
        int span = 1;
        for (int r = row + 1; r < t.rows.size(); r++) {
            Table.Cell below = cellAt(t.rows.get(r), col);
            if (below == null || below.vMerge != 2) {
                break;
            }
            span++;
        }
        return span;
    }

    private static Table.Cell cellAt(Table.Row row, int col) {
        int c = 0;
        for (Table.Cell cell : row.cells) {
            if (c == col) {
                return cell;
            }
            c += Math.max(1, cell.gridSpan);
            if (c > col) {
                return null;
            }
        }
        return null;
    }
}
