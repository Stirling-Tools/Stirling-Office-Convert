package stirling.software.officeconvert.json;

import java.util.List;
import java.util.Locale;

import stirling.software.officeconvert.model.Block;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.Scripts;
import stirling.software.officeconvert.model.Section;
import stirling.software.officeconvert.model.StyleSheet;
import stirling.software.officeconvert.model.Table;

/** The document model as JSON; {@link JsonModel} reads it back. Lengths are in points, colours #RRGGBB. */
final class ModelJson {

    private ModelJson() {}

    static void block(StringBuilder sb, Block b) {
        if (b instanceof Paragraph p) {
            paragraph(sb, p);
        } else {
            table(sb, (Table) b);
        }
    }

    static void paragraph(StringBuilder sb, Paragraph p) {
        JsonOut o = new JsonOut(sb).str("type", "paragraph");
        if (!"Normal".equals(p.style)) {
            o.str("style", p.style);
        }
        if (p.align != Paragraph.Align.LEFT) {
            o.str("align", lower(p.align));
        }
        o.num("indentLeft", p.indentLeft).num("indentRight", p.indentRight).num("indentFirst", p.indentFirst)
                .num("spaceBefore", p.spaceBefore).num("spaceAfter", p.spaceAfter).num("lineHeight", p.lineHeight);
        if (p.lineRule != Paragraph.LineRule.AUTO) {
            o.str("lineRule", lower(p.lineRule));
        }
        if (p.list != null) {
            JsonOut l = new JsonOut(o.key("list"));
            l.always("id", p.list.numId()).always("level", p.list.level()).end();
        }
        o.flag("pageBreakBefore", p.pageBreakBefore).flag("keepNext", p.keepNext).flag("keepLines", p.keepLines).flag("rightToLeft", p.bidi)
                .flag("noHangingPunctuation", p.noHangingPunctuation)
                .color("shading", p.shading).num("borderBottom", p.borderBottom);
        if (p.borderBottom > 0) {
            o.color("borderBottomColor", p.borderBottomRgb);
        }
        o.str("bookmark", p.bookmark);
        if (p.markStyle != null) {
            run(o.key("markStyle"), p.markStyle);
        }
        if (!p.tabs.isEmpty()) {
            StringBuilder t = o.key("tabs").append('[');
            for (int i = 0; i < p.tabs.size(); i++) {
                Paragraph.TabStop tab = p.tabs.get(i);
                JsonOut s = new JsonOut(i > 0 ? t.append(',') : t).always("pos", tab.pos());
                if (tab.kind() != Paragraph.TabStop.Kind.LEFT) {
                    s.str("kind", lower(tab.kind()));
                }
                if (tab.leader() != 0 && tab.leader() != ' ') {
                    s.str("leader", String.valueOf(tab.leader()));
                }
                s.end();
            }
            t.append(']');
        }
        if (p.endsSection != null) {
            section(o.key("endsSection"), p.endsSection);
        }
        inlines(o.key("content"), p.inlines);
        o.end();
    }

    static void paragraphs(StringBuilder sb, List<Paragraph> list) {
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            paragraph(sb, list.get(i));
        }
        sb.append(']');
    }

    private static void inlines(StringBuilder sb, List<Inline> list) {
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            inline(sb, list.get(i));
        }
        sb.append(']');
    }

    private static void inline(StringBuilder sb, Inline in) {
        switch (in) {
            case Inline.Text t -> {
                JsonOut o = new JsonOut(sb).str("type", "text").str("text", t.text()).str("link", t.link());
                if (t.anchorPage() >= 0) {
                    o.always("linkPage", t.anchorPage() + 1);
                }
                styled(o, t.style());
            }
            case Inline.Tab t -> styled(new JsonOut(sb).str("type", "tab"), t.style());
            case Inline.Break b -> new JsonOut(sb).str("type", "break").end();
            case Inline.ColumnBreak b -> new JsonOut(sb).str("type", "columnBreak").end();
            case Inline.PageBreak b -> new JsonOut(sb).str("type", "pageBreak").end();
            case Inline.PageNumber n -> styled(new JsonOut(sb).str("type", "pageNumber").flag("total", n.total()),
                    n.style());
            case Inline.Image im -> picture(sb, im.picture());
            case Inline.FootnoteRef r -> styled(new JsonOut(sb).str("type", "footnoteRef").always("id", r.id())
                    .str("marker", r.marker()).flag("custom", r.custom()), r.style());
            case Inline.FootnoteMark m -> styled(new JsonOut(sb).str("type", "footnoteMark").str("marker", m.marker())
                    .flag("custom", m.custom()), m.style());
            case Inline.TextBox b -> {
                JsonOut o = new JsonOut(sb).str("type", "textBox").always("x", b.x()).always("y", b.y())
                        .always("width", b.width()).always("height", b.height()).color("fill", b.fillRgb())
                        .num("insetLeft", b.insetLeft()).num("insetRight", b.insetRight()).num("wrapGap", b.wrapGap())
                        .color("line", b.lineRgb()).num("lineWidth", b.lineWidth()).flag("rounded", b.rounded())
                        .flag("overlay", b.overlay()).integer("direction", b.direction(), 0).color("ground", b.groundRgb())
                        .flag("upright", b.upright());
                paragraphs(o.key("paragraphs"), b.paragraphs());
                o.end();
            }
            case Inline.FormControl f -> {
                StringBuilder text = new StringBuilder();
                RunStyle style = null;
                for (Inline k : f.shown().inlines) {
                    if (k instanceof Inline.Text t) {
                        text.append(t.text());
                        style = style == null ? t.style() : style;
                    } else if (k instanceof Inline.Break) {
                        text.append('\n');
                    }
                }
                inline(sb, new Inline.Text(text.toString(), style, null, -1));
            }
            case Inline.Shape s -> new JsonOut(sb).str("type", "shape").always("x", s.x()).always("y", s.y())
                    .always("width", s.width()).always("height", s.height()).color("fill", s.rgb())
                    .color("line", s.lineRgb()).num("lineWidth", s.lineWidth()).flag("rounded", s.rounded())
                    .flag("ellipse", s.ellipse()).flag("fromParagraph", s.fromParagraph()).integer("z", s.z(), 0).end();
        }
    }

    // No style at all is written as null; a missing style means the paragraph's to a reader
    private static void styled(JsonOut o, RunStyle style) {
        if (style != null) {
            run(o.key("style"), style);
        } else {
            o.key("style").append("null");
        }
        o.end();
    }

    static void run(StringBuilder sb, RunStyle s) {
        JsonOut o = new JsonOut(sb).nullable("font", s.font()).always("size", s.size()).flag("bold", s.bold())
                .flag("italic", s.italic()).flag("underline", s.underline()).flag("strike", s.strike());
        if ((s.rgb() & 0xFFFFFF) != 0) {
            o.color("color", s.rgb());
        }
        o.color("highlight", s.highlight());
        if (s.vertAlign() != 0) {
            o.str("vertAlign", s.vertAlign() > 0 ? "super" : "sub");
        }
        o.flag("symbol", s.symbol()).num("spacing", s.spacing()).integer("scale", s.scale(), 100)
                .flag("smallCaps", s.smallCaps()).color("border", s.border()).end();
    }

    private static void picture(StringBuilder sb, Picture p) {
        JsonOut o = new JsonOut(sb).str("type", "image").str("media", p.media.name()).always("width", p.width)
                .always("height", p.height);
        if (p.wrap != Picture.Wrap.INLINE) {
            o.str("wrap", lower(p.wrap)).always("x", p.x).always("y", p.y);
        }
        o.flag("behind", p.behind).flag("backdrop", p.backdrop).integer("z", p.z, 0)
                .integer("paintOrder", p.paintOrder, -1).flag("fromParagraph", p.fromParagraph)
                .num("wrapGap", p.wrapGap).num("cropLeft", p.cropLeft).num("cropTop", p.cropTop)
                .num("cropRight", p.cropRight).num("cropBottom", p.cropBottom).integer("rotation", p.rotation, 0)
                .flag("flipHorizontal", p.flipH).num("baselineShift", p.baselineShift);
        if (p.description != null && !p.description.isEmpty()) {
            o.str("description", p.description);
        }
        o.end();
    }

    static void table(StringBuilder sb, Table t) {
        JsonOut o = new JsonOut(sb).str("type", "table");
        StringBuilder cols = o.key("columns").append('[');
        for (int i = 0; i < t.columnWidths.size(); i++) {
            Json.number(i > 0 ? cols.append(',') : cols, t.columnWidths.get(i));
        }
        cols.append(']');
        o.num("indent", t.indent).num("indentEnd", t.indentEnd).num("cellMarginLeft", t.cellMarginLeft, 2f)
                .num("cellMarginRight", t.cellMarginRight, 2f)
                .flag("pageBreakBefore", t.pageBreakBefore)
                .flag("rightToLeft", t.rightToLeft);
        if (t.floating()) {
            o.num("floatX", t.floatX, Float.NaN).always("floatY", t.floatY).num("floatRoom", t.floatRoom);
        }
        StringBuilder rows = o.key("rows").append('[');
        for (int r = 0; r < t.rows.size(); r++) {
            Table.Row row = t.rows.get(r);
            JsonOut ro = new JsonOut(r > 0 ? rows.append(',') : rows).num("height", row.height)
                    .flag("header", row.header).flag("exactHeight", row.exactHeight).flag("splits", row.splits);
            StringBuilder cells = ro.key("cells").append('[');
            for (int c = 0; c < row.cells.size(); c++) {
                cell(c > 0 ? cells.append(',') : cells, row.cells.get(c));
            }
            cells.append(']');
            ro.end();
        }
        rows.append(']');
        o.end();
    }

    private static void cell(StringBuilder sb, Table.Cell c) {
        JsonOut o = new JsonOut(sb).integer("span", c.gridSpan, 1);
        if (c.vMerge != 0) {
            o.str("verticalMerge", c.vMerge == 1 ? "restart" : "continue");
        }
        border(o, "top", c.top);
        border(o, "bottom", c.bottom);
        border(o, "left", c.left);
        border(o, "right", c.right);
        o.color("shading", c.shading);
        if (c.vAlign != Table.VAlign.TOP) {
            o.str("verticalAlign", lower(c.vAlign));
        }
        paragraphs(o.key("paragraphs"), c.paragraphs);
        o.end();
    }

    private static void border(JsonOut o, String side, Table.Border b) {
        if (b != null && b.visible()) {
            new JsonOut(o.key(side)).always("width", b.width()).color("color", b.rgb()).end();
        }
    }

    static void section(StringBuilder sb, Section s) {
        JsonOut o = new JsonOut(sb).always("width", s.pageWidth).always("height", s.pageHeight)
                .always("marginTop", s.marginTop).always("marginBottom", s.marginBottom)
                .always("marginLeft", s.marginLeft).always("marginRight", s.marginRight)
                .num("headerDistance", s.headerDistance).num("footerDistance", s.footerDistance)
                .flag("continuous", s.continuous).flag("titlePage", s.titlePage).integer("pageNumberStart", s.pageNumberStart, 1);
        if (!s.columns.isEmpty()) {
            StringBuilder cols = o.key("columns").append('[');
            for (int i = 0; i < s.columns.size(); i++) {
                float[] c = s.columns.get(i);
                JsonOut co = new JsonOut(i > 0 ? cols.append(',') : cols).always("width", c[0]);
                if (c.length > 1) {
                    co.num("gap", c[1]);
                }
                co.end();
            }
            cols.append(']');
        }
        o.end();
    }

    static void styles(StringBuilder sb, StyleSheet styles) {
        JsonOut o = new JsonOut(sb);
        run(o.key("normal"), styles.normal);
        Scripts.Profile.Answers a = styles.scripts.answers();
        JsonOut lang = new JsonOut(o.key("languages")).str("latin", a.latin()).str("eastAsian", a.eastAsian())
                .str("complex", a.complex()).flag("rightToLeft", a.rightToLeft());
        if (a.latinScript() != null) {
            lang.str("latinScript", a.latinScript().name());
        }
        if (a.complexScript() != null) {
            lang.str("complexScript", a.complexScript().name());
        }
        lang.end();
        StringBuilder list = o.key("styles").append('[');
        boolean first = true;
        for (StyleSheet.Style s : styles.all()) {
            if ("Normal".equals(s.id())) {
                continue;
            }
            JsonOut so = new JsonOut(first ? list : list.append(',')).str("id", s.id()).str("name", s.name());
            run(so.key("run"), s.run());
            so.num("outlineLevel", s.outlineLevel(), -1).flag("keepNext", s.keepNext()).end();
            first = false;
        }
        list.append(']');
        o.end();
    }

    static void lists(StringBuilder sb, Numbering numbering) {
        sb.append('[');
        List<Numbering.Instance> all = numbering == null ? List.of() : numbering.instances;
        for (int i = 0; i < all.size(); i++) {
            Numbering.Instance inst = all.get(i);
            JsonOut o = new JsonOut(i > 0 ? sb.append(',') : sb).always("id", inst.numId);
            StringBuilder levels = o.key("levels").append('[');
            for (int l = 0; l < 9; l++) {
                Numbering.Level lv = inst.definition.levels[l];
                if (l > 0) {
                    levels.append(',');
                }
                if (lv == null) {
                    levels.append("null");
                    continue;
                }
                JsonOut lo = new JsonOut(levels).str("format", lv.format()).str("text", lv.text())
                        .num("indentLeft", lv.indentLeft(), 36f * (l + 1)).num("hanging", lv.hanging(), 18f).integer("start", inst.starts[l], 1);
                if (lv.markerStyle() != null) {
                    run(lo.key("markerStyle"), lv.markerStyle());
                }
                lo.end();
            }
            levels.append(']');
            o.end();
        }
        sb.append(']');
    }

    private static String lower(Enum<?> e) {
        String n = e.name().toLowerCase(Locale.ROOT);
        int u = n.indexOf('_');
        return u < 0 ? n : n.substring(0, u) + Character.toUpperCase(n.charAt(u + 1)) + n.substring(u + 2);
    }
}
