package stirling.software.officeconvert.topdf.xlsb;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.topdf.xls.StyleNames;
import stirling.software.officeconvert.topdf.xls.Xml;

/** styles.bin read into the styles.xml the XLSX renderer reads, keeping the fonts for rich text runs. */
final class Styles {

    static final int MAX_ITEMS = 65_536;

    private static final int GRADIENT = 40;

    final List<String> fonts = new ArrayList<>();

    private final List<String> runFonts = new ArrayList<>();

    private final StringBuilder numFmts = new StringBuilder();

    private final List<String> fills = new ArrayList<>();

    private final List<String> borders = new ArrayList<>();

    private final List<String> cellXfs = new ArrayList<>();

    private final List<String> styleXfs = new ArrayList<>();

    private final List<String> cellStyles = new ArrayList<>();

    private final List<String> dxfs = new ArrayList<>();

    private final List<String> palette = new ArrayList<>();

    private int numFmtCount;

    static Styles read(InputStream in) throws IOException {
        Styles s = new Styles();
        if (in == null) {
            return s;
        }
        Records r = new Records(in);
        boolean cellBlock = false;
        while (r.next()) {
            Data d = r.data();
            switch (r.type()) {
                case Ids.CELLXFS -> cellBlock = true;
                case Ids.CELLXFS_END, Ids.CELLSTYLEXFS, Ids.CELLSTYLEXFS_END -> cellBlock = false;
                case Ids.NUMFMT -> s.numFmt(d);
                case Ids.FONT -> s.font(d);
                case Ids.FILL -> s.add(s.fills, fill(d));
                case Ids.BORDER -> s.add(s.borders, border(d));
                case Ids.XF -> s.add(cellBlock ? s.cellXfs : s.styleXfs, xf(d, cellBlock));
                case Ids.CELLSTYLE -> s.add(s.cellStyles, cellStyle(d));
                case Ids.DXF -> s.add(s.dxfs, Dxf.read(d));
                case Ids.RGBCOLOR -> s.add(s.palette, rgbColor(d));
                default -> {
                }
            }
        }
        return s;
    }

    private static String rgbColor(Data d) {
        int r = d.u8();
        int g = d.u8();
        int b = d.u8();
        return String.format("<rgbColor rgb=\"FF%02X%02X%02X\"/>", r, g, b);
    }

    private void add(List<String> list, String item) {
        if (list.size() < MAX_ITEMS && item != null) {
            list.add(item);
        }
    }

    private void numFmt(Data d) {
        int id = d.u16();
        String code = d.string();
        if (numFmtCount++ < MAX_ITEMS) {
            numFmts.append("<numFmt numFmtId=\"").append(id).append("\" formatCode=\"").append(Xml.attr(code))
                    .append("\"/>");
        }
    }

    private void font(Data d) {
        int height = d.u16();
        int flags = d.u16();
        int weight = d.u16();
        int escapement = d.u16();
        int underline = d.u8();
        int family = d.u8();
        int charset = d.u8();
        d.skip(1);
        Data.Color color = d.color();
        int scheme = d.u8();
        String name = d.string();
        StringBuilder b = new StringBuilder();
        if (weight >= 700) {
            b.append("<b/>");
        }
        if ((flags & 0x02) != 0) {
            b.append("<i/>");
        }
        if ((flags & 0x08) != 0) {
            b.append("<strike/>");
        }
        if ((flags & 0x10) != 0) {
            b.append("<outline/>");
        }
        if ((flags & 0x20) != 0) {
            b.append("<shadow/>");
        }
        String u = StyleNames.underline(underline);
        if (u != null) {
            b.append("<u val=\"").append(u).append("\"/>");
        }
        if (escapement == 1 || escapement == 2) {
            b.append("<vertAlign val=\"").append(escapement == 1 ? "superscript" : "subscript").append("\"/>");
        }
        b.append("<sz val=\"").append(height / 20.0).append("\"/>").append(color.element("color"));
        String tail = (family > 0 ? "<family val=\"" + family + "\"/>" : "")
                + (charset > 0 ? "<charset val=\"" + charset + "\"/>" : "")
                + (scheme == 1 ? "<scheme val=\"major\"/>" : scheme == 2 ? "<scheme val=\"minor\"/>" : "");
        String n = Xml.attr(name);
        if (fonts.size() < MAX_ITEMS) {
            fonts.add("<font>" + b + "<name val=\"" + n + "\"/>" + tail + "</font>");
            runFonts.add("<rPr>" + b + "<rFont val=\"" + n + "\"/>" + tail + "</rPr>");
        }
    }

    String runProperties(int font) {
        return font >= 0 && font < runFonts.size() ? runFonts.get(font) : "";
    }

    private static String fill(Data d) {
        int pattern = d.i32();
        if (pattern == GRADIENT) {
            d.skip(16);
            int type = d.i32();
            double angle = d.f64();
            double left = d.f64();
            double right = d.f64();
            double top = d.f64();
            double bottom = d.f64();
            int stops = d.i32();
            StringBuilder b = new StringBuilder("<fill><gradientFill");
            if (type == 1) {
                b.append(" type=\"path\" left=\"").append(left).append("\" right=\"").append(right)
                        .append("\" top=\"").append(top).append("\" bottom=\"").append(bottom).append('"');
            } else {
                b.append(" degree=\"").append(angle).append('"');
            }
            b.append('>');
            for (int i = 0; i < stops && i < 256 && d.remaining() >= 16; i++) {
                Data.Color c = d.color();
                double at = d.f64();
                b.append("<stop position=\"").append(at).append("\">").append(c.element("color")).append("</stop>");
            }
            return b.append("</gradientFill></fill>").toString();
        }
        Data.Color fg = d.color();
        Data.Color bg = d.color();
        String type = StyleNames.pattern(pattern);
        return "<fill><patternFill patternType=\"" + type + "\">" + fg.element("fgColor") + bg.element("bgColor")
                + "</patternFill></fill>";
    }

    private static String border(Data d) {
        int flags = d.u8();
        String[] sides = {"top", "bottom", "left", "right", "diagonal"};
        String[] xml = new String[5];
        for (int i = 0; i < 5; i++) {
            int style = d.u16();
            Data.Color c = d.color();
            String s = StyleNames.border(style);
            xml[i] = style == 0 ? "<" + sides[i] + "/>"
                    : "<" + sides[i] + " style=\"" + s + "\">" + c.element("color") + "</" + sides[i] + ">";
        }
        String diag = ((flags & 0x01) != 0 ? " diagonalDown=\"1\"" : "") + ((flags & 0x02) != 0 ? " diagonalUp=\"1\"" : "");
        return "<border" + diag + ">" + xml[2] + xml[3] + xml[0] + xml[1] + xml[4] + "</border>";
    }

    private static String xf(Data d, boolean cell) {
        int parent = d.u16();
        int numFmt = d.u16();
        int font = d.u16();
        int fill = d.u16();
        int border = d.u16();
        long flags = d.u32();
        int used = d.u16();
        StringBuilder b = new StringBuilder("<xf numFmtId=\"").append(numFmt).append("\" fontId=\"").append(font)
                .append("\" fillId=\"").append(fill).append("\" borderId=\"").append(border).append('"');
        if (cell) {
            b.append(" xfId=\"").append(parent == 0xFFFF ? 0 : parent).append('"');
            String[] names = {"applyNumberFormat", "applyFont", "applyAlignment", "applyBorder", "applyFill",
                "applyProtection"};
            for (int i = 0; i < names.length; i++) {
                if ((used & (1 << i)) != 0) {
                    b.append(' ').append(names[i]).append("=\"1\"");
                }
            }
        }
        b.append('>');
        int rotation = (int) (flags & 0xFF);
        int indent = (int) (flags >> 8 & 0xFF);
        int horizontal = (int) (flags >> 16 & 0x07);
        int vertical = (int) (flags >> 19 & 0x07);
        boolean wrap = (flags & 0x00400000L) != 0;
        boolean justifyLast = (flags & 0x00800000L) != 0;
        boolean shrink = (flags & 0x01000000L) != 0;
        int reading = (int) (flags >> 26 & 0x03);
        StringBuilder a = new StringBuilder();
        if (StyleNames.horizontal(horizontal) != null) {
            a.append(" horizontal=\"").append(StyleNames.horizontal(horizontal)).append('"');
        }
        if (StyleNames.vertical(vertical) != null) {
            a.append(" vertical=\"").append(StyleNames.vertical(vertical)).append('"');
        }
        if (rotation != 0) {
            a.append(" textRotation=\"").append(rotation).append('"');
        }
        if (wrap) {
            a.append(" wrapText=\"1\"");
        }
        if (indent != 0) {
            a.append(" indent=\"").append(indent).append('"');
        }
        if (justifyLast) {
            a.append(" justifyLastLine=\"1\"");
        }
        if (shrink) {
            a.append(" shrinkToFit=\"1\"");
        }
        if (reading != 0) {
            a.append(" readingOrder=\"").append(reading).append('"');
        }
        if (!a.isEmpty()) {
            b.append("<alignment").append(a).append("/>");
        }
        boolean locked = (flags & 0x10000000L) != 0;
        boolean hidden = (flags & 0x20000000L) != 0;
        if (!locked || hidden) {
            b.append("<protection locked=\"").append(locked ? 1 : 0).append("\" hidden=\"").append(hidden ? 1 : 0)
                    .append("\"/>");
        }
        return b.append("</xf>").toString();
    }

    private static String cellStyle(Data d) {
        int xf = d.i32();
        int flags = d.u16();
        int builtin = d.u8();
        int level = d.u8();
        String name = d.string();
        StringBuilder b = new StringBuilder("<cellStyle name=\"").append(Xml.attr(name)).append("\" xfId=\"")
                .append(Math.max(0, xf)).append('"');
        if ((flags & 0x01) != 0) {
            b.append(" builtinId=\"").append(builtin).append('"');
            if (builtin == 1 || builtin == 2) {
                b.append(" iLevel=\"").append(level).append('"');
            }
        }
        if ((flags & 0x02) != 0) {
            b.append(" hidden=\"1\"");
        }
        if ((flags & 0x04) != 0) {
            b.append(" customBuiltin=\"1\"");
        }
        return b.append("/>").toString();
    }

    String xml() {
        StringBuilder b = new StringBuilder(Xml.HEAD).append("<styleSheet xmlns=\"").append(Xml.MAIN).append("\">");
        if (numFmtCount > 0) {
            b.append("<numFmts count=\"").append(Math.min(numFmtCount, MAX_ITEMS)).append("\">").append(numFmts)
                    .append("</numFmts>");
        }
        list(b, "fonts", fonts.isEmpty() ? List.of("<font><sz val=\"11\"/><name val=\"Calibri\"/></font>") : fonts);
        list(b, "fills", fills.isEmpty() ? List.of("<fill><patternFill patternType=\"none\"/></fill>",
                "<fill><patternFill patternType=\"gray125\"/></fill>") : fills);
        list(b, "borders", borders.isEmpty() ? List.of("<border><left/><right/><top/><bottom/><diagonal/></border>")
                : borders);
        list(b, "cellStyleXfs", styleXfs.isEmpty()
                ? List.of("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>") : styleXfs);
        list(b, "cellXfs", cellXfs.isEmpty()
                ? List.of("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>") : cellXfs);
        if (!cellStyles.isEmpty()) {
            list(b, "cellStyles", cellStyles);
        }
        if (!dxfs.isEmpty()) {
            list(b, "dxfs", dxfs);
        }
        if (!palette.isEmpty()) {
            b.append("<colors><indexedColors>");
            palette.forEach(b::append);
            b.append("</indexedColors></colors>");
        }
        return b.append("</styleSheet>").toString();
    }

    private static void list(StringBuilder b, String name, List<String> items) {
        b.append('<').append(name).append(" count=\"").append(items.size()).append("\">");
        items.forEach(b::append);
        b.append("</").append(name).append('>');
    }
}
