package stirling.software.officeconvert.topdf.rtf;

final class PropsXml {

    private final Doc doc;

    PropsXml(Doc doc) {
        this.doc = doc;
    }

    String rPr(CharProps c) {
        String inner = rPrInner(c, true);
        return inner.isEmpty() ? "" : "<w:rPr>" + inner + "</w:rPr>";
    }

    String rPrInner(CharProps c, boolean withStyle) {
        StringBuilder b = new StringBuilder(128);
        if (withStyle && c.style >= 0 && doc.styles.character(c.style) != null) {
            b.append("<w:rStyle w:val=\"cs").append(c.style).append("\"/>");
        }
        fonts(b, c);
        toggle(b, c, CharProps.BOLD, c.bold, "b");
        toggle(b, c, CharProps.BOLD_CS, c.boldCs, "bCs");
        toggle(b, c, CharProps.ITALIC, c.italic, "i");
        toggle(b, c, CharProps.ITALIC_CS, c.italicCs, "iCs");
        toggle(b, c, CharProps.CAPS, c.caps, "caps");
        toggle(b, c, CharProps.SMALL_CAPS, c.smallCaps, "smallCaps");
        toggle(b, c, CharProps.STRIKE, c.strike, "strike");
        toggle(b, c, CharProps.DSTRIKE, c.dstrike, "dstrike");
        toggle(b, c, CharProps.OUTLINE, c.outline, "outline");
        toggle(b, c, CharProps.SHADOW, c.shadow, "shadow");
        toggle(b, c, CharProps.EMBOSS, c.emboss, "emboss");
        toggle(b, c, CharProps.IMPRINT, c.imprint, "imprint");
        toggle(b, c, CharProps.HIDDEN, c.hidden, "vanish");
        if (c.has(CharProps.COLOR)) {
            b.append("<w:color w:val=\"").append(doc.colors.hex(c.color)).append("\"/>");
        }
        if (c.has(CharProps.SPACING)) {
            b.append("<w:spacing w:val=\"").append(c.spacing).append("\"/>");
        }
        if (c.has(CharProps.SCALE)) {
            b.append("<w:w w:val=\"").append(c.scale).append("\"/>");
        }
        if (c.has(CharProps.KERNING)) {
            b.append("<w:kern w:val=\"").append(c.kerning).append("\"/>");
        }
        if (c.has(CharProps.POSITION)) {
            b.append("<w:position w:val=\"").append(c.position).append("\"/>");
        }
        if (c.has(CharProps.SIZE)) {
            b.append("<w:sz w:val=\"").append(c.size).append("\"/>");
        }
        if (c.has(CharProps.CS_SIZE)) {
            b.append("<w:szCs w:val=\"").append(c.csSize).append("\"/>");
        }
        if (c.has(CharProps.UNDERLINE)) {
            b.append("<w:u w:val=\"").append(c.underline).append('"');
            if (c.has(CharProps.UNDERLINE_COLOR) && doc.colors.explicit(c.underlineColor)) {
                b.append(" w:color=\"").append(doc.colors.hex(c.underlineColor)).append('"');
            }
            b.append("/>");
        }
        if (c.has(CharProps.BORDER) && c.border != null) {
            b.append(c.border.xml("bdr", doc.colors));
        }
        String fill = null;
        if (c.has(CharProps.HIGHLIGHT) && c.highlight > 0 && doc.colors.explicit(c.highlight)) {
            fill = doc.colors.hex(c.highlight);
        } else if (c.has(CharProps.SHADING)) {
            fill = c.shade.fill(doc.colors);
        }
        if (fill != null) {
            b.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(fill).append("\"/>");
        }
        if (c.has(CharProps.VERTICAL)) {
            b.append("<w:vertAlign w:val=\"")
                    .append(c.vertical == 1 ? "superscript" : c.vertical == 2 ? "subscript" : "baseline")
                    .append("\"/>");
        }
        if (c.has(CharProps.RTL) && c.rtl) {
            b.append("<w:rtl/>");
        }
        lang(b, c);
        return b.toString();
    }

    private void lang(StringBuilder b, CharProps c) {
        String ea = c.has(CharProps.EA_LANG) ? Langs.tag(c.eaLang) : null;
        String bidi = c.has(CharProps.CS_LANG) ? Langs.tag(c.csLang) : null;
        String val = c.has(CharProps.LANG) ? Langs.tag(c.lang) : null;
        if (ea == null && bidi == null && val == null) {
            return;
        }
        b.append("<w:lang");
        if (val != null) {
            b.append(" w:val=\"").append(val).append('"');
        }
        if (ea != null) {
            b.append(" w:eastAsia=\"").append(ea).append('"');
        }
        if (bidi != null) {
            b.append(" w:bidi=\"").append(bidi).append('"');
        }
        b.append("/>");
    }

    private void fonts(StringBuilder b, CharProps c) {
        String ascii = c.has(CharProps.FONT) ? doc.fonts.name(c.font) : null;
        String hAnsi = c.has(CharProps.H_FONT) ? doc.fonts.name(c.hFont) : null;
        String ea = c.has(CharProps.EA_FONT) ? doc.fonts.name(c.eaFont) : null;
        String cs = c.has(CharProps.CS_FONT) ? doc.fonts.name(c.csFont) : null;
        if (ascii == null && hAnsi == null && ea == null && cs == null) {
            return;
        }
        b.append("<w:rFonts");
        attr(b, "ascii", ascii);
        attr(b, "hAnsi", hAnsi);
        attr(b, "eastAsia", ea);
        attr(b, "cs", cs);
        b.append("/>");
    }

    private static void attr(StringBuilder b, String name, String value) {
        if (value != null) {
            b.append(" w:").append(name).append("=\"").append(Xml.attr(value)).append('"');
        }
    }

    private static void toggle(StringBuilder b, CharProps c, int prop, boolean value, String tag) {
        if (c.has(prop)) {
            b.append("<w:").append(tag).append(value ? "/>" : " w:val=\"0\"/>");
        }
    }

    String pPr(ParaProps p, CharProps mark, String extra, boolean withStyle) {
        StringBuilder b = new StringBuilder(256);
        if (withStyle && doc.styles.paragraph(p.style) != null) {
            b.append("<w:pStyle w:val=\"s").append(p.style).append("\"/>");
        }
        flag(b, p, ParaProps.KEEP_NEXT, p.keepNext, "keepNext");
        flag(b, p, ParaProps.KEEP, p.keep, "keepLines");
        flag(b, p, ParaProps.PAGE_BREAK, p.pageBreak, "pageBreakBefore");
        if (p.frame != null) {
            b.append(p.frame.xml());
        }
        flag(b, p, ParaProps.WIDOW, p.widow, "widowControl");
        if (p.has(ParaProps.LIST)) {
            if (p.list > 0 && doc.lists.numbered(p.list)) {
                b.append("<w:numPr><w:ilvl w:val=\"").append(p.level).append("\"/><w:numId w:val=\"")
                        .append(p.list).append("\"/></w:numPr>");
            } else if (p.list == 0) {
                b.append("<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"0\"/></w:numPr>");
            }
        }
        flag(b, p, ParaProps.NO_LINE, p.noLine, "suppressLineNumbers");
        if (p.has(ParaProps.BORDERS)) {
            StringBuilder bd = new StringBuilder();
            if (p.top != null) {
                bd.append(p.top.xml("top", doc.colors));
            }
            if (p.leftBorder != null) {
                bd.append(p.leftBorder.xml("left", doc.colors));
            }
            if (p.bottom != null) {
                bd.append(p.bottom.xml("bottom", doc.colors));
            }
            if (p.rightBorder != null) {
                bd.append(p.rightBorder.xml("right", doc.colors));
            }
            if (p.between != null) {
                bd.append(p.between.xml("between", doc.colors));
            }
            if (!bd.isEmpty()) {
                b.append("<w:pBdr>").append(bd).append("</w:pBdr>");
            }
        }
        if (p.has(ParaProps.SHADING)) {
            String fill = p.shade.fill(doc.colors);
            b.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(fill == null ? "auto" : fill)
                    .append("\"/>");
        }
        if (p.has(ParaProps.TABS) && !p.tabs.isEmpty()) {
            b.append("<w:tabs>");
            for (ParaProps.Tab t : p.tabs) {
                b.append("<w:tab w:val=\"").append(t.align()).append('"');
                if (t.leader() != null) {
                    b.append(" w:leader=\"").append(t.leader()).append('"');
                }
                b.append(" w:pos=\"").append(t.pos()).append("\"/>");
            }
            b.append("</w:tabs>");
        }
        flag(b, p, ParaProps.NO_HYPHEN, p.noHyphen, "suppressAutoHyphens");
        flag(b, p, ParaProps.BIDI, p.bidi, "bidi");
        flag(b, p, ParaProps.SNAP, p.snap, "snapToGrid");
        spacing(b, p);
        indent(b, p);
        flag(b, p, ParaProps.CONTEXTUAL, p.contextual, "contextualSpacing");
        if (p.has(ParaProps.ALIGN)) {
            String a = p.align;
            if (p.bidi && ("left".equals(a) || "right".equals(a))) {
                a = "left".equals(a) ? "right" : "left";
            }
            b.append("<w:jc w:val=\"").append(a).append("\"/>");
        }
        if (p.has(ParaProps.OUTLINE)) {
            b.append("<w:outlineLvl w:val=\"").append(p.outline).append("\"/>");
        }
        if (mark != null) {
            String r = rPrInner(mark, true);
            if (!r.isEmpty()) {
                b.append("<w:rPr>").append(r).append("</w:rPr>");
            }
        }
        if (extra != null) {
            b.append(extra);
        }
        return b.isEmpty() ? "" : "<w:pPr>" + b + "</w:pPr>";
    }

    private static void spacing(StringBuilder b, ParaProps p) {
        boolean before = p.has(ParaProps.BEFORE);
        boolean after = p.has(ParaProps.AFTER);
        boolean line = p.has(ParaProps.LINE);
        boolean ba = p.has(ParaProps.BEFORE_AUTO);
        boolean aa = p.has(ParaProps.AFTER_AUTO);
        if (!before && !after && !line && !ba && !aa) {
            return;
        }
        b.append("<w:spacing");
        if (before) {
            b.append(" w:before=\"").append(p.before).append('"');
        }
        if (ba) {
            b.append(" w:beforeAutospacing=\"").append(p.beforeAuto ? 1 : 0).append('"');
        }
        if (after) {
            b.append(" w:after=\"").append(p.after).append('"');
        }
        if (aa) {
            b.append(" w:afterAutospacing=\"").append(p.afterAuto ? 1 : 0).append('"');
        }
        if (line) {
            int v = p.line;
            if (v == 0) {
                b.append(" w:line=\"240\" w:lineRule=\"auto\"");
            } else if (p.lineMultiple) {
                b.append(" w:line=\"").append(Math.abs(v)).append("\" w:lineRule=\"auto\"");
            } else if (v < 0) {
                b.append(" w:line=\"").append(-v).append("\" w:lineRule=\"exact\"");
            } else {
                b.append(" w:line=\"").append(v).append("\" w:lineRule=\"atLeast\"");
            }
        }
        b.append("/>");
    }

    private static void indent(StringBuilder b, ParaProps p) {
        boolean l = p.has(ParaProps.LEFT);
        boolean r = p.has(ParaProps.RIGHT);
        boolean f = p.has(ParaProps.FIRST);
        if (!l && !r && !f) {
            return;
        }
        b.append("<w:ind");
        if (l) {
            b.append(" w:left=\"").append(p.left).append('"');
        }
        if (r) {
            b.append(" w:right=\"").append(p.right).append('"');
        }
        if (f) {
            if (p.first < 0) {
                b.append(" w:hanging=\"").append(-p.first).append('"');
            } else {
                b.append(" w:firstLine=\"").append(p.first).append('"');
            }
        }
        b.append("/>");
    }

    private static void flag(StringBuilder b, ParaProps p, int prop, boolean value, String tag) {
        if (p.has(prop)) {
            b.append("<w:").append(tag).append(value ? "/>" : " w:val=\"0\"/>");
        }
    }
}
