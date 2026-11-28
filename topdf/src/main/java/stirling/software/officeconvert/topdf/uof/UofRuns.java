package stirling.software.officeconvert.topdf.uof;

import java.util.HashMap;
import java.util.Map;

import org.w3c.dom.Element;

final class UofRuns {

    private final Map<String, Element> paragraphStyles = new HashMap<>();

    private final Map<String, Element> runStyles = new HashMap<>();

    private final Map<String, String> fonts;

    UofRuns(Element root) {
        Element set = Uof.kid(root, "式样集");
        for (Element s : Uof.kids(set, "段落式样")) {
            String id = Uof.attr(s, "标识符");
            if (id != null) {
                paragraphStyles.putIfAbsent(id, s);
            }
        }
        for (Element s : Uof.kids(set, "句式样")) {
            String id = Uof.attr(s, "标识符");
            if (id != null) {
                runStyles.putIfAbsent(id, s);
            }
        }
        this.fonts = UofText.fonts(root);
    }

    Element runProperty(Element direct, Element paragraphProps, String name) {
        Element own = Uof.kid(direct, name);
        if (own != null) {
            return own;
        }
        Element found = fromChain(runStyles, Uof.attr(direct, "式样引用"), name, false);
        if (found != null) {
            return found;
        }
        Element paraRun = Uof.kid(paragraphProps, "句属性");
        own = Uof.kid(paraRun, name);
        if (own != null) {
            return own;
        }
        return fromChain(paragraphStyles, Uof.attr(paragraphProps, "式样引用"), name, true);
    }

    Element paragraphProperty(Element props, String name) {
        Element own = Uof.kid(props, name);
        if (own != null) {
            return own;
        }
        String ref = Uof.attr(props, "式样引用");
        int hops = 0;
        while (ref != null && hops++ < 16) {
            Element s = paragraphStyles.get(ref);
            if (s == null) {
                return null;
            }
            Element e = Uof.kid(s, name);
            if (e != null) {
                return e;
            }
            String base = Uof.attr(s, "基式样引用");
            ref = base == null || base.equals(ref) ? null : base;
        }
        return null;
    }

    private static Element fromChain(Map<String, Element> styles, String ref, String name, boolean runChild) {
        int hops = 0;
        while (ref != null && hops++ < 16) {
            Element s = styles.get(ref);
            if (s == null) {
                return null;
            }
            Element e = Uof.kid(runChild ? Uof.kid(s, "句属性") : s, name);
            if (e != null) {
                return e;
            }
            String base = Uof.attr(s, "基式样引用");
            ref = base == null || base.equals(ref) ? null : base;
        }
        return null;
    }

    String textBody(Element text, String placeholder) {
        StringBuilder b = new StringBuilder("<p:txBody><a:bodyPr wrap=\"square\"");
        if (text != null) {
            for (String[] side : new String[][] {{"左边距", "lIns"}, {"右边距", "rIns"}, {"上边距", "tIns"},
                    {"下边距", "bIns"}}) {
                double cm = Uof.number(Uof.attr(text, side[0]));
                if (cm >= 0 && cm < 100) {
                    b.append(' ').append(side[1]).append("=\"").append(Math.round(cm * 360_000)).append('"');
                }
            }
            String v = String.valueOf(Uof.attr(text, "垂直对齐"));
            String anchor = switch (v) {
                case "center", "middle" -> "ctr";
                case "bottom" -> "b";
                default -> "t";
            };
            b.append(" anchor=\"").append(anchor).append('"');
            if (String.valueOf(Uof.attr(text, "文字排列方向")).startsWith("vert")) {
                b.append(" vert=\"eaVert\"");
            }
        }
        b.append("><a:normAutofit/></a:bodyPr><a:lstStyle/>");
        boolean any = false;
        for (Element p : Uof.kids(text, "段落")) {
            any = true;
            b.append(paragraph(p, placeholder));
        }
        if (!any) {
            b.append("<a:p><a:endParaRPr lang=\"zh-CN\"/></a:p>");
        }
        b.append("</p:txBody>");
        return b.toString();
    }

    private String paragraph(Element p, String placeholder) {
        Element props = Uof.kid(p, "段落属性");
        StringBuilder b = new StringBuilder("<a:p><a:pPr");
        Element align = paragraphProperty(props, "对齐");
        String jc = switch (String.valueOf(Uof.attr(align, "水平对齐"))) {
            case "center" -> "ctr";
            case "right" -> "r";
            case "justified" -> "just";
            case "distributed" -> "dist";
            default -> "l";
        };
        b.append(" algn=\"").append(jc).append("\">");
        Element line = paragraphProperty(props, "行距");
        double lines = Uof.number(Uof.attr(line, "值"));
        if ("multi-lines".equals(Uof.attr(line, "类型")) && lines > 0 && lines < 10) {
            b.append("<a:lnSpc><a:spcPct val=\"").append(Math.round(lines * 100_000)).append("\"/></a:lnSpc>");
        }
        Element num = Uof.kid(props, "自动编号信息");
        b.append(num != null && "object".equals(placeholder) ? "<a:buFont typeface=\"Arial\"/><a:buChar char=\"•\"/>"
                : "<a:buNone/>");
        b.append("</a:pPr>");
        String last = "";
        for (Element r : Uof.kids(p, "句")) {
            String rPr = runProps(Uof.kid(r, "句属性"), props, placeholder);
            last = rPr;
            for (Element k : Uof.kids(r)) {
                switch (Uof.name(k)) {
                    case "文本串" -> {
                        String t = k.getTextContent();
                        if (!t.isEmpty()) {
                            b.append("<a:r>").append(rPr).append("<a:t>").append(Uof.esc(t)).append("</a:t></a:r>");
                        }
                    }
                    case "空格符" -> {
                        double n = Uof.number(Uof.attr(k, "个数"));
                        b.append("<a:r>").append(rPr).append("<a:t>")
                                .append(" ".repeat(Double.isNaN(n) ? 1 : (int) Math.clamp(n, 1, 200)))
                                .append("</a:t></a:r>");
                    }
                    case "制表符" -> b.append("<a:r>").append(rPr).append("<a:t>\t</a:t></a:r>");
                    case "换行符" -> b.append("<a:br>").append(rPr).append("</a:br>");
                    default -> {}
                }
            }
        }
        b.append(last.replace("<a:rPr", "<a:endParaRPr").replace("</a:rPr>", "</a:endParaRPr>"));
        b.append("</a:p>");
        return b.toString();
    }

    private String runProps(Element direct, Element paragraph, String placeholder) {
        StringBuilder b = new StringBuilder("<a:rPr lang=\"zh-CN\"");
        Element font = runProperty(direct, paragraph, "字体");
        double size = Uof.number(Uof.attr(font, "字号"));
        if (!(size > 0 && size < 4000)) {
            size = "title".equals(placeholder) ? 44 : 18;
        }
        b.append(" sz=\"").append(Math.round(size * 100)).append('"');
        if (Uof.truth(runProperty(direct, paragraph, "粗体"))) {
            b.append(" b=\"1\"");
        }
        if (Uof.truth(runProperty(direct, paragraph, "斜体"))) {
            b.append(" i=\"1\"");
        }
        Element under = runProperty(direct, paragraph, "下划线");
        String u = String.valueOf(Uof.attr(under, "类型"));
        if (under != null && !u.equals("none") && !u.equals("null") && !u.isEmpty()) {
            b.append(" u=\"").append(u.equals("double") ? "dbl" : "sng").append('"');
        }
        Element strike = runProperty(direct, paragraph, "删除线");
        String st = String.valueOf(Uof.attr(strike, "类型"));
        if (strike != null && !st.equals("none") && !st.equals("null") && !st.isEmpty()) {
            b.append(" strike=\"sngStrike\"");
        }
        String pos = Uof.text(runProperty(direct, paragraph, "位置")).strip();
        if (pos.startsWith("super")) {
            b.append(" baseline=\"30000\"");
        } else if (pos.startsWith("sub")) {
            b.append(" baseline=\"-25000\"");
        }
        b.append('>');
        String colour = font == null ? null : Uof.colour(Uof.attr(font, "颜色"));
        if (colour != null) {
            b.append("<a:solidFill><a:srgbClr val=\"").append(colour).append("\"/></a:solidFill>");
        }
        String latin = Uof.font(fonts, Uof.attr(font, "西文字体引用"));
        String east = Uof.font(fonts, Uof.attr(font, "中文字体引用"));
        if (latin != null) {
            b.append("<a:latin typeface=\"").append(Uof.esc(latin)).append("\"/>");
        }
        if (east != null) {
            b.append("<a:ea typeface=\"").append(Uof.esc(east)).append("\"/>");
        }
        b.append("</a:rPr>");
        return b.toString();
    }

}
