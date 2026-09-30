package stirling.software.officeconvert.topdf.xls;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.poi.hssf.record.FormatRecord;
import org.apache.poi.hssf.usermodel.HSSFCellStyle;
import org.apache.poi.hssf.usermodel.HSSFFont;
import org.apache.poi.hssf.usermodel.HSSFPalette;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hssf.util.HSSFColor;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;

// Every XF of the workbook becomes the cellXfs entry of the same index, so cells keep their style numbers
final class StylesPart {

    static final int MAX_XFS = 65_000;

    private static final String[] PATTERNS = {"none", "solid", "mediumGray", "darkGray", "lightGray",
        "darkHorizontal", "darkVertical", "darkDown", "darkUp", "darkGrid", "darkTrellis", "lightHorizontal",
        "lightVertical", "lightDown", "lightUp", "lightGrid", "lightTrellis", "gray125", "gray0625"};

    private static final String[] BORDERS = {"none", "thin", "medium", "dashed", "dotted", "thick", "double", "hair",
        "mediumDashed", "dashDot", "mediumDashDot", "dashDotDot", "mediumDashDotDot", "slantDashDot"};

    private static final int[] BASIC = {0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF};

    private StylesPart() {}

    static String write(HSSFWorkbook wb) {
        StringBuilder b = new StringBuilder(8192).append(Xml.HEAD).append("<styleSheet xmlns=\"").append(Xml.MAIN)
                .append("\">");
        StringBuilder formats = new StringBuilder();
        int nf = 0;
        for (FormatRecord f : wb.getInternalWorkbook().getFormats()) {
            formats.append("<numFmt numFmtId=\"").append(f.getIndexCode()).append("\" formatCode=\"")
                    .append(Xml.attr(f.getFormatString())).append("\"/>");
            nf++;
        }
        if (nf > 0) {
            b.append("<numFmts count=\"").append(nf).append("\">").append(formats).append("</numFmts>");
        }
        int fonts = fontCount(wb);
        b.append("<fonts count=\"").append(fonts).append("\">");
        for (int i = 0; i < fonts; i++) {
            b.append(font(wb, i));
        }
        b.append("</fonts>");
        Map<String, Integer> fills = new LinkedHashMap<>();
        fills.put("<fill><patternFill patternType=\"none\"/></fill>", 0);
        fills.put("<fill><patternFill patternType=\"gray125\"/></fill>", 1);
        Map<String, Integer> borders = new LinkedHashMap<>();
        borders.put("<border><left/><right/><top/><bottom/><diagonal/></border>", 0);
        int count = Math.min(MAX_XFS, wb.getNumCellStyles());
        StringBuilder xfs = new StringBuilder(count * 160);
        for (int i = 0; i < count; i++) {
            HSSFCellStyle s = wb.getCellStyleAt(i);
            int fill = fills.computeIfAbsent(fill(s), k -> fills.size());
            int border = borders.computeIfAbsent(border(s), k -> borders.size());
            int font = s.getFontIndex() < fonts ? s.getFontIndex() : 0;
            xfs.append("<xf numFmtId=\"").append(s.getDataFormat() & 0xFFFF).append("\" fontId=\"").append(font)
                    .append("\" fillId=\"").append(fill).append("\" borderId=\"").append(border)
                    .append("\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\""
                            + " applyAlignment=\"1\">")
                    .append(alignment(s)).append("</xf>");
        }
        b.append("<fills count=\"").append(fills.size()).append("\">");
        fills.keySet().forEach(b::append);
        b.append("</fills><borders count=\"").append(borders.size()).append("\">");
        borders.keySet().forEach(b::append);
        b.append("</borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>"
                + "</cellStyleXfs><cellXfs count=\"").append(count).append("\">").append(xfs).append("</cellXfs>");
        b.append("<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>");
        b.append(palette(wb)).append("</styleSheet>");
        return b.toString();
    }

    // Font index 4 does not exist in a BIFF file; its slot repeats font 0 so later indexes stay in place
    static int fontCount(HSSFWorkbook wb) {
        int records = wb.getNumberOfFonts();
        return records > 4 ? records + 1 : records;
    }

    static HSSFFont fontAt(HSSFWorkbook wb, int index) {
        try {
            return wb.getFontAt(index == 4 ? 0 : index);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String font(HSSFWorkbook wb, int index) {
        HSSFFont f = fontAt(wb, index);
        if (f == null) {
            return "<font><sz val=\"10\"/><name val=\"Arial\"/></font>";
        }
        StringBuilder b = new StringBuilder("<font>");
        if (f.getBold()) {
            b.append("<b/>");
        }
        if (f.getItalic()) {
            b.append("<i/>");
        }
        if (f.getStrikeout()) {
            b.append("<strike/>");
        }
        b.append(color("color", f.getColor(), true));
        b.append("<sz val=\"").append(size(f)).append("\"/>");
        b.append("<name val=\"").append(Xml.attr(f.getFontName())).append("\"/>");
        String u = underline(f.getUnderline());
        if (u != null) {
            b.append("<u val=\"").append(u).append("\"/>");
        }
        if (f.getTypeOffset() == Font.SS_SUPER) {
            b.append("<vertAlign val=\"superscript\"/>");
        } else if (f.getTypeOffset() == Font.SS_SUB) {
            b.append("<vertAlign val=\"subscript\"/>");
        }
        return b.append("</font>").toString();
    }

    static String size(HSSFFont f) {
        double pt = Math.max(1, Math.min(409, f.getFontHeight() / 20.0));
        return pt == Math.rint(pt) ? Integer.toString((int) pt) : Double.toString(pt);
    }

    static String underline(byte u) {
        return switch (u) {
            case Font.U_SINGLE -> "single";
            case Font.U_DOUBLE -> "double";
            case Font.U_SINGLE_ACCOUNTING -> "singleAccounting";
            case Font.U_DOUBLE_ACCOUNTING -> "doubleAccounting";
            default -> null;
        };
    }

    static String color(String tag, int index, boolean font) {
        int i = index & 0xFFFF;
        if (i == 0x7FFF || font && i == 64) {
            return "";
        }
        if (i > 65) {
            return "";
        }
        return "<" + tag + " indexed=\"" + i + "\"/>";
    }

    private static String fill(HSSFCellStyle s) {
        FillPatternType p = s.getFillPattern();
        int code = p == null ? 0 : p.getCode();
        if (code <= 0 || code >= PATTERNS.length) {
            return "<fill><patternFill patternType=\"none\"/></fill>";
        }
        return "<fill><patternFill patternType=\"" + PATTERNS[code] + "\">" + color("fgColor",
                s.getFillForegroundColor(), false) + color("bgColor", s.getFillBackgroundColor(), false)
                + "</patternFill></fill>";
    }

    private static String border(HSSFCellStyle s) {
        return "<border>" + side("left", s.getBorderLeft(), s.getLeftBorderColor())
                + side("right", s.getBorderRight(), s.getRightBorderColor())
                + side("top", s.getBorderTop(), s.getTopBorderColor())
                + side("bottom", s.getBorderBottom(), s.getBottomBorderColor()) + "<diagonal/></border>";
    }

    private static String side(String tag, BorderStyle style, short color) {
        int code = style == null ? 0 : style.getCode();
        if (code <= 0 || code >= BORDERS.length) {
            return "<" + tag + "/>";
        }
        return "<" + tag + " style=\"" + BORDERS[code] + "\">" + color("color", color, false) + "</" + tag + ">";
    }

    private static String alignment(HSSFCellStyle s) {
        StringBuilder b = new StringBuilder("<alignment");
        HorizontalAlignment h = s.getAlignment();
        if (h != null && h != HorizontalAlignment.GENERAL) {
            b.append(" horizontal=\"").append(switch (h) {
                case LEFT -> "left";
                case CENTER -> "center";
                case RIGHT -> "right";
                case FILL -> "fill";
                case JUSTIFY -> "justify";
                case CENTER_SELECTION -> "centerContinuous";
                case DISTRIBUTED -> "distributed";
                default -> "general";
            }).append('"');
        }
        VerticalAlignment v = s.getVerticalAlignment();
        if (v != null && v != VerticalAlignment.BOTTOM) {
            b.append(" vertical=\"").append(switch (v) {
                case TOP -> "top";
                case CENTER -> "center";
                case JUSTIFY -> "justify";
                case DISTRIBUTED -> "distributed";
                default -> "bottom";
            }).append('"');
        }
        int rotation = s.getRotation();
        if (rotation != 0) {
            b.append(" textRotation=\"").append(rotation == 0xFF ? 255 : rotation < 0 ? 90 - rotation
                    : Math.min(90, rotation)).append('"');
        }
        if (s.getWrapText()) {
            b.append(" wrapText=\"1\"");
        }
        if (s.getIndention() > 0) {
            b.append(" indent=\"").append(Math.min(250, s.getIndention())).append('"');
        }
        if (s.getShrinkToFit()) {
            b.append(" shrinkToFit=\"1\"");
        }
        return b.append("/>").toString();
    }

    // Three bytes, not ARGB: the renderer takes the first three bytes of a custom palette entry as RGB
    private static String palette(HSSFWorkbook wb) {
        HSSFPalette palette = wb.getCustomPalette();
        StringBuilder b = new StringBuilder("<colors><indexedColors>");
        for (int i = 0; i < 64; i++) {
            int rgb = i < BASIC.length ? BASIC[i] : rgb(palette.getColor((short) i), 0);
            b.append("<rgbColor rgb=\"").append(String.format("%06X", rgb)).append("\"/>");
        }
        return b.append("</indexedColors></colors>").toString();
    }

    static int rgb(HSSFColor c, int fallback) {
        if (c == null) {
            return fallback;
        }
        short[] t = c.getTriplet();
        return (t[0] & 0xFF) << 16 | (t[1] & 0xFF) << 8 | t[2] & 0xFF;
    }
}
