package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;

final class TableProps {

    String styleId;
    Float width;
    String widthType;
    String jc;
    Float ind;
    Border top;
    Border left;
    Border bottom;
    Border right;
    Border insideH;
    Border insideV;
    Float marTop;
    Float marLeft;
    Float marBottom;
    Float marRight;
    Boolean fixed;
    Boolean firstRow;
    Boolean lastRow;
    Boolean firstCol;
    Boolean lastCol;
    Boolean noHBand;
    Boolean noVBand;
    Color shading;
    Float cellSpacing;
    XEl floating;
    Integer rowBand;
    Integer colBand;
    Boolean bidiVisual;

    TableProps copy() {
        TableProps t = new TableProps();
        t.mergeFrom(this);
        t.styleId = styleId;
        return t;
    }

    void mergeFrom(TableProps o) {
        if (o == null) {
            return;
        }
        if (o.width != null) {
            width = o.width;
            widthType = o.widthType;
        }
        jc = o.jc != null ? o.jc : jc;
        ind = o.ind != null ? o.ind : ind;
        top = o.top != null ? o.top : top;
        left = o.left != null ? o.left : left;
        bottom = o.bottom != null ? o.bottom : bottom;
        right = o.right != null ? o.right : right;
        insideH = o.insideH != null ? o.insideH : insideH;
        insideV = o.insideV != null ? o.insideV : insideV;
        marTop = o.marTop != null ? o.marTop : marTop;
        marLeft = o.marLeft != null ? o.marLeft : marLeft;
        marBottom = o.marBottom != null ? o.marBottom : marBottom;
        marRight = o.marRight != null ? o.marRight : marRight;
        fixed = o.fixed != null ? o.fixed : fixed;
        firstRow = o.firstRow != null ? o.firstRow : firstRow;
        lastRow = o.lastRow != null ? o.lastRow : lastRow;
        firstCol = o.firstCol != null ? o.firstCol : firstCol;
        lastCol = o.lastCol != null ? o.lastCol : lastCol;
        noHBand = o.noHBand != null ? o.noHBand : noHBand;
        noVBand = o.noVBand != null ? o.noVBand : noVBand;
        shading = o.shading != null ? o.shading : shading;
        cellSpacing = o.cellSpacing != null ? o.cellSpacing : cellSpacing;
        floating = o.floating != null ? o.floating : floating;
        rowBand = o.rowBand != null ? o.rowBand : rowBand;
        colBand = o.colBand != null ? o.colBand : colBand;
        bidiVisual = o.bidiVisual != null ? o.bidiVisual : bidiVisual;
    }

    static TableProps parse(XEl tblPr, Theme theme) {
        TableProps t = new TableProps();
        t.apply(tblPr, theme);
        return t;
    }

    void apply(XEl tblPr, Theme theme) {
        if (tblPr == null) {
            return;
        }
        for (XEl k : tblPr.kids) {
            switch (k.name) {
                case "w:tblStyle" -> styleId = k.val();
                case "w:tblW" -> {
                    widthType = k.attr("type", "dxa");
                    width = measure(k);
                }
                case "w:jc" -> jc = k.val();
                case "w:tblInd" -> ind = Ooxml.twips(k.attr("w"), 0);
                case "w:tblBorders" -> {
                    for (XEl b : k.kids) {
                        Border border = Border.parse(b, theme);
                        switch (b.name) {
                            case "w:top" -> top = border;
                            case "w:left", "w:start" -> left = border;
                            case "w:bottom" -> bottom = border;
                            case "w:right", "w:end" -> right = border;
                            case "w:insideH" -> insideH = border;
                            case "w:insideV" -> insideV = border;
                            default -> {
                            }
                        }
                    }
                }
                case "w:tblCellMar" -> {
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
                case "w:tblLayout" -> fixed = "fixed".equals(k.attr("type"));
                case "w:tblLook" -> look(k);
                case "w:shd" -> shading = Shading.parse(k, theme);
                case "w:tblCellSpacing" -> cellSpacing = Ooxml.twips(k.attr("w"), 0);
                case "w:tblpPr" -> floating = k;
                case "w:tblStyleRowBandSize" -> rowBand = Ooxml.integer(k.val());
                case "w:tblStyleColBandSize" -> colBand = Ooxml.integer(k.val());
                case "w:bidiVisual" -> bidiVisual = Ooxml.on(k);
                default -> {
                }
            }
        }
    }

    private void look(XEl k) {
        String v = k.val();
        if (v != null && k.attr("firstRow") == null) {
            try {
                int bits = Integer.parseInt(v, 16);
                firstRow = (bits & 0x20) != 0;
                lastRow = (bits & 0x40) != 0;
                firstCol = (bits & 0x80) != 0;
                lastCol = (bits & 0x100) != 0;
                noHBand = (bits & 0x200) != 0;
                noVBand = (bits & 0x400) != 0;
                return;
            } catch (NumberFormatException ignored) {
                // fall through to the attributes
            }
        }
        firstRow = Ooxml.flag(k.attr("firstRow"), false);
        lastRow = Ooxml.flag(k.attr("lastRow"), false);
        firstCol = Ooxml.flag(k.attr("firstColumn"), false);
        lastCol = Ooxml.flag(k.attr("lastColumn"), false);
        noHBand = Ooxml.flag(k.attr("noHBand"), false);
        noVBand = Ooxml.flag(k.attr("noVBand"), false);
    }

    static Float measure(XEl k) {
        String type = k.attr("type", "dxa");
        String w = k.attr("w");
        if (w == null) {
            return null;
        }
        if (type.equals("pct")) {
            String s = w.trim();
            if (s.endsWith("%")) {
                Float f = parseFloat(s.substring(0, s.length() - 1));
                return f == null ? null : f;
            }
            Integer i = Ooxml.integer(s);
            return i == null ? null : i / 50f;
        }
        if (type.equals("auto") || type.equals("nil")) {
            return 0f;
        }
        return Ooxml.twips(w);
    }

    private static Float parseFloat(String s) {
        try {
            float f = Float.parseFloat(s.trim());
            return Float.isFinite(f) ? f : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
