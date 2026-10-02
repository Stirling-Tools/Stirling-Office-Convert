package stirling.software.officeconvert.topdf.xlsb;

import stirling.software.officeconvert.topdf.xls.StyleNames;
import stirling.software.officeconvert.topdf.xls.Xml;

/** A differential format (BrtDXF): the properties a conditional format or table style lays over a cell. */
final class Dxf {

    private static final int MAX_PROPERTIES = 256;

    private Dxf() {}

    static String read(Data d) {
        d.skip(4);
        int count = d.u16();
        StringBuilder font = new StringBuilder();
        String fontName = null;
        String fontColor = "";
        String numFmt = null;
        int numFmtId = -1;
        String pattern = null;
        String fg = "";
        String bg = "";
        String[] sides = new String[4];
        for (int i = 0; i < count && i < MAX_PROPERTIES && d.remaining() >= 4; i++) {
            int type = d.u16();
            int size = d.u16();
            int start = d.remaining();
            switch (type) {
                case 0 -> {
                    int p = d.u8();
                    pattern = StyleNames.pattern(p);
                }
                case 1 -> fg = d.color().element("fgColor");
                case 2 -> bg = d.color().element("bgColor");
                case 5 -> fontColor = d.color().element("color");
                case 6, 7, 8, 9 -> {
                    Data.Color c = d.color();
                    int style = d.u16();
                    String name = new String[] {"top", "bottom", "left", "right"}[type - 6];
                    sides[type - 6] = style == 0 ? "<" + name + "/>" : "<" + name + " style=\""
                            + StyleNames.border(style) + "\">" + c.element("color") + "</"
                            + name + ">";
                }
                case 24 -> fontName = shortString(d);
                case 25 -> font.append(d.u16() >= 700 ? "<b/>" : "<b val=\"0\"/>");
                case 26 -> {
                    int u = d.u16();
                    String name = StyleNames.underline(u);
                    font.append("<u val=\"").append(name == null ? "none" : name).append("\"/>");
                }
                case 27 -> {
                    int e = d.u16();
                    font.append("<vertAlign val=\"").append(e == 1 ? "superscript" : e == 2 ? "subscript" : "baseline")
                            .append("\"/>");
                }
                case 28 -> font.append(d.u8() != 0 ? "<i/>" : "<i val=\"0\"/>");
                case 29 -> font.append(d.u8() != 0 ? "<strike/>" : "<strike val=\"0\"/>");
                case 36 -> font.append("<sz val=\"").append(d.u16() / 20.0).append("\"/>");
                case 38 -> numFmt = shortString(d);
                case 41 -> numFmtId = d.u16();
                default -> {
                }
            }
            int used = start - d.remaining();
            int rest = size - 4 - used;
            if (rest > 0) {
                d.skip(rest);
            }
        }
        StringBuilder b = new StringBuilder("<dxf>");
        if (!font.isEmpty() || fontName != null || !fontColor.isEmpty()) {
            b.append("<font>").append(font);
            if (fontName != null && !fontName.isEmpty()) {
                b.append("<name val=\"").append(Xml.attr(fontName)).append("\"/>");
            }
            b.append(fontColor).append("</font>");
        }
        if (numFmt != null && numFmtId >= 0) {
            b.append("<numFmt numFmtId=\"").append(numFmtId).append("\" formatCode=\"").append(Xml.attr(numFmt))
                    .append("\"/>");
        }
        if (pattern != null || !fg.isEmpty() || !bg.isEmpty()) {
            b.append("<fill><patternFill").append(pattern == null ? "" : " patternType=\"" + pattern + "\"").append('>')
                    .append(fg).append(bg).append("</patternFill></fill>");
        }
        if (sides[0] != null || sides[1] != null || sides[2] != null || sides[3] != null) {
            b.append("<border>").append(or(sides[2])).append(or(sides[3])).append(or(sides[0])).append(or(sides[1]))
                    .append("</border>");
        }
        return b.append("</dxf>").toString();
    }

    private static String or(String s) {
        return s == null ? "" : s;
    }

    private static String shortString(Data d) {
        int n = d.u16();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n && d.remaining() >= 2; i++) {
            b.append((char) d.u16());
        }
        return b.toString();
    }
}
