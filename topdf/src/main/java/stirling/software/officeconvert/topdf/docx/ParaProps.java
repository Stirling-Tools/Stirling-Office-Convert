package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class ParaProps {

    enum Rule {
        AUTO,
        EXACT,
        AT_LEAST
    }

    String styleId;
    String jc;
    Float indLeft;
    Float indRight;
    Float indFirst;
    Float before;
    Float after;
    Boolean beforeAuto;
    Boolean afterAuto;
    Float line;
    Rule lineRule;
    Boolean contextualSpacing;
    Boolean keepNext;
    Boolean keepLines;
    Boolean pageBreakBefore;
    Boolean widowControl;
    Boolean bidi;
    Boolean snapToGrid;
    Boolean suppressAutoHyphens;
    Boolean suppressLineNumbers;
    Integer numId;
    Integer ilvl;
    Integer outlineLvl;
    Border bdrTop;
    Border bdrLeft;
    Border bdrBottom;
    Border bdrRight;
    Border bdrBetween;
    Color shading;
    List<TabStop> tabs;
    XEl framePr;
    Boolean mirrorIndents;
    Boolean autoSpaceDE;
    Boolean autoSpaceDN;

    ParaProps copy() {
        ParaProps p = new ParaProps();
        p.mergeFrom(this);
        p.styleId = styleId;
        return p;
    }

    void mergeFrom(ParaProps o) {
        if (o == null) {
            return;
        }
        jc = o.jc != null ? o.jc : jc;
        indLeft = o.indLeft != null ? o.indLeft : indLeft;
        indRight = o.indRight != null ? o.indRight : indRight;
        indFirst = o.indFirst != null ? o.indFirst : indFirst;
        before = o.before != null ? o.before : before;
        after = o.after != null ? o.after : after;
        beforeAuto = o.beforeAuto != null ? o.beforeAuto : beforeAuto;
        afterAuto = o.afterAuto != null ? o.afterAuto : afterAuto;
        if (o.line != null) {
            line = o.line;
            lineRule = o.lineRule;
        }
        contextualSpacing = o.contextualSpacing != null ? o.contextualSpacing : contextualSpacing;
        keepNext = o.keepNext != null ? o.keepNext : keepNext;
        keepLines = o.keepLines != null ? o.keepLines : keepLines;
        pageBreakBefore = o.pageBreakBefore != null ? o.pageBreakBefore : pageBreakBefore;
        widowControl = o.widowControl != null ? o.widowControl : widowControl;
        bidi = o.bidi != null ? o.bidi : bidi;
        snapToGrid = o.snapToGrid != null ? o.snapToGrid : snapToGrid;
        suppressAutoHyphens = o.suppressAutoHyphens != null ? o.suppressAutoHyphens : suppressAutoHyphens;
        suppressLineNumbers = o.suppressLineNumbers != null ? o.suppressLineNumbers : suppressLineNumbers;
        if (o.numId != null) {
            numId = o.numId;
            if (o.ilvl != null) {
                ilvl = o.ilvl;
            }
        } else if (o.ilvl != null) {
            ilvl = o.ilvl;
        }
        outlineLvl = o.outlineLvl != null ? o.outlineLvl : outlineLvl;
        bdrTop = o.bdrTop != null ? o.bdrTop : bdrTop;
        bdrLeft = o.bdrLeft != null ? o.bdrLeft : bdrLeft;
        bdrBottom = o.bdrBottom != null ? o.bdrBottom : bdrBottom;
        bdrRight = o.bdrRight != null ? o.bdrRight : bdrRight;
        bdrBetween = o.bdrBetween != null ? o.bdrBetween : bdrBetween;
        shading = o.shading != null ? o.shading : shading;
        if (o.tabs != null) {
            tabs = mergeTabs(tabs, o.tabs);
        }
        framePr = o.framePr == null ? framePr : framePr == null ? o.framePr : framePr.overlay(o.framePr);
        mirrorIndents = o.mirrorIndents != null ? o.mirrorIndents : mirrorIndents;
        autoSpaceDE = o.autoSpaceDE != null ? o.autoSpaceDE : autoSpaceDE;
        autoSpaceDN = o.autoSpaceDN != null ? o.autoSpaceDN : autoSpaceDN;
    }

    static List<TabStop> mergeTabs(List<TabStop> base, List<TabStop> over) {
        List<TabStop> out = new ArrayList<>();
        if (base != null) {
            out.addAll(base);
        }
        for (TabStop t : over) {
            out.removeIf(b -> Math.abs(b.pos() - t.pos()) < 0.5f);
            if (t.kind() != TabStop.Kind.CLEAR) {
                out.add(t);
            }
        }
        out.sort(Comparator.comparingDouble(TabStop::pos));
        return out;
    }

    static ParaProps parse(XEl pPr, Theme theme) {
        ParaProps p = new ParaProps();
        p.apply(pPr, theme);
        return p;
    }

    void apply(XEl pPr, Theme theme) {
        if (pPr == null) {
            return;
        }
        for (XEl k : pPr.kids) {
            switch (k.name) {
                case "w:pStyle" -> styleId = k.val();
                case "w:jc" -> jc = k.val();
                case "w:ind" -> indent(k);
                case "w:spacing" -> spacing(k);
                case "w:contextualSpacing" -> contextualSpacing = Ooxml.on(k);
                case "w:keepNext" -> keepNext = Ooxml.on(k);
                case "w:keepLines" -> keepLines = Ooxml.on(k);
                case "w:pageBreakBefore" -> pageBreakBefore = Ooxml.on(k);
                case "w:widowControl" -> widowControl = Ooxml.on(k);
                case "w:bidi" -> bidi = Ooxml.on(k);
                case "w:snapToGrid" -> snapToGrid = Ooxml.on(k);
                case "w:suppressAutoHyphens" -> suppressAutoHyphens = Ooxml.on(k);
                case "w:suppressLineNumbers" -> suppressLineNumbers = Ooxml.on(k);
                case "w:mirrorIndents" -> mirrorIndents = Ooxml.on(k);
                case "w:autoSpaceDE" -> autoSpaceDE = Ooxml.on(k);
                case "w:autoSpaceDN" -> autoSpaceDN = Ooxml.on(k);
                case "w:outlineLvl" -> outlineLvl = Ooxml.integer(k.val());
                case "w:numPr" -> {
                    XEl id = k.child("w:numId");
                    XEl lvl = k.child("w:ilvl");
                    if (id != null) {
                        Integer n = Ooxml.integer(id.val());
                        numId = n == null ? 0 : n;
                    }
                    if (lvl != null) {
                        Integer l = Ooxml.integer(lvl.val());
                        ilvl = l == null ? 0 : Math.max(0, Math.min(8, l));
                    }
                }
                case "w:pBdr" -> {
                    for (XEl b : k.kids) {
                        Border border = Border.parse(b, theme);
                        switch (b.name) {
                            case "w:top" -> bdrTop = border;
                            case "w:left", "w:start" -> bdrLeft = border;
                            case "w:bottom" -> bdrBottom = border;
                            case "w:right", "w:end" -> bdrRight = border;
                            case "w:between" -> bdrBetween = border;
                            default -> {
                            }
                        }
                    }
                }
                case "w:shd" -> {
                    Color c = Shading.parse(k, theme);
                    shading = c == null ? RunProps.AUTO : c;
                }
                case "w:tabs" -> {
                    List<TabStop> list = new ArrayList<>();
                    for (XEl t : k.children("w:tab")) {
                        TabStop ts = TabStop.parse(t);
                        if (ts != null) {
                            list.add(ts);
                        }
                    }
                    tabs = list;
                }
                case "w:framePr" -> framePr = k;
                default -> {
                }
            }
        }
    }

    private void indent(XEl k) {
        Float left = Ooxml.twips(k.attr("left"));
        if (left == null) {
            left = Ooxml.twips(k.attr("start"));
        }
        Float right = Ooxml.twips(k.attr("right"));
        if (right == null) {
            right = Ooxml.twips(k.attr("end"));
        }
        if (left != null) {
            indLeft = left;
        }
        if (right != null) {
            indRight = right;
        }
        Float hanging = Ooxml.twips(k.attr("hanging"));
        Float first = Ooxml.twips(k.attr("firstLine"));
        if (hanging != null) {
            indFirst = -hanging;
        } else if (first != null) {
            indFirst = first;
        }
    }

    private void spacing(XEl k) {
        Float b = Ooxml.twips(k.attr("before"));
        Float a = Ooxml.twips(k.attr("after"));
        Integer bl = Ooxml.integer(k.attr("beforeLines"));
        Integer al = Ooxml.integer(k.attr("afterLines"));
        if (bl != null) {
            b = bl * 12f / 100f;
        }
        if (al != null) {
            a = al * 12f / 100f;
        }
        if (b != null) {
            before = Math.max(0, b);
        }
        if (a != null) {
            after = Math.max(0, a);
        }
        if (k.attr("beforeAutospacing") != null) {
            beforeAuto = Ooxml.flag(k.attr("beforeAutospacing"), false);
        }
        if (k.attr("afterAutospacing") != null) {
            afterAuto = Ooxml.flag(k.attr("afterAutospacing"), false);
        }
        String l = k.attr("line");
        if (l != null) {
            String rule = k.attr("lineRule");
            Integer v = Ooxml.integer(l);
            if (v != null) {
                if (rule == null || rule.equals("auto")) {
                    lineRule = Rule.AUTO;
                    line = Math.max(0.06f, Math.min(20, v / 240f));
                } else {
                    lineRule = rule.equals("exact") ? Rule.EXACT : Rule.AT_LEAST;
                    line = Math.max(0, Math.min(1584, Math.abs(v) / 20f));
                }
            }
        }
    }

    float left() {
        return indLeft == null ? 0 : indLeft;
    }

    float right() {
        return indRight == null ? 0 : indRight;
    }

    float first() {
        return indFirst == null ? 0 : indFirst;
    }

    Color shadingColor() {
        return shading == null || shading == RunProps.AUTO ? null : shading;
    }

    boolean hasBorders() {
        return visible(bdrTop) || visible(bdrBottom) || visible(bdrLeft) || visible(bdrRight) || visible(bdrBetween);
    }

    static boolean visible(Border b) {
        return b != null && b.visible();
    }
}
