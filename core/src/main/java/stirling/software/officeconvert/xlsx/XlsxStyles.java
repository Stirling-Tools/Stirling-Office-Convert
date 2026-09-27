package stirling.software.officeconvert.xlsx;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import stirling.software.officeconvert.sheet.CellStyle;
import stirling.software.officeconvert.sheet.CellStyle.Border;
import stirling.software.officeconvert.sheet.CellStyle.HAlign;
import stirling.software.officeconvert.sheet.CellStyle.VAlign;
import stirling.software.officeconvert.sheet.SheetXml;

final class XlsxStyles {

    static final String FONT = "Calibri";

    private record FontKey(float size, boolean bold, boolean italic, boolean underline, boolean strike, int rgb) {}

    private record BorderKey(Border left, Border right, Border top, Border bottom) {}

    private record XfKey(int numFmt, int font, int fill, int border, HAlign h, VAlign v, boolean wrap) {}

    private static final Map<String, Integer> BUILT_IN = Map.of(
            "General", 0, "0", 1, "0.00", 2, "#,##0", 3, "#,##0.00", 4, "0%", 9, "0.00%", 10);

    private final Map<FontKey, Integer> fonts = new LinkedHashMap<>();
    private final Map<Integer, Integer> fills = new LinkedHashMap<>();
    private final Map<BorderKey, Integer> borders = new LinkedHashMap<>();
    private final Map<String, Integer> numFmts = new LinkedHashMap<>();
    private final Map<XfKey, Integer> xfs = new LinkedHashMap<>();

    XlsxStyles() {
        index(CellStyle.DEFAULT);
    }

    int index(CellStyle s) {
        float size = Math.round(s.size() * 2f) / 2f;
        int font = fonts.computeIfAbsent(
                new FontKey(size, s.bold(), s.italic(), s.underline(), s.strike(), s.rgb()), k -> fonts.size());
        int fill = s.fill() < 0 ? 0 : fills.computeIfAbsent(s.fill() & 0xFFFFFF, k -> fills.size() + 2);
        BorderKey edges = new BorderKey(weight(s.left()), weight(s.right()), weight(s.top()), weight(s.bottom()));
        int border = borders.computeIfAbsent(edges, k -> borders.size());
        String code = s.format().excelCode();
        Integer builtIn = BUILT_IN.get(code);
        int numFmt = builtIn != null ? builtIn : numFmts.computeIfAbsent(code, k -> 164 + numFmts.size());
        return xfs.computeIfAbsent(new XfKey(numFmt, font, fill, border, s.horizontal(), s.vertical(), s.wrap()),
                k -> xfs.size());
    }

    String xml() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
                .append("<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        if (!numFmts.isEmpty()) {
            sb.append("<numFmts count=\"").append(numFmts.size()).append("\">");
            numFmts.forEach((code, id) -> {
                sb.append("<numFmt numFmtId=\"").append(id).append("\" formatCode=\"");
                SheetXml.escape(sb, code);
                sb.append("\"/>");
            });
            sb.append("</numFmts>");
        }
        sb.append("<fonts count=\"").append(fonts.size()).append("\">");
        for (FontKey f : fonts.keySet()) {
            sb.append("<font>");
            if (f.bold()) {
                sb.append("<b/>");
            }
            if (f.italic()) {
                sb.append("<i/>");
            }
            if (f.strike()) {
                sb.append("<strike/>");
            }
            if (f.underline()) {
                sb.append("<u/>");
            }
            sb.append("<sz val=\"").append(size(f.size())).append("\"/>");
            if (f.rgb() >= 0) {
                sb.append("<color rgb=\"").append(argb(f.rgb())).append("\"/>");
            }
            sb.append("<name val=\"").append(FONT).append("\"/><family val=\"2\"/></font>");
        }
        sb.append("</fonts>");
        sb.append("<fills count=\"").append(fills.size() + 2).append("\">")
                .append("<fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>");
        for (int rgb : fills.keySet()) {
            sb.append("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"").append(argb(rgb))
                    .append("\"/><bgColor indexed=\"64\"/></patternFill></fill>");
        }
        sb.append("</fills>");
        sb.append("<borders count=\"").append(borders.size()).append("\">");
        for (BorderKey b : borders.keySet()) {
            sb.append("<border>");
            edge(sb, "left", b.left());
            edge(sb, "right", b.right());
            edge(sb, "top", b.top());
            edge(sb, "bottom", b.bottom());
            sb.append("<diagonal/></border>");
        }
        sb.append("</borders>");
        sb.append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>");
        sb.append("<cellXfs count=\"").append(xfs.size()).append("\">");
        for (XfKey x : xfs.keySet()) {
            sb.append("<xf numFmtId=\"").append(x.numFmt()).append("\" fontId=\"").append(x.font())
                    .append("\" fillId=\"").append(x.fill()).append("\" borderId=\"").append(x.border())
                    .append("\" xfId=\"0\"");
            if (x.numFmt() != 0) {
                sb.append(" applyNumberFormat=\"1\"");
            }
            if (x.font() != 0) {
                sb.append(" applyFont=\"1\"");
            }
            if (x.fill() != 0) {
                sb.append(" applyFill=\"1\"");
            }
            if (x.border() != 0) {
                sb.append(" applyBorder=\"1\"");
            }
            boolean aligned = x.h() != HAlign.GENERAL || x.v() != VAlign.BOTTOM || x.wrap();
            if (!aligned) {
                sb.append("/>");
                continue;
            }
            sb.append(" applyAlignment=\"1\"><alignment");
            if (x.h() != HAlign.GENERAL) {
                sb.append(" horizontal=\"").append(x.h().name().toLowerCase(Locale.ROOT)).append('"');
            }
            if (x.v() != VAlign.BOTTOM) {
                sb.append(" vertical=\"").append(x.v() == VAlign.CENTER ? "center" : "top").append('"');
            }
            if (x.wrap()) {
                sb.append(" wrapText=\"1\"");
            }
            sb.append("/></xf>");
        }
        sb.append("</cellXfs>");
        sb.append("<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>")
                .append("<dxfs count=\"0\"/><tableStyles count=\"0\" defaultTableStyle=\"TableStyleMedium2\""
                        + " defaultPivotStyle=\"PivotStyleLight16\"/></styleSheet>");
        return sb.toString();
    }

    private static Border weight(Border b) {
        if (!b.visible()) {
            return Border.NONE;
        }
        return new Border(b.width() > 2f ? 3f : b.width() > 1f ? 1.5f : 0.5f, Math.max(0, b.rgb()) & 0xFFFFFF);
    }

    private static void edge(StringBuilder sb, String side, Border b) {
        if (!b.visible()) {
            sb.append('<').append(side).append("/>");
            return;
        }
        String style = b.width() > 2f ? "thick" : b.width() > 1f ? "medium" : "thin";
        sb.append('<').append(side).append(" style=\"").append(style).append("\"><color rgb=\"")
                .append(argb(Math.max(0, b.rgb()))).append("\"/></").append(side).append('>');
    }

    private static String argb(int rgb) {
        return String.format(Locale.ROOT, "FF%06X", rgb & 0xFFFFFF);
    }

    private static String size(float pt) {
        float half = Math.round(Math.max(1f, Math.min(409f, pt)) * 2f) / 2f;
        return half == Math.rint(half) ? Integer.toString((int) half) : Float.toString(half);
    }
}
