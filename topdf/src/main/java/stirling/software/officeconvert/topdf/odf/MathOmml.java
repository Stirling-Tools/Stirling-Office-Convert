package stirling.software.officeconvert.topdf.odf;

import java.util.List;
import java.util.Set;

import org.w3c.dom.Element;

final class MathOmml {

    static final String MATHML = "http://www.w3.org/1998/Math/MathML";

    static final String M = "http://schemas.openxmlformats.org/officeDocument/2006/math";

    private static final int MAX_DEPTH = 40;

    private static final int MAX_NODES = 20_000;

    private static final Set<String> LARGE = Set.of("∑", "∏", "∐", "∫", "∬", "∭", "∮", "∯", "∰", "⋃", "⋂", "⋁", "⋀",
            "⨁", "⨂", "⨀");

    private int nodes;

    private MathOmml() {}

    static Element root(Element content) {
        if (content == null) {
            return null;
        }
        if (Dom.is(content, MATHML, "math")) {
            return content;
        }
        return Dom.kid(content, MATHML, "math");
    }

    static String omml(Element math) {
        if (math == null) {
            return null;
        }
        String body = new MathOmml().children(math, 0);
        return body.isEmpty() ? null : "<m:oMath>" + body + "</m:oMath>";
    }

    private String children(Element e, int depth) {
        StringBuilder b = new StringBuilder();
        for (Element k : Dom.kids(e)) {
            b.append(node(k, depth + 1));
        }
        return b.toString();
    }

    private String node(Element e, int depth) {
        if (depth > MAX_DEPTH || ++nodes > MAX_NODES || !MATHML.equals(e.getNamespaceURI())) {
            return "";
        }
        List<Element> k = Dom.kids(e);
        return switch (Dom.local(e)) {
            case "semantics" -> k.isEmpty() ? "" : node(k.get(0), depth);
            case "annotation", "annotation-xml", "mspace", "none", "mprescripts", "maligngroup", "malignmark" -> "";
            case "mi" -> run(e, italic(e) ? null : "p");
            case "mn", "mo" -> run(e, "p");
            case "mtext", "ms" -> "<m:r><m:rPr><m:nor/></m:rPr><m:t xml:space=\"preserve\">" + Xml.esc(Dom.text(e))
                    + "</m:t></m:r>";
            case "mfrac" -> "<m:f>" + ("0".equals(e.getAttribute("linethickness").replaceAll("[^0-9.]", ""))
                    ? "<m:fPr><m:type m:val=\"noBar\"/></m:fPr>" : "") + arg("m:num", k, 0, depth)
                    + arg("m:den", k, 1, depth) + "</m:f>";
            case "msup" -> "<m:sSup>" + arg("m:e", k, 0, depth) + arg("m:sup", k, 1, depth) + "</m:sSup>";
            case "msub" -> "<m:sSub>" + arg("m:e", k, 0, depth) + arg("m:sub", k, 1, depth) + "</m:sSub>";
            case "msubsup" -> "<m:sSubSup>" + arg("m:e", k, 0, depth) + arg("m:sub", k, 1, depth)
                    + arg("m:sup", k, 2, depth) + "</m:sSubSup>";
            case "msqrt" -> "<m:rad><m:radPr><m:degHide m:val=\"1\"/></m:radPr><m:deg/><m:e>" + children(e, depth)
                    + "</m:e></m:rad>";
            case "mroot" -> "<m:rad>" + arg("m:deg", k, 1, depth) + arg("m:e", k, 0, depth) + "</m:rad>";
            case "mfenced" -> fenced(e, depth);
            case "munder", "mover", "munderover" -> script(e, k, depth);
            case "mtable" -> table(e, depth);
            case "menclose" -> "<m:borderBox><m:e>" + children(e, depth) + "</m:e></m:borderBox>";
            case "mphantom" -> "<m:phant><m:phantPr><m:show m:val=\"0\"/></m:phantPr><m:e>" + children(e, depth)
                    + "</m:e></m:phant>";
            default -> children(e, depth);
        };
    }

    private static boolean italic(Element e) {
        String t = Dom.text(e).trim();
        return t.codePointCount(0, t.length()) == 1 && !"normal".equals(e.getAttribute("mathvariant"));
    }

    private static String run(Element e, String style) {
        String t = Dom.text(e).trim();
        if (t.isEmpty()) {
            return "";
        }
        return "<m:r>" + (style == null ? "" : "<m:rPr><m:sty m:val=\"" + style + "\"/></m:rPr>")
                + "<m:t xml:space=\"preserve\">" + Xml.esc(t) + "</m:t></m:r>";
    }

    private String arg(String tag, List<Element> k, int i, int depth) {
        return "<" + tag + ">" + (i < k.size() ? node(k.get(i), depth) : "") + "</" + tag + ">";
    }

    private String fenced(Element e, int depth) {
        String open = e.hasAttribute("open") ? e.getAttribute("open") : "(";
        String close = e.hasAttribute("close") ? e.getAttribute("close") : ")";
        StringBuilder b = new StringBuilder("<m:d><m:dPr><m:begChr m:val=\"").append(Xml.esc(open))
                .append("\"/><m:endChr m:val=\"").append(Xml.esc(close)).append("\"/></m:dPr>");
        for (Element k : Dom.kids(e)) {
            b.append("<m:e>").append(node(k, depth + 1)).append("</m:e>");
        }
        return b.append("</m:d>").toString();
    }

    private String script(Element e, List<Element> k, int depth) {
        String local = Dom.local(e);
        boolean under = !local.equals("mover");
        boolean over = !local.equals("munder");
        Element base = k.isEmpty() ? null : k.get(0);
        String baseText = base == null ? "" : Dom.text(base).trim();
        String lower = under ? arg("m:sub", k, 1, depth) : "<m:sub/>";
        String upper = over ? arg("m:sup", k, under ? 2 : 1, depth) : "<m:sup/>";
        if (base != null && Dom.local(base).equals("mo") && LARGE.contains(baseText)) {
            return "<m:nary><m:naryPr><m:chr m:val=\"" + Xml.esc(baseText) + "\"/><m:limLoc m:val=\"undOvr\"/>"
                    + (under ? "" : "<m:subHide m:val=\"1\"/>") + (over ? "" : "<m:supHide m:val=\"1\"/>")
                    + "</m:naryPr>" + lower + upper + "<m:e/></m:nary>";
        }
        if (local.equals("mover") && k.size() > 1 && Dom.local(k.get(1)).equals("mo")) {
            return "<m:acc><m:accPr><m:chr m:val=\"" + Xml.esc(Dom.text(k.get(1)).trim()) + "\"/></m:accPr>"
                    + arg("m:e", k, 0, depth) + "</m:acc>";
        }
        String inner = base == null ? "" : node(base, depth);
        if (under) {
            inner = "<m:limLow><m:e>" + inner + "</m:e>" + arg("m:lim", k, 1, depth) + "</m:limLow>";
        }
        if (over) {
            inner = "<m:limUpp><m:e>" + inner + "</m:e>" + arg("m:lim", k, under ? 2 : 1, depth) + "</m:limUpp>";
        }
        return inner;
    }

    private String table(Element e, int depth) {
        StringBuilder b = new StringBuilder("<m:m>");
        for (Element row : Dom.kids(e, MATHML, "mtr")) {
            b.append("<m:mr>");
            for (Element cell : Dom.kids(row, MATHML, "mtd")) {
                b.append("<m:e>").append(children(cell, depth + 1)).append("</m:e>");
            }
            b.append("</m:mr>");
        }
        return b.append("</m:m>").toString();
    }
}
