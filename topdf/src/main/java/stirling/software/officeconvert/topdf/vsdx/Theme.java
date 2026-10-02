package stirling.software.officeconvert.topdf.vsdx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import stirling.software.officeconvert.topdf.xls.Xml;

final class Theme {

    static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";

    static final String VT = "http://schemas.microsoft.com/office/visio/2012/theme";

    private static final String[] SCHEME = {"dk1", "lt1", "accent1", "accent2", "accent3", "accent4", "accent5",
        "accent6"};

    record Style(int fill, int line, int font) {}

    private final Map<String, String> scheme = new HashMap<>();

    private final List<List<String>> variationColors = new ArrayList<>();

    private final List<List<Style>> variationStyles = new ArrayList<>();

    private final List<Element> fills = new ArrayList<>();

    private final List<Element> lines = new ArrayList<>();

    private final List<Element> fonts = new ArrayList<>();

    private final List<Element> connectorFills = new ArrayList<>();

    private final List<Element> connectorLines = new ArrayList<>();

    private final List<Element> connectorFonts = new ArrayList<>();

    private final List<Element> lineEx = new ArrayList<>();

    private final List<Element> connectorLineEx = new ArrayList<>();

    final String minorFont;

    Theme(Document doc) {
        Element root = doc == null ? null : doc.getDocumentElement();
        String font = null;
        if (root != null) {
            Element clr = first(root, A, "clrScheme");
            if (clr != null) {
                for (Element e : Drawing.kids(clr)) {
                    String c = srgb(e);
                    if (c != null) {
                        scheme.put(e.getLocalName(), c);
                    }
                }
            }
            for (Element v : all(root, VT, "variationClrScheme")) {
                List<String> colors = new ArrayList<>();
                for (Element k : Drawing.kids(v)) {
                    colors.add(srgb(k));
                }
                variationColors.add(colors);
            }
            for (Element v : all(root, VT, "variationStyleScheme")) {
                List<Style> styles = new ArrayList<>();
                for (Element k : Drawing.kids(v)) {
                    styles.add(new Style(index(k, "fillIdx"), index(k, "lineIdx"), index(k, "fontIdx")));
                }
                variationStyles.add(styles);
            }
            Element fmt = first(root, A, "fmtScheme");
            if (fmt != null) {
                fills.addAll(Drawing.kids(first(fmt, A, "fillStyleLst")));
                lines.addAll(Drawing.kids(first(fmt, A, "lnStyleLst")));
            }
            Element conn = first(root, VT, "fmtConnectorScheme");
            if (conn != null) {
                connectorFills.addAll(Drawing.kids(first(conn, A, "fillStyleLst")));
                connectorLines.addAll(Drawing.kids(first(conn, A, "lnStyleLst")));
            }
            Element fs = first(root, VT, "fontStyles");
            if (fs != null) {
                fonts.addAll(Drawing.kids(fs));
            }
            Element cfs = first(root, VT, "connectorFontStyles");
            if (cfs != null) {
                connectorFonts.addAll(Drawing.kids(cfs));
            }
            Element ls = first(root, VT, "fmtSchemeLineStyles");
            if (ls != null) {
                for (Element k : Drawing.kids(ls)) {
                    lineEx.add(first(k, VT, "lineEx"));
                }
            }
            Element cls = first(root, VT, "fmtConnectorSchemeLineStyles");
            if (cls != null) {
                for (Element k : Drawing.kids(cls)) {
                    connectorLineEx.add(first(k, VT, "lineEx"));
                }
            }
            Element minor = first(root, A, "minorFont");
            Element latin = minor == null ? null : first(minor, A, "latin");
            font = latin == null ? null : Sheet.attr(latin, "typeface");
        }
        this.minorFont = font == null || font.isBlank() ? null : font;
    }

    private static int index(Element e, String name) {
        return (int) Cells.parse(Sheet.attr(e, name), 0);
    }

    private static Element first(Element root, String ns, String local) {
        if (root == null) {
            return null;
        }
        NodeList l = root.getElementsByTagNameNS(ns, local);
        return l.getLength() == 0 ? null : (Element) l.item(0);
    }

    private static List<Element> all(Element root, String ns, String local) {
        NodeList l = root.getElementsByTagNameNS(ns, local);
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < l.getLength(); i++) {
            out.add((Element) l.item(i));
        }
        return out;
    }

    private static String srgb(Element holder) {
        if (holder == null) {
            return null;
        }
        for (Element k : Drawing.kids(holder)) {
            if ("srgbClr".equals(k.getLocalName())) {
                return Sheet.attr(k, "val");
            }
            if ("sysClr".equals(k.getLocalName())) {
                return Sheet.attr(k, "lastClr");
            }
        }
        return null;
    }

    String color(int index, int variation) {
        if (index >= 100 && index < 107) {
            List<String> v = variationColors.isEmpty() ? null
                    : variationColors.get(Math.floorMod(variation, variationColors.size()));
            String c = v == null || index - 100 >= v.size() ? null : v.get(index - 100);
            return c != null ? c : scheme.get("accent1");
        }
        return index >= 0 && index < SCHEME.length ? scheme.get(SCHEME[index]) : scheme.get("accent1");
    }

    private Style style(int matrix, int variation) {
        if (matrix >= 100) {
            List<Style> v = variationStyles.isEmpty() ? null
                    : variationStyles.get(Math.floorMod(variation, variationStyles.size()));
            return v == null || matrix - 100 >= v.size() ? new Style(1, 1, 1) : v.get(matrix - 100);
        }
        return new Style(matrix, matrix, matrix);
    }

    String fill(int matrix, int colorIndex, int variation, boolean connector) {
        if (fills.isEmpty()) {
            return "<a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill>";
        }
        int idx = style(matrix, variation).fill();
        List<Element> list = connector && !connectorFills.isEmpty() ? connectorFills : fills;
        if (idx < 1 || idx > list.size()) {
            return null;
        }
        return paint(list.get(idx - 1), color(colorIndex, variation));
    }

    record Line(String fill, long width, int pattern, int begin, int end, int beginSize, int endSize) {}

    Line line(int matrix, int colorIndex, int variation, boolean connector) {
        if (lines.isEmpty()) {
            return new Line("<a:solidFill><a:srgbClr val=\"000000\"/></a:solidFill>", 9525, 1, 0, 0, 2, 2);
        }
        int idx = style(matrix, variation).line();
        List<Element> list = connector && !connectorLines.isEmpty() ? connectorLines : lines;
        List<Element> ex = connector && !connectorLineEx.isEmpty() ? connectorLineEx : lineEx;
        if (idx < 1 || idx > list.size()) {
            return null;
        }
        Element ln = list.get(idx - 1);
        Element solid = null;
        for (Element k : Drawing.kids(ln)) {
            if (k.getLocalName().endsWith("Fill")) {
                solid = k;
            }
        }
        Element e = idx <= ex.size() ? ex.get(idx - 1) : null;
        return new Line(solid == null ? null : paint(solid, color(colorIndex, variation)),
                (long) Cells.parse(Sheet.attr(ln, "w"), 9525), e == null ? 1 : index(e, "pattern"),
                e == null ? 0 : index(e, "start"), e == null ? 0 : index(e, "end"),
                e == null ? 2 : index(e, "startSize"), e == null ? 2 : index(e, "endSize"));
    }

    record Font(String rgb, String xml) {}

    String scheme(String name) {
        return scheme.get(name);
    }

    Font font(int matrix, int colorIndex, int variation, boolean connector) {
        if (fonts.isEmpty()) {
            return new Font("000000", "<a:srgbClr val=\"000000\"/>");
        }
        int idx = style(matrix, variation).font();
        List<Element> list = connector && !connectorFonts.isEmpty() ? connectorFonts : fonts;
        if (idx < 1 || idx > list.size()) {
            return null;
        }
        Element color = first(list.get(idx - 1), VT, "color");
        if (color == null) {
            return null;
        }
        for (Element k : Drawing.kids(color)) {
            String placeholder = color(colorIndex, variation);
            String val = Sheet.attr(k, "val");
            String rgb = "srgbClr".equals(k.getLocalName()) ? val
                    : "phClr".equals(val) ? placeholder : scheme.get(val == null ? "" : val);
            return new Font(rgb, clr(k, placeholder));
        }
        return null;
    }

    private String paint(Element fill, String placeholder) {
        if (fill == null) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        write(b, fill, placeholder, 0);
        return b.toString();
    }

    private void write(StringBuilder b, Element e, String placeholder, int depth) {
        if (depth > 12) {
            return;
        }
        if ("schemeClr".equals(e.getLocalName()) || "srgbClr".equals(e.getLocalName())) {
            b.append(clr(e, placeholder));
            return;
        }
        b.append("<a:").append(e.getLocalName());
        var attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            var a = attrs.item(i);
            if (a.getNamespaceURI() == null && !a.getNodeName().startsWith("xmlns")) {
                b.append(' ').append(a.getNodeName()).append("=\"").append(Xml.attr(a.getNodeValue())).append('"');
            }
        }
        b.append('>');
        for (Element k : Drawing.kids(e)) {
            if (A.equals(k.getNamespaceURI())) {
                write(b, k, placeholder, depth + 1);
            }
        }
        b.append("</a:").append(e.getLocalName()).append('>');
    }

    private String clr(Element e, String placeholder) {
        String val = Sheet.attr(e, "val");
        String rgb = "srgbClr".equals(e.getLocalName()) ? val
                : "phClr".equals(val) ? placeholder : scheme.get(val == null ? "" : val);
        if (rgb == null) {
            rgb = "000000";
        }
        StringBuilder b = new StringBuilder("<a:srgbClr val=\"").append(Xml.attr(rgb)).append("\">");
        for (Element m : Drawing.kids(e)) {
            String v = Sheet.attr(m, "val");
            if (v != null) {
                b.append("<a:").append(m.getLocalName()).append(" val=\"").append(Xml.attr(v)).append("\"/>");
            }
        }
        return b.append("</a:srgbClr>").toString();
    }
}
