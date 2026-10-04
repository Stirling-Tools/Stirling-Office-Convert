package stirling.software.officeconvert.docx;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.Section;
import stirling.software.officeconvert.model.StyleSheet;
import stirling.software.officeconvert.model.Table;
import stirling.software.officeconvert.sink.Links;

final class BodyXml {

    private static final int MAX_TABS = 64;

    private final PartContext ctx;
    private final StyleSheet styles;
    private final RunXml runs;
    private final DrawingXml drawings;
    private boolean firstSection = true;

    BodyXml(PartContext ctx, StyleSheet styles) {
        this.ctx = ctx;
        this.styles = styles;
        this.runs = new RunXml(ctx);
        this.drawings = new DrawingXml(ctx, this);
    }

    void paragraph(StringBuilder sb, Paragraph p, boolean body) {
        StyleSheet.Style style = styles.get(p.style);
        RunStyle base = style.run();
        sb.append("<w:p><w:pPr>");
        if (!"Normal".equals(p.style)) {
            sb.append("<w:pStyle w:val=\"").append(p.style).append("\"/>");
        }
        if (p.keepNext) {
            sb.append("<w:keepNext/>");
        } else if (style.keepNext()) {
            sb.append("<w:keepNext w:val=\"0\"/><w:keepLines w:val=\"0\"/>");
        }
        if (p.pageBreakBefore) {
            sb.append("<w:pageBreakBefore/>");
        }
        if (p.list != null) {
            sb.append("<w:numPr><w:ilvl w:val=\"").append(p.list.level())
                    .append("\"/><w:numId w:val=\"").append(p.list.numId()).append("\"/></w:numPr>");
        }
        if (p.borderBottom > 0) {
            sb.append("<w:pBdr><w:bottom w:val=\"single\" w:sz=\"").append(Xml.eighths(p.borderBottom))
                    .append("\" w:space=\"1\" w:color=\"").append(Xml.hex(p.borderBottomRgb)).append("\"/></w:pBdr>");
        }
        if (p.shading >= 0) {
            sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(Xml.hex(p.shading)).append("\"/>");
        }
        if (!p.tabs.isEmpty()) {
            sb.append("<w:tabs>");
            for (Paragraph.TabStop t : p.tabs.subList(0, Math.min(p.tabs.size(), MAX_TABS))) {
                String kind = switch (t.kind()) {
                    case CENTER -> "center";
                    case RIGHT -> "right";
                    case DECIMAL -> "decimal";
                    default -> "left";
                };
                sb.append("<w:tab w:val=\"").append(kind).append('"');
                if (t.leader() != 0) {
                    String leader = switch (t.leader()) {
                        case '_' -> "underscore";
                        case '-' -> "hyphen";
                        case '\u00B7' -> "middleDot";
                        default -> "dot";
                    };
                    sb.append(" w:leader=\"").append(leader).append('"');
                }
                sb.append(" w:pos=\"").append(Xml.twips(t.pos())).append("\"/>");
            }
            sb.append("</w:tabs>");
        }
        if (p.noHangingPunctuation) {
            sb.append("<w:overflowPunct w:val=\"0\"/>");
        }
        if (p.bidi) {
            sb.append("<w:bidi/>");
        }
        sb.append("<w:spacing w:before=\"").append(clampTwips(p.spaceBefore))
                .append("\" w:after=\"").append(clampTwips(p.spaceAfter)).append('"');
        switch (p.lineRule) {
            case EXACT -> sb.append(" w:line=\"").append(Math.max(20, Xml.twips(p.lineHeight))).append("\" w:lineRule=\"exact\"");
            case AT_LEAST -> sb.append(" w:line=\"").append(Math.max(20, Xml.twips(p.lineHeight))).append("\" w:lineRule=\"atLeast\"");
            default -> sb.append(" w:line=\"240\" w:lineRule=\"auto\"");
        }
        sb.append("/>");
        int left = Xml.twips(p.bidi ? p.indentRight : p.indentLeft);
        int right = Xml.twips(p.bidi ? p.indentLeft : p.indentRight);
        int first = Xml.twips(p.indentFirst);
        if (left != 0 || right != 0 || first != 0 || p.list != null) {
            sb.append("<w:ind w:left=\"").append(left).append("\" w:right=\"").append(right).append('"');
            if (first > 0) {
                sb.append(" w:firstLine=\"").append(first).append('"');
            } else if (first < 0) {
                sb.append(" w:hanging=\"").append(-first).append('"');
            } else {
                sb.append(" w:firstLine=\"0\"");
            }
            sb.append("/>");
        }
        String jc = switch (p.align) {
            case CENTER -> "center";
            case RIGHT -> p.bidi ? null : "right";
            case JUSTIFY -> "both";
            default -> p.bidi ? "right" : "left";
        };
        if (jc != null) {
            sb.append("<w:jc w:val=\"").append(jc).append("\"/>");
        }
        if (p.markStyle != null) {
            StringBuilder rpr = new StringBuilder();
            runs.runProps(rpr, p.markStyle, base);
            if (!rpr.isEmpty()) {
                sb.append("<w:rPr>").append(rpr).append("</w:rPr>");
            }
        }
        if (body && p.endsSection != null) {
            sectPr(sb, p.endsSection);
        }
        sb.append("</w:pPr>");
        if (p.bookmark != null) {
            int id = ctx.bookmark();
            sb.append("<w:bookmarkStart w:id=\"").append(id).append("\" w:name=\"").append(Xml.esc(p.bookmark))
                    .append("\"/><w:bookmarkEnd w:id=\"").append(id).append("\"/>");
        }
        inlines(sb, p.inlines, base);
        sb.append("</w:p>");
    }

    private static int clampTwips(float pt) {
        return Math.max(0, Math.min(31680, Xml.twips(pt)));
    }

    private void inlines(StringBuilder sb, List<Inline> inlines, RunStyle base) {
        String openLink = null;
        for (Inline in : inlines) {
            String link = in instanceof Inline.Text t ? Links.target(t) : null;
            if (!Objects.equals(link, openLink)) {
                if (openLink != null) {
                    sb.append("</w:hyperlink>");
                }
                if (link != null) {
                    if (link.startsWith("#")) {
                        sb.append("<w:hyperlink w:anchor=\"").append(Xml.esc(link.substring(1))).append("\" w:history=\"1\">");
                    } else {
                        sb.append("<w:hyperlink r:id=\"").append(ctx.linkRel(link)).append("\" w:history=\"1\">");
                    }
                }
                openLink = link;
            }
            switch (in) {
                case Inline.Text t -> runs.textRun(sb, t.text(), t.style(), base);
                case Inline.Tab tab -> {
                    sb.append("<w:r>");
                    runs.rPr(sb, tab.style(), base);
                    sb.append("<w:tab/></w:r>");
                }
                case Inline.Break br -> sb.append("<w:r><w:br/></w:r>");
                case Inline.ColumnBreak cb -> sb.append("<w:r><w:br w:type=\"column\"/></w:r>");
                case Inline.PageBreak pb -> sb.append("<w:r><w:br w:type=\"page\"/></w:r>");
                case Inline.PageNumber pn -> runs.field(sb, pn.total() ? "NUMPAGES" : "PAGE", pn.style(), base);
                case Inline.Image img -> drawings.drawing(sb, img.picture());
                case Inline.Shape shape -> drawings.shape(sb, shape);
                case Inline.FootnoteRef ref -> {
                    sb.append("<w:r>");
                    runs.rPr(sb, ref.style() == null ? null : ref.style().withVertAlign(1), base);
                    if (ref.custom()) {
                        sb.append("<w:footnoteReference w:customMarkFollows=\"1\" w:id=\"").append(ref.id()).append("\"/><w:t>");
                        Xml.text(sb, ref.marker());
                        sb.append("</w:t>");
                    } else {
                        sb.append("<w:footnoteReference w:id=\"").append(ref.id()).append("\"/>");
                    }
                    sb.append("</w:r>");
                }
                case Inline.FootnoteMark mark -> {
                    sb.append("<w:r>");
                    runs.rPr(sb, mark.style(), base);
                    if (mark.custom()) {
                        sb.append("<w:t>");
                        Xml.text(sb, mark.marker());
                        sb.append("</w:t>");
                    } else {
                        sb.append("<w:footnoteRef/>");
                    }
                    sb.append("</w:r>");
                }
                case Inline.TextBox box -> drawings.textBox(sb, box);
            }
        }
        if (openLink != null) {
            sb.append("</w:hyperlink>");
        }
    }

    void table(StringBuilder sb, Table t) {
        float total = 0;
        for (float w : t.columnWidths) {
            total += w;
        }
        sb.append("<w:tbl><w:tblPr>");
        if (t.floating()) {
            sb.append("<w:tblpPr w:leftFromText=\"0\" w:rightFromText=\"").append(Xml.twips(t.floatRoom))
                    .append("\" w:topFromText=\"0\" w:bottomFromText=\"0\"")
                    .append(" w:vertAnchor=\"page\" w:horzAnchor=\"page\" w:tblpX=\"").append(Xml.twips(t.floatX))
                    .append("\" w:tblpY=\"").append(Xml.twips(t.floatY)).append("\"/><w:tblOverlap w:val=\"overlap\"/>");
        }
        if (t.rightToLeft) {
            sb.append("<w:bidiVisual/>");
        }
        sb.append("<w:tblW w:w=\"").append(Xml.twips(total)).append("\" w:type=\"dxa\"/>")
                .append("<w:tblInd w:w=\"").append(Xml.twips(t.floating() ? 0 : t.indent)).append("\" w:type=\"dxa\"/>")
                .append("<w:tblLayout w:type=\"fixed\"/>")
                .append("<w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"")
                .append(Xml.twips(t.cellMarginLeft)).append("\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"")
                .append(Xml.twips(t.cellMarginRight)).append("\" w:type=\"dxa\"/></w:tblCellMar>")
                .append("<w:tblLook w:val=\"0000\" w:firstRow=\"0\" w:lastRow=\"0\" w:firstColumn=\"0\" w:lastColumn=\"0\" w:noHBand=\"1\" w:noVBand=\"1\"/>")
                .append("</w:tblPr><w:tblGrid>");
        for (float w : t.columnWidths) {
            sb.append("<w:gridCol w:w=\"").append(Xml.twips(w)).append("\"/>");
        }
        sb.append("</w:tblGrid>");
        for (Table.Row row : t.rows) {
            sb.append(row.splits ? "<w:tr><w:trPr><w:trHeight w:val=\"" : "<w:tr><w:trPr><w:cantSplit/><w:trHeight w:val=\"")
                    .append(Xml.twips(row.height))
                    .append("\" w:hRule=\"").append(row.exactHeight ? "exact" : "atLeast").append("\"/>");
            if (row.header) {
                sb.append("<w:tblHeader/>");
            }
            sb.append("</w:trPr>");
            int col = 0;
            for (Table.Cell cell : row.cells) {
                float w = 0;
                for (int k = col; k < Math.min(t.columnWidths.size(), col + cell.gridSpan); k++) {
                    w += t.columnWidths.get(k);
                }
                col += cell.gridSpan;
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(Xml.twips(w)).append("\" w:type=\"dxa\"/>");
                if (cell.gridSpan > 1) {
                    sb.append("<w:gridSpan w:val=\"").append(cell.gridSpan).append("\"/>");
                }
                if (cell.vMerge == 1) {
                    sb.append("<w:vMerge w:val=\"restart\"/>");
                } else if (cell.vMerge == 2) {
                    sb.append("<w:vMerge/>");
                }
                if (cell.top.visible() || cell.bottom.visible() || cell.left.visible() || cell.right.visible()) {
                    sb.append("<w:tcBorders>");
                    border(sb, "top", cell.top);
                    border(sb, "left", cell.left);
                    border(sb, "bottom", cell.bottom);
                    border(sb, "right", cell.right);
                    sb.append("</w:tcBorders>");
                }
                if (cell.shading >= 0) {
                    sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(Xml.hex(cell.shading)).append("\"/>");
                }
                if (cell.vAlign != Table.VAlign.TOP) {
                    sb.append("<w:vAlign w:val=\"").append(cell.vAlign == Table.VAlign.CENTER ? "center" : "bottom").append("\"/>");
                }
                sb.append("</w:tcPr>");
                if (cell.paragraphs.isEmpty() || cell.vMerge == 2) {
                    sb.append("<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/><w:rPr><w:sz w:val=\"2\"/><w:szCs w:val=\"2\"/></w:rPr></w:pPr></w:p>");
                } else {
                    for (Paragraph p : cell.paragraphs) {
                        paragraph(sb, p, false);
                    }
                }
                sb.append("</w:tc>");
            }
            sb.append("</w:tr>");
        }
        sb.append("</w:tbl>");
    }

    private static void border(StringBuilder sb, String side, Table.Border b) {
        if (!b.visible()) {
            sb.append("<w:").append(side).append(" w:val=\"nil\"/>");
            return;
        }
        sb.append("<w:").append(side).append(" w:val=\"single\" w:sz=\"").append(Xml.eighths(b.width()))
                .append("\" w:space=\"0\" w:color=\"").append(Xml.hex(b.rgb())).append("\"/>");
    }

    void sectPr(StringBuilder sb, Section s) {
        sb.append("<w:sectPr>");
        if (firstSection) {
            for (Map.Entry<String, String> e : ctx.headerRelIds.entrySet()) {
                String[] parts = e.getKey().split("\\|");
                sb.append("<w:").append(parts[0]).append("Reference w:type=\"").append(parts[1])
                        .append("\" r:id=\"").append(e.getValue()).append("\"/>");
            }
        }
        sb.append("<w:type w:val=\"").append(s.continuous ? "continuous" : "nextPage").append("\"/>");
        int w = Xml.twips(s.pageWidth);
        int h = Xml.twips(s.pageHeight);
        sb.append("<w:pgSz w:w=\"").append(w).append("\" w:h=\"").append(h).append('"');
        if (w > h) {
            sb.append(" w:orient=\"landscape\"");
        }
        sb.append("/><w:pgMar w:top=\"").append(Xml.twips(s.marginTop)).append("\" w:right=\"")
                .append(Xml.twips(s.marginRight)).append("\" w:bottom=\"").append(Xml.twips(s.marginBottom))
                .append("\" w:left=\"").append(Xml.twips(s.marginLeft)).append("\" w:header=\"")
                .append(Xml.twips(s.headerDistance)).append("\" w:footer=\"").append(Xml.twips(s.footerDistance))
                .append("\" w:gutter=\"0\"/>");
        if (firstSection && s.pageNumberStart != 1) {
            sb.append("<w:pgNumType w:start=\"").append(Math.max(0, s.pageNumberStart)).append("\"/>");
        }
        if (s.columns.size() > 1) {
            sb.append("<w:cols w:num=\"").append(s.columns.size()).append("\" w:space=\"")
                    .append(Xml.twips(s.columns.getFirst()[1])).append("\" w:equalWidth=\"0\">");
            for (float[] c : s.columns) {
                sb.append("<w:col w:w=\"").append(Xml.twips(c[0])).append("\" w:space=\"").append(Xml.twips(c[1])).append("\"/>");
            }
            sb.append("</w:cols>");
        } else {
            sb.append("<w:cols w:space=\"720\"/>");
        }
        if (firstSection && s.titlePage) {
            sb.append("<w:titlePg/>");
        }
        sb.append("</w:sectPr>");
        firstSection = false;
    }
}
