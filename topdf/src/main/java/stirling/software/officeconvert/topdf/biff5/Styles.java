package stirling.software.officeconvert.topdf.biff5;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.topdf.xls.StyleNames;
import stirling.software.officeconvert.topdf.xls.Xml;

/** The workbook globals' FONT, FORMAT, XF and PALETTE records as styles.xml; cells refer to their XF by its index in
 * the file, which {@link #cellXf(int)} maps to the cellXfs written. */
final class Styles {

    private static final int MAX_XFS = 4050;

    private static final int FIRST_CUSTOM_FORMAT = 164;

    private static final int[] BUILT_IN = {0, 1, 2, 3, 4, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 37, 38,
        39, 40, 45, 46, 47, 48, 49};

    private final Text text;

    private final List<String> fonts = new ArrayList<>();

    final List<String> runFonts = new ArrayList<>();

    private final Map<Integer, Integer> formatIds = new HashMap<>();

    private final StringBuilder numFmts = new StringBuilder();

    private int nextFormat = FIRST_CUSTOM_FORMAT;

    private final Map<String, Integer> fills = new LinkedHashMap<>();

    private final Map<String, Integer> borders = new LinkedHashMap<>();

    private final List<String> styleXfs = new ArrayList<>();

    private final List<String> cellXfs = new ArrayList<>();

    private final Map<Integer, Integer> cellXfIndex = new HashMap<>();

    private final Map<Integer, Integer> styleXfIndex = new HashMap<>();

    private final int[] palette = new int[56];

    private boolean customPalette;

    private int xfCount;

    Styles(Text text) {
        this.text = text;
        fills.put("<fill><patternFill patternType=\"none\"/></fill>", 0);
        fills.put("<fill><patternFill patternType=\"gray125\"/></fill>", 1);
        borders.put("<border><left/><right/><top/><bottom/><diagonal/></border>", 0);
    }

    void font(Stream s) {
        int height = s.u16(0);
        int flags = s.u16(2);
        int color = s.u16(4);
        int weight = s.u16(6);
        int escapement = s.u16(8);
        int underline = s.u8(10);
        int family = s.u8(11);
        int charset = s.u8(12);
        String name = text.read(s, 15, s.u8(14));
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
        String u = StyleNames.underline(underline);
        if (u != null) {
            b.append("<u val=\"").append(u).append("\"/>");
        }
        if (escapement == 1 || escapement == 2) {
            b.append("<vertAlign val=\"").append(escapement == 1 ? "superscript" : "subscript").append("\"/>");
        }
        b.append("<sz val=\"").append(Math.max(1, height) / 20.0).append("\"/>").append(color(color, "color"));
        String n = Xml.attr(name.isEmpty() ? "Arial" : name);
        String tail = (family > 0 && family < 6 ? "<family val=\"" + family + "\"/>" : "")
                + (charset > 0 ? "<charset val=\"" + charset + "\"/>" : "");
        if (fonts.size() < 1024) {
            fonts.add("<font>" + b + "<name val=\"" + n + "\"/>" + tail + "</font>");
            runFonts.add("<rPr>" + b + "<rFont val=\"" + n + "\"/>" + tail + "</rPr>");
            if (fonts.size() == 4) {
                fonts.add(fonts.get(0));
                runFonts.add(runFonts.get(0));
            }
        }
    }

    void format(Stream s) {
        int id = s.u16(0);
        String code = text.read(s, 3, s.u8(2));
        int mapped = builtIn(id) ? id : nextFormat++;
        formatIds.put(id, mapped);
        numFmts.append("<numFmt numFmtId=\"").append(mapped).append("\" formatCode=\"").append(Xml.attr(code))
                .append("\"/>");
    }

    private static boolean builtIn(int id) {
        for (int b : BUILT_IN) {
            if (b == id) {
                return true;
            }
        }
        return false;
    }

    void palette(Stream s) {
        int n = Math.min(56, s.u16(0));
        for (int i = 0; i < n; i++) {
            palette[i] = s.u8(2 + 4 * i) << 16 | s.u8(3 + 4 * i) << 8 | s.u8(4 + 4 * i);
        }
        customPalette = n > 0;
    }

    void xf(Stream s) {
        int index = xfCount++;
        if (index >= MAX_XFS) {
            return;
        }
        int font = s.u16(0);
        int format = s.u16(2);
        int typeProt = s.u16(4);
        int align = s.u16(6);
        long area = s.i32(8) & 0xFFFFFFFFL;
        long border = s.i32(12) & 0xFFFFFFFFL;
        boolean style = (typeProt & 0x0004) != 0;
        int parent = typeProt >> 4 & 0x0FFF;
        int numFmt = formatIds.getOrDefault(format, builtIn(format) ? format : 0);
        int fill = add(fills, fill(area));
        int b = add(borders, border(area, border));
        StringBuilder x = new StringBuilder("<xf numFmtId=\"").append(numFmt).append("\" fontId=\"")
                .append(font < fonts.size() ? font : 0).append("\" fillId=\"").append(fill).append("\" borderId=\"")
                .append(b).append('"');
        if (!style) {
            x.append(" xfId=\"").append(styleXfIndex.getOrDefault(parent, 0)).append('"')
                    .append(" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"");
        }
        x.append('>').append(alignment(align));
        boolean locked = (typeProt & 0x0001) != 0;
        boolean hidden = (typeProt & 0x0002) != 0;
        if (!locked || hidden) {
            x.append("<protection locked=\"").append(locked ? 1 : 0).append("\" hidden=\"").append(hidden ? 1 : 0)
                    .append("\"/>");
        }
        x.append("</xf>");
        if (style) {
            styleXfIndex.put(index, styleXfs.size());
            styleXfs.add(x.toString());
        } else {
            cellXfIndex.put(index, cellXfs.size());
            cellXfs.add(x.toString());
        }
    }

    private static int add(Map<String, Integer> map, String item) {
        Integer known = map.get(item);
        if (known != null) {
            return known;
        }
        int i = map.size();
        map.put(item, i);
        return i;
    }

    private String fill(long area) {
        int pattern = (int) (area >> 16 & 0x3F);
        if (pattern == 0) {
            return "<fill><patternFill patternType=\"none\"/></fill>";
        }
        return "<fill><patternFill patternType=\"" + StyleNames.pattern(pattern) + "\">"
                + color((int) (area & 0x7F), "fgColor") + color((int) (area >> 7 & 0x7F), "bgColor")
                + "</patternFill></fill>";
    }

    private String border(long area, long border) {
        String top = line("top", (int) (border & 0x07), (int) (border >> 9 & 0x7F));
        String left = line("left", (int) (border >> 3 & 0x07), (int) (border >> 16 & 0x7F));
        String right = line("right", (int) (border >> 6 & 0x07), (int) (border >> 23 & 0x7F));
        String bottom = line("bottom", (int) (area >> 22 & 0x07), (int) (area >> 25 & 0x7F));
        return "<border>" + left + right + top + bottom + "<diagonal/></border>";
    }

    private String line(String side, int style, int color) {
        if (style == 0) {
            return "<" + side + "/>";
        }
        return "<" + side + " style=\"" + StyleNames.border(style) + "\">" + color(color, "color") + "</" + side + ">";
    }

    private static String alignment(int align) {
        StringBuilder a = new StringBuilder();
        String h = StyleNames.horizontal(align & 0x07);
        if (h != null) {
            a.append(" horizontal=\"").append(h).append('"');
        }
        String v = StyleNames.vertical(align >> 4 & 0x07);
        if (v != null) {
            a.append(" vertical=\"").append(v).append('"');
        }
        if ((align & 0x08) != 0) {
            a.append(" wrapText=\"1\"");
        }
        int orient = align >> 8 & 0x03;
        if (orient != 0) {
            a.append(" textRotation=\"").append(orient == 1 ? 255 : orient == 2 ? 90 : 180).append('"');
        }
        return a.isEmpty() ? "" : "<alignment" + a + "/>";
    }

    String color(int index, String element) {
        if (index >= 8 && index < 64 && customPalette) {
            return String.format("<%s rgb=\"FF%06X\"/>", element, palette[index - 8]);
        }
        if (index >= 0 && index < 64) {
            return "<" + element + " indexed=\"" + index + "\"/>";
        }
        return "fgColor".equals(element) || "bgColor".equals(element) ? "<" + element + " indexed=\"" + index + "\"/>"
                : "";
    }

    int cellXf(int index) {
        return cellXfIndex.getOrDefault(index, 0);
    }

    String runProperties(int font) {
        return font >= 0 && font < runFonts.size() ? runFonts.get(font) : "";
    }

    String xml() {
        StringBuilder b = new StringBuilder(Xml.HEAD).append("<styleSheet xmlns=\"").append(Xml.MAIN).append("\">");
        if (!numFmts.isEmpty()) {
            b.append("<numFmts count=\"").append(formatIds.size()).append("\">").append(numFmts).append("</numFmts>");
        }
        list(b, "fonts", fonts.isEmpty() ? List.of("<font><sz val=\"10\"/><name val=\"Arial\"/></font>") : fonts);
        list(b, "fills", List.copyOf(fills.keySet()));
        list(b, "borders", List.copyOf(borders.keySet()));
        list(b, "cellStyleXfs", styleXfs.isEmpty()
                ? List.of("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>") : styleXfs);
        list(b, "cellXfs", cellXfs.isEmpty()
                ? List.of("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>") : cellXfs);
        b.append("<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>");
        return b.append("</styleSheet>").toString();
    }

    private static void list(StringBuilder b, String name, List<String> items) {
        b.append('<').append(name).append(" count=\"").append(items.size()).append("\">");
        items.forEach(b::append);
        b.append("</").append(name).append('>');
    }
}
