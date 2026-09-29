package stirling.software.officeconvert.topdf.xlsx;

import java.awt.Color;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FontUnderline;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.extensions.XSSFCellBorder;
import org.apache.poi.xssf.usermodel.extensions.XSSFCellFill;
import org.openxmlformats.schemas.officeDocument.x2006.sharedTypes.STVerticalAlignRun;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTBorder;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTBorderPr;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCellAlignment;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTColor;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTFill;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTPatternFill;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTXf;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.STPatternType;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

final class StyleCache {

    private final StylesTable styles;

    private final ExcelColors colors;

    private final Map<Integer, CellFormat> byIndex = new HashMap<>();

    private final Map<Integer, FontSpec> fonts = new HashMap<>();

    private final CellFormat fallback;

    StyleCache(StylesTable styles, ExcelColors colors) {
        this.styles = styles;
        this.colors = colors;
        this.fallback = new CellFormat(defaultFont(), null, null, null, null, null, null, false, false, null, null,
                false, false, 0, 0, 0, "General");
    }

    FontSpec defaultFont() {
        if (styles == null) {
            return new FontSpec("Calibri", 11, false, false, null, false, Color.BLACK, null);
        }
        try {
            int fontId = 0;
            if (styles.getNumCellStyles() > 0 && styles._getStyleXfsSize() > 0) {
                CTXf xf = styles.getCellStyleXfAt(0);
                if (xf != null && xf.isSetFontId()) {
                    fontId = (int) xf.getFontId();
                }
            }
            return font(fontId);
        } catch (RuntimeException e) {
            return new FontSpec("Calibri", 11, false, false, null, false, Color.BLACK, null);
        }
    }

    CellFormat of(XSSFCellStyle style) {
        if (style == null) {
            return fallback;
        }
        return byIndex.computeIfAbsent((int) style.getIndex(), i -> {
            try {
                return build(style);
            } catch (RuntimeException e) {
                return fallback;
            }
        });
    }

    CellFormat at(int index) {
        if (styles == null || index < 0 || index >= styles.getNumCellStyles()) {
            return fallback;
        }
        try {
            return of(styles.getStyleAt(index));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    FontSpec font(int fontId) {
        return fonts.computeIfAbsent(fontId, id -> {
            if (styles == null || id < 0 || id >= styles.getFonts().size()) {
                return new FontSpec("Calibri", 11, false, false, null, false, Color.BLACK, null);
            }
            try {
                return font(styles.getFontAt(id));
            } catch (RuntimeException e) {
                return new FontSpec("Calibri", 11, false, false, null, false, Color.BLACK, null);
            }
        });
    }

    FontSpec font(XSSFFont f) {
        if (f == null) {
            return new FontSpec("Calibri", 11, false, false, null, false, Color.BLACK, null);
        }
        double size = f.getFontHeight() / 20.0;
        FontSpec.Underline u = switch (FontUnderline.valueOf(f.getUnderline())) {
            case SINGLE -> FontSpec.Underline.SINGLE;
            case DOUBLE -> FontSpec.Underline.DOUBLE;
            case SINGLE_ACCOUNTING -> FontSpec.Underline.SINGLE_ACCOUNTING;
            case DOUBLE_ACCOUNTING -> FontSpec.Underline.DOUBLE_ACCOUNTING;
            default -> FontSpec.Underline.NONE;
        };
        FontSpec.Offset offset = FontSpec.Offset.NONE;
        if (f.getCTFont().sizeOfVertAlignArray() > 0) {
            STVerticalAlignRun.Enum v = f.getCTFont().getVertAlignArray(0).getVal();
            if (v == STVerticalAlignRun.SUPERSCRIPT) {
                offset = FontSpec.Offset.SUPER;
            } else if (v == STVerticalAlignRun.SUBSCRIPT) {
                offset = FontSpec.Offset.SUB;
            }
        }
        CTColor c = f.getCTFont().sizeOfColorArray() > 0 ? f.getCTFont().getColorArray(0) : null;
        Color color = colors.resolve(c, Color.BLACK);
        return new FontSpec(f.getFontName(), size, f.getBold(), f.getItalic(), u, f.getStrikeout(), color, offset);
    }

    private CellFormat build(XSSFCellStyle style) {
        CTXf xf = style.getCoreXf();
        FontSpec font = xf != null && xf.isSetFontId() ? font((int) xf.getFontId()) : defaultFont();
        Color fill = null;
        CTBorder border = null;
        try {
            if (xf != null && xf.isSetFillId()) {
                XSSFCellFill cf = styles.getFillAt((int) xf.getFillId());
                fill = cf == null ? null : fill(cf.getCTFill());
            }
            if (xf != null && xf.isSetBorderId()) {
                XSSFCellBorder cb = styles.getBorderAt((int) xf.getBorderId());
                border = cb == null ? null : cb.getCTBorder();
            }
        } catch (RuntimeException ignored) {
            fill = null;
        }
        BorderLine left = null;
        BorderLine right = null;
        BorderLine top = null;
        BorderLine bottom = null;
        BorderLine diagonal = null;
        boolean up = false;
        boolean down = false;
        if (border != null) {
            left = line(border.isSetLeft() ? border.getLeft() : border.isSetStart() ? border.getStart() : null);
            right = line(border.isSetRight() ? border.getRight() : border.isSetEnd() ? border.getEnd() : null);
            top = line(border.isSetTop() ? border.getTop() : null);
            bottom = line(border.isSetBottom() ? border.getBottom() : null);
            diagonal = line(border.isSetDiagonal() ? border.getDiagonal() : null);
            up = border.isSetDiagonalUp() && border.getDiagonalUp();
            down = border.isSetDiagonalDown() && border.getDiagonalDown();
        }
        CellFormat.HAlign h = CellFormat.HAlign.GENERAL;
        CellFormat.VAlign v = CellFormat.VAlign.BOTTOM;
        boolean wrap = false;
        boolean shrink = false;
        int indent = 0;
        int rotation = 0;
        CTCellAlignment a = xf != null && xf.isSetAlignment() ? xf.getAlignment() : null;
        if (a != null) {
            h = hAlign(style.getAlignment());
            v = vAlign(style.getVerticalAlignment());
            wrap = a.isSetWrapText() && a.getWrapText();
            shrink = a.isSetShrinkToFit() && a.getShrinkToFit();
            indent = a.isSetIndent() ? (int) Math.min(250, a.getIndent()) : 0;
            rotation = a.isSetTextRotation() ? a.getTextRotation().intValue() : 0;
        }
        int formatIndex = style.getDataFormat();
        String formatString;
        try {
            formatString = style.getDataFormatString();
        } catch (RuntimeException e) {
            formatString = "General";
        }
        return new CellFormat(font, fill, left, right, top, bottom, diagonal, up, down, h, v, wrap, shrink, indent,
                rotation, formatIndex, formatString);
    }

    private BorderLine line(CTBorderPr pr) {
        if (pr == null || !pr.isSetStyle()) {
            return BorderLine.NONE;
        }
        BorderStyle style = BorderStyle.valueOf((short) (pr.getStyle().intValue() - 1));
        Color color = colors.resolve(pr.isSetColor() ? pr.getColor() : null, Color.BLACK);
        return new BorderLine(style, color);
    }

    Color fill(CTFill fill) {
        if (fill == null) {
            return null;
        }
        if (fill.isSetGradientFill()) {
            return gradient(fill);
        }
        if (!fill.isSetPatternFill()) {
            return null;
        }
        CTPatternFill p = fill.getPatternFill();
        STPatternType.Enum type = p.isSetPatternType() ? p.getPatternType() : null;
        if (type == null || type == STPatternType.NONE) {
            return null;
        }
        Color fg = colors.resolve(p.isSetFgColor() ? p.getFgColor() : null, Color.BLACK);
        if (type == STPatternType.SOLID) {
            return fg;
        }
        Color bg = colors.resolve(p.isSetBgColor() ? p.getBgColor() : null, Color.WHITE);
        double d = density(type);
        return new Color(mix(fg.getRed(), bg.getRed(), d), mix(fg.getGreen(), bg.getGreen(), d),
                mix(fg.getBlue(), bg.getBlue(), d));
    }

    private Color gradient(CTFill fill) {
        Node node = fill.getDomNode();
        if (!(node instanceof Element e)) {
            return null;
        }
        Element g = Dml.child(e, "gradientFill");
        List<Element> stops = Dml.children(g, "stop");
        if (stops.isEmpty()) {
            return null;
        }
        double r = 0;
        double gr = 0;
        double b = 0;
        for (Element stop : stops) {
            Color c = domColor(Dml.child(stop, "color"));
            r += c.getRed();
            gr += c.getGreen();
            b += c.getBlue();
        }
        int n = stops.size();
        return new Color((int) Math.round(r / n), (int) Math.round(gr / n), (int) Math.round(b / n));
    }

    private Color domColor(Element c) {
        if (c == null) {
            return Color.WHITE;
        }
        try {
            String rgb = Dml.attr(c, "rgb");
            Color base;
            if (rgb != null) {
                int v = (int) Long.parseLong(rgb, 16);
                base = new Color(v & 0xFFFFFF);
            } else if (Dml.attr(c, "theme") != null) {
                base = colors.themeColor(Integer.parseInt(Dml.attr(c, "theme")));
            } else if (Dml.attr(c, "indexed") != null) {
                base = colors.indexedColor(Integer.parseInt(Dml.attr(c, "indexed")), Color.WHITE);
            } else {
                return Color.WHITE;
            }
            if (base == null) {
                return Color.WHITE;
            }
            String tint = Dml.attr(c, "tint");
            return tint == null ? base : ExcelColors.tint(base, Double.parseDouble(tint));
        } catch (RuntimeException ex) {
            return Color.WHITE;
        }
    }

    private static int mix(int fg, int bg, double d) {
        return (int) Math.round(fg * d + bg * (1 - d));
    }

    private static double density(STPatternType.Enum t) {
        int v = t.intValue();
        if (v == STPatternType.INT_GRAY_0625) {
            return 0.0625;
        }
        if (v == STPatternType.INT_GRAY_125) {
            return 0.125;
        }
        if (v == STPatternType.INT_LIGHT_GRAY) {
            return 0.25;
        }
        if (v == STPatternType.INT_MEDIUM_GRAY) {
            return 0.5;
        }
        if (v == STPatternType.INT_DARK_GRAY) {
            return 0.75;
        }
        if (v == STPatternType.INT_DARK_HORIZONTAL || v == STPatternType.INT_DARK_VERTICAL
                || v == STPatternType.INT_DARK_DOWN || v == STPatternType.INT_DARK_UP) {
            return 0.5;
        }
        if (v == STPatternType.INT_DARK_GRID || v == STPatternType.INT_DARK_TRELLIS) {
            return 0.75;
        }
        return 0.25;
    }

    private static CellFormat.HAlign hAlign(HorizontalAlignment h) {
        if (h == null) {
            return CellFormat.HAlign.GENERAL;
        }
        return switch (h) {
            case LEFT -> CellFormat.HAlign.LEFT;
            case CENTER -> CellFormat.HAlign.CENTER;
            case RIGHT -> CellFormat.HAlign.RIGHT;
            case FILL -> CellFormat.HAlign.FILL;
            case JUSTIFY -> CellFormat.HAlign.JUSTIFY;
            case CENTER_SELECTION -> CellFormat.HAlign.CENTER_CONTINUOUS;
            case DISTRIBUTED -> CellFormat.HAlign.DISTRIBUTED;
            default -> CellFormat.HAlign.GENERAL;
        };
    }

    private static CellFormat.VAlign vAlign(VerticalAlignment v) {
        if (v == null) {
            return CellFormat.VAlign.BOTTOM;
        }
        return switch (v) {
            case TOP -> CellFormat.VAlign.TOP;
            case CENTER -> CellFormat.VAlign.CENTER;
            case JUSTIFY -> CellFormat.VAlign.JUSTIFY;
            case DISTRIBUTED -> CellFormat.VAlign.DISTRIBUTED;
            default -> CellFormat.VAlign.BOTTOM;
        };
    }
}
