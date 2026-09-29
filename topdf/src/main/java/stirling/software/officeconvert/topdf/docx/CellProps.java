package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;

final class CellProps {

    Float width;
    String widthType;
    Integer gridSpan;
    String vMerge;
    String hMerge;
    Border top;
    Border left;
    Border bottom;
    Border right;
    Border insideH;
    Border insideV;
    Border tl2br;
    Border tr2bl;
    Color shading;
    Float marTop;
    Float marLeft;
    Float marBottom;
    Float marRight;
    String vAlign;
    Boolean noWrap;
    String textDirection;
    Boolean hideMark;

    CellProps copy() {
        CellProps c = new CellProps();
        c.mergeFrom(this);
        return c;
    }

    void mergeFrom(CellProps o) {
        if (o == null) {
            return;
        }
        if (o.width != null) {
            width = o.width;
            widthType = o.widthType;
        }
        gridSpan = o.gridSpan != null ? o.gridSpan : gridSpan;
        vMerge = o.vMerge != null ? o.vMerge : vMerge;
        hMerge = o.hMerge != null ? o.hMerge : hMerge;
        top = o.top != null ? o.top : top;
        left = o.left != null ? o.left : left;
        bottom = o.bottom != null ? o.bottom : bottom;
        right = o.right != null ? o.right : right;
        insideH = o.insideH != null ? o.insideH : insideH;
        insideV = o.insideV != null ? o.insideV : insideV;
        tl2br = o.tl2br != null ? o.tl2br : tl2br;
        tr2bl = o.tr2bl != null ? o.tr2bl : tr2bl;
        shading = o.shading != null ? o.shading : shading;
        marTop = o.marTop != null ? o.marTop : marTop;
        marLeft = o.marLeft != null ? o.marLeft : marLeft;
        marBottom = o.marBottom != null ? o.marBottom : marBottom;
        marRight = o.marRight != null ? o.marRight : marRight;
        vAlign = o.vAlign != null ? o.vAlign : vAlign;
        noWrap = o.noWrap != null ? o.noWrap : noWrap;
        textDirection = o.textDirection != null ? o.textDirection : textDirection;
        hideMark = o.hideMark != null ? o.hideMark : hideMark;
    }

    static CellProps parse(XEl tcPr, Theme theme) {
        CellProps c = new CellProps();
        c.apply(tcPr, theme);
        return c;
    }

    void apply(XEl tcPr, Theme theme) {
        if (tcPr == null) {
            return;
        }
        for (XEl k : tcPr.kids) {
            switch (k.name) {
                case "w:tcW" -> {
                    widthType = k.attr("type", "dxa");
                    width = TableProps.measure(k);
                }
                case "w:gridSpan" -> {
                    Integer g = Ooxml.integer(k.val());
                    gridSpan = g == null ? 1 : Math.max(1, Math.min(1000, g));
                }
                case "w:vMerge" -> vMerge = k.val() == null ? "continue" : k.val();
                case "w:hMerge" -> hMerge = k.val() == null ? "continue" : k.val();
                case "w:tcBorders" -> {
                    for (XEl b : k.kids) {
                        Border border = Border.parse(b, theme);
                        switch (b.name) {
                            case "w:top" -> top = border;
                            case "w:left", "w:start" -> left = border;
                            case "w:bottom" -> bottom = border;
                            case "w:right", "w:end" -> right = border;
                            case "w:insideH" -> insideH = border;
                            case "w:insideV" -> insideV = border;
                            case "w:tl2br" -> tl2br = border;
                            case "w:tr2bl" -> tr2bl = border;
                            default -> {
                            }
                        }
                    }
                }
                case "w:shd" -> {
                    Color c = Shading.parse(k, theme);
                    shading = c == null ? RunProps.AUTO : c;
                }
                case "w:tcMar" -> {
                    for (XEl m : k.kids) {
                        Float v = Ooxml.twips(m.attr("w"));
                        switch (m.name) {
                            case "w:top" -> marTop = v;
                            case "w:left", "w:start" -> marLeft = v;
                            case "w:bottom" -> marBottom = v;
                            case "w:right", "w:end" -> marRight = v;
                            default -> {
                            }
                        }
                    }
                }
                case "w:vAlign" -> vAlign = k.val();
                case "w:noWrap" -> noWrap = Ooxml.on(k);
                case "w:textDirection" -> textDirection = k.val();
                case "w:hideMark" -> hideMark = Ooxml.on(k);
                default -> {
                }
            }
        }
    }

    Color shadingColor() {
        return shading == null || shading == RunProps.AUTO ? null : shading;
    }
}
