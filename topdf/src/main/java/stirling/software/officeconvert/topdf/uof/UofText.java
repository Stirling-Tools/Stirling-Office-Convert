package stirling.software.officeconvert.topdf.uof;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.io.OfficeZip;

final class UofText {

    static final int MAX_DEPTH = 40;

    static final int MAX_NODES = 2_000_000;

    private final Element root;

    private final UofParts parts;

    private final UofUnits units;

    private final Map<String, String> fonts;

    private final UofObjects objects;

    private final Map<String, Integer> numbering = new LinkedHashMap<>();

    private final StringBuilder footnotes = new StringBuilder();

    private final StringBuilder endnotes = new StringBuilder();

    private final List<String> headerParts = new ArrayList<>();

    private int notes = 1;

    private int nodes;

    private int drawings;

    private final Set<String> expanding = new HashSet<>();

    private UofText(Element root, UofParts parts) {
        this.root = root;
        this.parts = parts;
        Element settings = Uof.path(root, "文字处理", "公用处理规则", "文档设置");
        this.units = UofUnits.of(Uof.text(Uof.kid(settings, "度量单位")));
        this.fonts = fonts(root);
        this.objects = new UofObjects(root, units);
    }

    static void write(Element root, UofParts parts) throws IOException {
        new UofText(root, parts).write();
    }

    static Map<String, String> fonts(Element root) {
        Map<String, String> out = new HashMap<>();
        for (Element f : Uof.kids(Uof.path(root, "式样集", "字体集"), "字体声明")) {
            String id = Uof.attr(f, "标识符");
            String name = Uof.fontName(Uof.attr(f, "名称"));
            if (id != null && name != null) {
                out.put(id, name);
            }
        }
        return out;
    }

    private void write() throws IOException {
        Element body = Uof.path(root, "文字处理", "主体");
        StringBuilder doc = new StringBuilder(1 << 16);
        doc.append(UofParts.XML).append("<w:document ").append(UofParts.W_NS).append("><w:body>");
        List<Element> items = Uof.kids(body);
        boolean leading = !items.isEmpty() && Uof.name(items.get(0)).equals("分节");
        Element section = leading ? items.get(0) : null;
        int count = items.size();
        for (int i = leading ? 1 : 0; i < count; i++) {
            Element e = items.get(i);
            OfficeZip.checkNotInterrupted();
            switch (Uof.name(e)) {
                case "段落" -> paragraph(doc, e, null, 0);
                case "文字表" -> table(doc, e, 0);
                case "分节" -> {
                    if (leading) {
                        doc.append("<w:p><w:pPr>").append(sectPr(section)).append("</w:pPr></w:p>");
                        section = e;
                    } else {
                        doc.append("<w:p><w:pPr>").append(sectPr(e)).append("</w:pPr></w:p>");
                        section = null;
                    }
                }
                default -> {}
            }
        }
        doc.append(sectPr(section)).append("</w:body></w:document>");
        parts.add("word/document.xml", doc.toString());
        parts.add("word/styles.xml", styles());
        parts.add("word/numbering.xml", numberingPart());
        parts.add("word/footnotes.xml", notesPart("footnotes", "footnote", footnotes));
        parts.add("word/endnotes.xml", notesPart("endnotes", "endnote", endnotes));
        parts.add("word/settings.xml", UofParts.XML + "<w:settings " + UofParts.W_NS + "><w:defaultTabStop w:val=\"420\"/>"
                + "<w:compat><w:compatSetting w:name=\"compatibilityMode\" w:uri=\"http://schemas.microsoft.com/office/word\""
                + " w:val=\"15\"/></w:compat></w:settings>");
        parts.wordPackage(headerParts);
    }

    private String sectPr(Element section) throws IOException {
        Element p = Uof.kid(section, "节属性");
        StringBuilder b = new StringBuilder("<w:sectPr>");
        for (String kind : new String[] {"页眉", "页脚"}) {
            Element hf = Uof.kid(p, kind);
            if (hf == null) {
                continue;
            }
            for (Element which : Uof.kids(hf)) {
                String type = switch (Uof.name(which)) {
                    case "首页页眉", "首页页脚" -> "first";
                    case "偶数页页眉", "偶数页页脚" -> "even";
                    default -> "default";
                };
                String rel = headerFooter(kind.equals("页眉") ? "header" : "footer", which);
                b.append("<w:").append(kind.equals("页眉") ? "headerReference" : "footerReference")
                        .append(" w:type=\"").append(type).append("\" r:id=\"").append(rel).append("\"/>");
            }
        }
        long w = 11906;
        long h = 16838;
        Element paper = Uof.kid(p, "纸张");
        if (paper != null) {
            long pw = units.twips(Uof.attr(paper, "宽度"));
            long ph = units.twips(Uof.attr(paper, "高度"));
            if (UofUnits.known(pw) && UofUnits.known(ph) && pw > 1000 && ph > 1000 && pw < 200_000 && ph < 200_000) {
                w = pw;
                h = ph;
            }
        }
        String orientation = Uof.text(Uof.kid(p, "纸张方向")).strip();
        boolean landscape = orientation.isEmpty() ? w > h : orientation.equalsIgnoreCase("landscape");
        if (landscape != w > h) {
            long t = w;
            w = h;
            h = t;
        }
        b.append("<w:pgSz w:w=\"").append(w).append("\" w:h=\"").append(h).append('"');
        if (landscape) {
            b.append(" w:orient=\"landscape\"");
        }
        b.append("/>");
        Element m = Uof.kid(p, "页边距");
        long top = margin(m, "上", 1440);
        long bottom = margin(m, "下", 1440);
        long left = margin(m, "左", 1800);
        long right = margin(m, "右", 1800);
        b.append("<w:pgMar w:top=\"").append(top).append("\" w:right=\"").append(right).append("\" w:bottom=\"")
                .append(bottom).append("\" w:left=\"").append(left).append("\" w:header=\"851\" w:footer=\"992\""
                        + " w:gutter=\"0\"/>");
        Element cols = Uof.kid(p, "分栏");
        String n = cols == null ? null : Uof.attr(cols, "栏数");
        double c = Uof.number(n);
        if (c >= 2 && c <= 12) {
            b.append("<w:cols w:num=\"").append((int) c).append("\" w:space=\"425\"/>");
        }
        if (Uof.truth(Uof.kid(p, "首页页眉页脚不同"))) {
            b.append("<w:titlePg/>");
        }
        String type = Uof.text(Uof.kid(p, "节类型")).strip();
        if (type.equals("continuous")) {
            b.append("<w:type w:val=\"continuous\"/>");
        }
        b.append("</w:sectPr>");
        return b.toString();
    }

    private long margin(Element m, String side, long fallback) {
        long v = units.twips(Uof.attr(m, side));
        return UofUnits.known(v) && v >= 0 && v < 30_000 ? v : fallback;
    }

    private String headerFooter(String kind, Element container) throws IOException {
        int n = headerParts.size() + 1;
        String part = "word/" + kind + n + ".xml";
        StringBuilder b = new StringBuilder();
        b.append(UofParts.XML).append("<w:").append(kind.equals("header") ? "hdr" : "ftr").append(' ')
                .append(UofParts.W_NS).append('>');
        int before = b.length();
        blocks(b, container, 1);
        if (b.length() == before) {
            b.append("<w:p/>");
        }
        b.append("</w:").append(kind.equals("header") ? "hdr" : "ftr").append('>');
        parts.add(part, b.toString());
        headerParts.add(kind + n);
        return "rH" + n;
    }

    private void blocks(StringBuilder b, Element container, int depth) throws IOException {
        for (Element e : Uof.kids(container)) {
            switch (Uof.name(e)) {
                case "段落" -> paragraph(b, e, null, depth);
                case "文字表" -> table(b, e, depth);
                default -> {}
            }
        }
    }

    private boolean budget(int depth) throws IOException {
        if ((nodes & 1023) == 1023) {
            OfficeZip.checkNotInterrupted();
        }
        return depth <= MAX_DEPTH && nodes++ < MAX_NODES;
    }

    private void grow(StringBuilder b) throws IOException {
        if (b.length() > parts.limit()) {
            throw new IOException("The UOF document is too large to convert");
        }
    }

    private void paragraph(StringBuilder b, Element p, String extraPPr, int depth) throws IOException {
        if (!budget(depth)) {
            return;
        }
        Element props = Uof.kid(p, "段落属性");
        b.append("<w:p><w:pPr>");
        b.append(paragraphProps(props, true));
        if (extraPPr != null) {
            b.append(extraPPr);
        }
        b.append("</w:pPr>");
        List<String> after = new ArrayList<>();
        for (Element k : Uof.kids(p)) {
            switch (Uof.name(k)) {
                case "句" -> run(b, k, after, depth);
                case "域开始", "域结束", "域代码" -> {}
                default -> {}
            }
        }
        b.append("</w:p>");
        for (String s : after) {
            b.append(s);
        }
        grow(b);
    }

    private void run(StringBuilder b, Element r, List<String> after, int depth) throws IOException {
        String rPr = "<w:rPr>" + runProps(Uof.kid(r, "句属性")) + "</w:rPr>";
        for (Element k : Uof.kids(r)) {
            switch (Uof.name(k)) {
                case "文本串" -> {
                    String t = k.getTextContent();
                    if (!t.isEmpty()) {
                        b.append("<w:r>").append(rPr).append("<w:t xml:space=\"preserve\">").append(Uof.esc(t))
                                .append("</w:t></w:r>");
                    }
                }
                case "空格符" -> {
                    double n = Uof.number(Uof.attr(k, "个数"));
                    int count = Double.isNaN(n) ? 1 : (int) Math.clamp(n, 1, 200);
                    b.append("<w:r>").append(rPr).append("<w:t xml:space=\"preserve\">").append(" ".repeat(count))
                            .append("</w:t></w:r>");
                }
                case "制表符" -> b.append("<w:r>").append(rPr).append("<w:tab/></w:r>");
                case "换行符" -> b.append("<w:r>").append(rPr).append("<w:br/></w:r>");
                case "分页符" -> b.append("<w:r>").append(rPr).append("<w:br w:type=\"page\"/></w:r>");
                case "分栏符" -> b.append("<w:r>").append(rPr).append("<w:br w:type=\"column\"/></w:r>");
                case "脚注", "尾注" -> note(b, k, rPr, Uof.name(k).equals("脚注"), depth);
                case "锚点" -> anchor(b, k, after, depth);
                default -> {}
            }
        }
    }

    private void note(StringBuilder b, Element n, String rPr, boolean foot, int depth) throws IOException {
        int id = notes++;
        String tag = foot ? "footnote" : "endnote";
        StringBuilder body = new StringBuilder();
        body.append("<w:").append(tag).append(" w:id=\"").append(id).append("\">");
        int before = body.length();
        boolean first = true;
        for (Element e : Uof.kids(n)) {
            if (Uof.name(e).equals("段落")) {
                StringBuilder para = new StringBuilder();
                paragraph(para, e, null, depth + 1);
                String s = para.toString();
                if (first) {
                    int at = s.indexOf("</w:pPr>");
                    if (at >= 0) {
                        s = s.substring(0, at + 8) + "<w:r><w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr><w:"
                                + tag + "Ref/></w:r>" + s.substring(at + 8);
                    }
                }
                body.append(s);
                first = false;
            } else if (Uof.name(e).equals("文字表")) {
                table(body, e, depth + 1);
            }
        }
        if (body.length() == before) {
            body.append("<w:p/>");
        }
        body.append("</w:").append(tag).append('>');
        StringBuilder all = foot ? footnotes : endnotes;
        all.append(body);
        grow(all);
        b.append("<w:r><w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr><w:").append(tag)
                .append("Reference w:id=\"").append(id).append("\"/></w:r>");
    }

    private void anchor(StringBuilder b, Element a, List<String> after, int depth) throws IOException {
        Element g = Uof.kid(a, "图形");
        String ref = g == null ? null : Uof.attr(g, "图形引用");
        UofObjects.Shape shape = objects.shape(ref);
        if (shape == null) {
            return;
        }
        if (shape.picture() != null) {
            String rel = parts.media(shape.picture(), shape.format());
            if (rel != null) {
                long[] size = objects.size(a, shape);
                b.append("<w:r>").append(UofParts.inlinePicture(rel, size[0], size[1], ++drawings)).append("</w:r>");
            }
        }
        if (shape.text() != null && expanding.add(ref)) {
            try {
                StringBuilder s = new StringBuilder();
                blocks(s, shape.text(), depth + 1);
                grow(s);
                after.add(s.toString());
            } finally {
                expanding.remove(ref);
            }
        }
    }

    private String paragraphProps(Element props, boolean withRun) {
        if (props == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        String style = Uof.attr(props, "式样引用");
        if (style != null && !style.isBlank()) {
            b.append("<w:pStyle w:val=\"").append(Uof.esc(styleId(style))).append("\"/>");
        }
        if (Uof.truth(Uof.kid(props, "与下段同页"))) {
            b.append("<w:keepNext/>");
        }
        if (Uof.truth(Uof.kid(props, "段中不分页"))) {
            b.append("<w:keepLines/>");
        }
        if (Uof.truth(Uof.kid(props, "段前分页"))) {
            b.append("<w:pageBreakBefore/>");
        }
        Element num = Uof.kid(props, "自动编号信息");
        if (num != null) {
            String ref = Uof.attr(num, "编号引用");
            double level = Uof.number(Uof.attr(num, "编号级别"));
            if (ref != null && !ref.isBlank()) {
                int id = numbering.computeIfAbsent(ref, k -> numbering.size() + 1);
                int lvl = Double.isNaN(level) ? 0 : (int) Math.clamp(level, 0, 8);
                b.append("<w:numPr><w:ilvl w:val=\"").append(lvl).append("\"/><w:numId w:val=\"").append(id)
                        .append("\"/></w:numPr>");
            }
        }
        Element border = Uof.kid(props, "边框");
        if (border != null) {
            b.append("<w:pBdr>").append(borders(border, new String[] {"上", "左", "下", "右"},
                    new String[] {"top", "left", "bottom", "right"})).append("</w:pBdr>");
        }
        String fill = Uof.colour(Uof.text(Uof.path(props, "填充", "颜色")));
        if (fill != null) {
            b.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(fill).append("\"/>");
        }
        Element tabs = Uof.kid(props, "制表位设置");
        if (tabs != null && !Uof.kids(tabs, "制表位").isEmpty()) {
            StringBuilder t = new StringBuilder();
            for (Element tab : Uof.kids(tabs, "制表位")) {
                long pos = units.twips(Uof.attr(tab, "位置"));
                if (!UofUnits.known(pos) || pos < 0 || pos > 100_000) {
                    continue;
                }
                String type = switch (String.valueOf(Uof.attr(tab, "类型"))) {
                    case "center" -> "center";
                    case "right" -> "right";
                    case "decimal" -> "decimal";
                    default -> "left";
                };
                String leader = switch (String.valueOf(Uof.attr(tab, "前导符"))) {
                    case "dotted" -> "dot";
                    case "solid" -> "underscore";
                    case "dash" -> "hyphen";
                    default -> "none";
                };
                t.append("<w:tab w:val=\"").append(type).append("\" w:leader=\"").append(leader).append("\" w:pos=\"")
                        .append(pos).append("\"/>");
            }
            if (!t.isEmpty()) {
                b.append("<w:tabs>").append(t).append("</w:tabs>");
            }
        }
        Element spacing = Uof.kid(props, "段间距");
        Element line = Uof.kid(props, "行距");
        StringBuilder sp = new StringBuilder();
        long before = spacingValue(Uof.kid(spacing, "段前距"));
        long after = spacingValue(Uof.kid(spacing, "段后距"));
        if (UofUnits.known(before)) {
            sp.append(" w:before=\"").append(before).append('"');
        }
        if (UofUnits.known(after)) {
            sp.append(" w:after=\"").append(after).append('"');
        }
        if (line != null) {
            String type = String.valueOf(Uof.attr(line, "类型"));
            String value = Uof.attr(line, "值");
            switch (type) {
                case "multi-lines" -> {
                    double v = Uof.number(value);
                    if (v > 0 && v < 20) {
                        sp.append(" w:line=\"").append(Math.round(240 * v)).append("\" w:lineRule=\"auto\"");
                    }
                }
                case "fixed", "at-least" -> {
                    long v = units.twips(value);
                    if (UofUnits.known(v) && v > 0 && v < 30_000) {
                        sp.append(" w:line=\"").append(v).append("\" w:lineRule=\"")
                                .append(type.equals("fixed") ? "exact" : "atLeast").append('"');
                    }
                }
                default -> {}
            }
        }
        if (!sp.isEmpty()) {
            b.append("<w:spacing").append(sp).append("/>");
        }
        Element ind = Uof.kid(props, "缩进");
        if (ind != null) {
            StringBuilder i = new StringBuilder();
            indent(i, Uof.kid(ind, "左"), "w:left", "w:leftChars");
            indent(i, Uof.kid(ind, "右"), "w:right", "w:rightChars");
            Element first = Uof.kid(ind, "首行");
            long f = units.twips(Uof.attr(Uof.kid(first, "绝对"), "值"));
            double chars = Uof.number(Uof.attr(Uof.kid(first, "相对"), "值"));
            if (UofUnits.known(f) && Math.abs(f) < 50_000) {
                i.append(f >= 0 ? " w:firstLine=\"" + f + '"' : " w:hanging=\"" + -f + '"');
            } else if (!Double.isNaN(chars) && Math.abs(chars) < 100) {
                long c = Math.round(chars * 100);
                i.append(c >= 0 ? " w:firstLineChars=\"" + c + '"' : " w:hangingChars=\"" + -c + '"');
            }
            if (!i.isEmpty()) {
                b.append("<w:ind").append(i).append("/>");
            }
        }
        Element align = Uof.kid(props, "对齐");
        String jc = align == null ? null : switch (String.valueOf(Uof.attr(align, "水平对齐"))) {
            case "center" -> "center";
            case "right" -> "right";
            case "justified" -> "both";
            case "distributed" -> "distribute";
            case "left" -> "left";
            default -> null;
        };
        if (jc != null) {
            b.append("<w:jc w:val=\"").append(jc).append("\"/>");
        }
        String level = Uof.text(Uof.kid(props, "大纲级别")).strip();
        double lv = Uof.number(level);
        if (lv >= 1 && lv <= 9) {
            b.append("<w:outlineLvl w:val=\"").append((int) lv - 1).append("\"/>");
        }
        if (withRun) {
            String r = runProps(Uof.kid(props, "句属性"));
            if (!r.isEmpty()) {
                b.append("<w:rPr>").append(r).append("</w:rPr>");
            }
        }
        return b.toString();
    }

    private long spacingValue(Element e) {
        if (e == null) {
            return Long.MIN_VALUE;
        }
        long abs = units.twips(Uof.attr(Uof.kid(e, "绝对值"), "值"));
        if (UofUnits.known(abs) && abs >= 0 && abs < 30_000) {
            return abs;
        }
        double lines = Uof.number(Uof.attr(Uof.kid(e, "相对值"), "值"));
        if (lines >= 0 && lines < 50) {
            return Math.round(lines * 312);
        }
        return Long.MIN_VALUE;
    }

    private void indent(StringBuilder b, Element side, String abs, String chars) {
        if (side == null) {
            return;
        }
        long v = units.twips(Uof.attr(Uof.kid(side, "绝对"), "值"));
        if (UofUnits.known(v) && Math.abs(v) < 50_000) {
            b.append(' ').append(abs).append("=\"").append(v).append('"');
            return;
        }
        double c = Uof.number(Uof.attr(Uof.kid(side, "相对"), "值"));
        if (!Double.isNaN(c) && Math.abs(c) < 100) {
            b.append(' ').append(chars).append("=\"").append(Math.round(c * 100)).append('"');
        }
    }

    private String runProps(Element props) {
        if (props == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        String style = Uof.attr(props, "式样引用");
        if (style != null && !style.isBlank() && !Uof.name(props.getParentNode()).equals("段落属性")) {
            b.append("<w:rStyle w:val=\"").append(Uof.esc(styleId(style))).append("\"/>");
        }
        Element font = Uof.kid(props, "字体");
        if (font != null) {
            String latin = Uof.font(fonts, Uof.attr(font, "西文字体引用"));
            String east = Uof.font(fonts, Uof.attr(font, "中文字体引用"));
            if (latin != null || east != null) {
                b.append("<w:rFonts");
                if (latin != null) {
                    b.append(" w:ascii=\"").append(Uof.esc(latin)).append("\" w:hAnsi=\"").append(Uof.esc(latin))
                            .append("\" w:cs=\"").append(Uof.esc(latin)).append('"');
                }
                if (east != null) {
                    b.append(" w:eastAsia=\"").append(Uof.esc(east)).append('"');
                }
                b.append("/>");
            }
        }
        flag(b, Uof.kid(props, "粗体"), "w:b");
        flag(b, Uof.kid(props, "斜体"), "w:i");
        Element caps = Uof.kid(props, "醒目字体");
        if (caps != null) {
            switch (String.valueOf(Uof.attr(caps, "类型"))) {
                case "uppercase" -> b.append("<w:caps/>");
                case "small-caps" -> b.append("<w:smallCaps/>");
                default -> {}
            }
        }
        Element strike = Uof.kid(props, "删除线");
        if (strike != null) {
            String t = String.valueOf(Uof.attr(strike, "类型"));
            if (t.equals("double")) {
                b.append("<w:dstrike/>");
            } else if (!t.isEmpty() && !t.equals("none") && !t.equals("null")) {
                b.append("<w:strike/>");
            }
        }
        if (Uof.truth(Uof.kid(props, "隐藏文字"))) {
            b.append("<w:vanish/>");
        }
        if (font != null) {
            String c = Uof.colour(Uof.attr(font, "颜色"));
            if (c != null) {
                b.append("<w:color w:val=\"").append(c).append("\"/>");
            }
        }
        long spacing = units.twips(Uof.text(Uof.kid(props, "字符间距")));
        if (UofUnits.known(spacing) && Math.abs(spacing) < 2000) {
            b.append("<w:spacing w:val=\"").append(spacing).append("\"/>");
        }
        double scale = Uof.number(Uof.text(Uof.kid(props, "缩放")));
        if (scale >= 1 && scale <= 600 && scale != 100) {
            b.append("<w:w w:val=\"").append(Math.round(scale)).append("\"/>");
        }
        if (font != null) {
            double size = Uof.number(Uof.attr(font, "字号"));
            if (size > 0 && size < 1600) {
                long half = Math.round(size * 2);
                b.append("<w:sz w:val=\"").append(half).append("\"/><w:szCs w:val=\"").append(half).append("\"/>");
            }
        }
        String highlight = Uof.colour(Uof.attr(Uof.kid(props, "突出显示"), "颜色"));
        if (highlight == null) {
            highlight = Uof.colour(Uof.attr(Uof.path(props, "填充", "图案"), "前景色"));
        }
        if (highlight != null && !highlight.equals("FFFFFF")) {
            b.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(highlight).append("\"/>");
        }
        Element under = Uof.kid(props, "下划线");
        if (under != null) {
            String t = String.valueOf(Uof.attr(under, "类型"));
            String u = switch (t) {
                case "single" -> "single";
                case "double" -> "double";
                case "thick" -> "thick";
                case "dotted" -> "dotted";
                case "dash" -> "dash";
                case "wave" -> "wave";
                case "words" -> "words";
                default -> t.equals("none") || t.equals("null") || t.isEmpty() ? null : "single";
            };
            if (u != null) {
                b.append("<w:u w:val=\"").append(u).append('"');
                String c = Uof.colour(Uof.attr(under, "颜色"));
                if (c != null) {
                    b.append(" w:color=\"").append(c).append('"');
                }
                b.append("/>");
            }
        }
        String position = Uof.text(Uof.kid(props, "位置")).strip();
        if (position.equals("super") || position.startsWith("super")) {
            b.append("<w:vertAlign w:val=\"superscript\"/>");
        } else if (position.startsWith("sub")) {
            b.append("<w:vertAlign w:val=\"subscript\"/>");
        }
        return b.toString();
    }

    private static void flag(StringBuilder b, Element e, String tag) {
        if (e == null) {
            return;
        }
        b.append('<').append(tag).append(Uof.truth(e) ? "/>" : " w:val=\"0\"/>");
    }

    private String borders(Element border, String[] uof, String[] word) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < uof.length; i++) {
            Element side = Uof.kid(border, uof[i]);
            if (side == null) {
                continue;
            }
            String type = String.valueOf(Uof.attr(side, "类型"));
            String val = switch (type) {
                case "single" -> "single";
                case "double" -> "double";
                case "dotted" -> "dotted";
                case "dash" -> "dashed";
                case "thick" -> "thick";
                default -> type.equals("none") || type.equals("null") ? "nil" : "single";
            };
            long w = units.twips(Uof.attr(side, "宽度"));
            long eighths = UofUnits.known(w) && w > 0 ? Math.clamp(Math.round(w * 8 / 20.0), 2, 96) : 4;
            String c = Uof.colour(Uof.attr(side, "颜色"));
            b.append("<w:").append(word[i]).append(" w:val=\"").append(val).append("\" w:sz=\"").append(eighths)
                    .append("\" w:space=\"0\" w:color=\"").append(c == null ? "auto" : c).append("\"/>");
        }
        return b.toString();
    }

    private void table(StringBuilder b, Element t, int depth) throws IOException {
        if (!budget(depth)) {
            return;
        }
        Element props = Uof.kid(t, "文字表属性");
        List<Long> grid = new ArrayList<>();
        for (Element c : Uof.kids(Uof.kid(props, "列宽集"), "列宽")) {
            long w = units.twips(Uof.text(c));
            grid.add(UofUnits.known(w) && w > 0 && w < 100_000 ? w : -1L);
        }
        List<Element> rows = Uof.kids(t, "行");
        int columns = grid.size();
        for (Element r : rows) {
            int n = 0;
            for (Element c : Uof.kids(r, "单元格")) {
                double span = Uof.number(Uof.attr(Uof.path(c, "单元格属性", "跨列"), "值"));
                n += Double.isNaN(span) ? 1 : (int) Math.clamp(span, 1, 64);
            }
            columns = Math.max(columns, n);
        }
        columns = Math.clamp(columns, 1, 64);
        long total = 9000;
        long known = 0;
        int unknown = 0;
        for (int i = 0; i < columns; i++) {
            long w = i < grid.size() ? grid.get(i) : -1;
            if (w > 0) {
                known += w;
            } else {
                unknown++;
            }
        }
        long rest = unknown == 0 ? 0 : Math.max(200, (total - known) / unknown);
        b.append("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/>");
        String align = Uof.text(Uof.kid(props, "对齐")).strip();
        if (align.equals("center") || align.equals("right")) {
            b.append("<w:jc w:val=\"").append(align).append("\"/>");
        }
        Element border = Uof.kid(props, "边框");
        if (border != null) {
            b.append("<w:tblBorders>").append(borders(border, new String[] {"上", "左", "下", "右"},
                    new String[] {"top", "left", "bottom", "right"})).append("</w:tblBorders>");
        }
        b.append("<w:tblLayout w:type=\"fixed\"/><w:tblCellMar><w:left w:w=\"108\" w:type=\"dxa\"/><w:right w:w=\"108\""
                + " w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>");
        long[] widths = new long[columns];
        for (int i = 0; i < columns; i++) {
            long w = i < grid.size() && grid.get(i) > 0 ? grid.get(i) : rest;
            widths[i] = w;
            b.append("<w:gridCol w:w=\"").append(w).append("\"/>");
        }
        b.append("</w:tblGrid>");
        int[] pending = new int[columns];
        String[] pendingHead = new String[columns];
        String[] pendingLook = new String[columns];
        for (Element r : rows) {
            if (!budget(depth)) {
                break;
            }
            b.append("<w:tr>");
            Element rp = Uof.kid(r, "表行属性");
            if (rp != null) {
                StringBuilder trPr = new StringBuilder();
                Element height = Uof.kid(rp, "高度");
                long fixed = units.twips(Uof.attr(height, "固定值"));
                long min = units.twips(Uof.attr(height, "最小值"));
                if (UofUnits.known(fixed) && fixed > 0 && fixed < 30_000) {
                    trPr.append("<w:trHeight w:val=\"").append(fixed).append("\" w:hRule=\"exact\"/>");
                } else if (UofUnits.known(min) && min > 0 && min < 30_000) {
                    trPr.append("<w:trHeight w:val=\"").append(min).append("\" w:hRule=\"atLeast\"/>");
                }
                if (Uof.truth(Uof.kid(rp, "表头行"))) {
                    trPr.append("<w:tblHeader/>");
                }
                if (!trPr.isEmpty()) {
                    b.append("<w:trPr>").append(trPr).append("</w:trPr>");
                }
            }
            int col = 0;
            List<Element> cells = Uof.kids(r, "单元格");
            int ci = 0;
            while (col < columns && (ci < cells.size() || pending[col] > 0)) {
                if (pending[col] > 0) {
                    pending[col]--;
                    b.append("<w:tc><w:tcPr>").append(pendingHead[col]).append("<w:vMerge/>").append(pendingLook[col])
                            .append("</w:tcPr><w:p/></w:tc>");
                    col += spanOf(pendingHead[col]);
                    continue;
                }
                Element c = cells.get(ci++);
                Element cp = Uof.kid(c, "单元格属性");
                double spanV = Uof.number(Uof.attr(Uof.kid(cp, "跨列"), "值"));
                int span = Double.isNaN(spanV) ? 1 : (int) Math.clamp(spanV, 1, columns - col);
                double rowsV = Uof.number(Uof.attr(Uof.kid(cp, "跨行"), "值"));
                int down = Double.isNaN(rowsV) ? 1 : (int) Math.clamp(rowsV, 1, 10_000);
                long width = 0;
                for (int k = col; k < col + span; k++) {
                    width += widths[k];
                }
                StringBuilder tcPr = new StringBuilder();
                tcPr.append("<w:tcW w:w=\"").append(width).append("\" w:type=\"dxa\"/>");
                if (span > 1) {
                    tcPr.append("<w:gridSpan w:val=\"").append(span).append("\"/>");
                }
                StringBuilder look = new StringBuilder();
                Element cb = Uof.kid(cp, "边框");
                if (cb != null) {
                    look.append("<w:tcBorders>").append(borders(cb, new String[] {"上", "左", "下", "右"},
                            new String[] {"top", "left", "bottom", "right"})).append("</w:tcBorders>");
                }
                String fill = Uof.colour(Uof.text(Uof.path(cp, "填充", "颜色")));
                if (fill != null) {
                    look.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(fill).append("\"/>");
                }
                b.append("<w:tc><w:tcPr>").append(tcPr);
                if (down > 1) {
                    b.append("<w:vMerge w:val=\"restart\"/>");
                    for (int k = col; k < col + span && k < columns; k++) {
                        pending[k] = 0;
                    }
                    pending[col] = down - 1;
                    pendingHead[col] = tcPr.toString();
                    pendingLook[col] = look.toString();
                }
                b.append(look);
                String v = switch (Uof.text(Uof.kid(cp, "垂直对齐方式")).strip()) {
                    case "center" -> "center";
                    case "bottom" -> "bottom";
                    default -> null;
                };
                if (v != null) {
                    b.append("<w:vAlign w:val=\"").append(v).append("\"/>");
                }
                b.append("</w:tcPr>");
                int before = b.length();
                for (Element k : Uof.kids(c)) {
                    switch (Uof.name(k)) {
                        case "段落" -> paragraph(b, k, null, depth + 1);
                        case "文字表" -> {
                            table(b, k, depth + 1);
                            b.append("<w:p/>");
                        }
                        default -> {}
                    }
                }
                if (b.length() == before) {
                    b.append("<w:p/>");
                }
                b.append("</w:tc>");
                col += span;
            }
            if (col == 0) {
                b.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(widths[0]).append("\" w:type=\"dxa\"/></w:tcPr><w:p/></w:tc>");
            }
            b.append("</w:tr>");
        }
        if (rows.isEmpty()) {
            b.append("<w:tr><w:tc><w:p/></w:tc></w:tr>");
        }
        b.append("</w:tbl>");
    }

    private static int spanOf(String tcPr) {
        int at = tcPr.indexOf("<w:gridSpan w:val=\"");
        if (at < 0) {
            return 1;
        }
        int from = at + "<w:gridSpan w:val=\"".length();
        int to = tcPr.indexOf('"', from);
        try {
            return Math.max(1, Integer.parseInt(tcPr.substring(from, to)));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    static String styleId(String id) {
        String s = id.strip();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 120; i++) {
            char c = s.charAt(i);
            b.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' ? c : '_');
        }
        return b.isEmpty() ? "s" : b.toString();
    }

    private String styles() {
        StringBuilder b = new StringBuilder();
        b.append(UofParts.XML).append("<w:styles ").append(UofParts.W_NS).append(">");
        b.append("<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Times New Roman\" w:hAnsi=\"Times New Roman\""
                + " w:eastAsia=\"宋体\" w:cs=\"Times New Roman\"/><w:sz w:val=\"21\"/><w:szCs w:val=\"21\"/>"
                + "<w:lang w:val=\"en-US\" w:eastAsia=\"zh-CN\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>"
                + "<w:jc w:val=\"both\"/></w:pPr></w:pPrDefault></w:docDefaults>");
        Element set = Uof.kid(root, "式样集");
        for (Element s : Uof.kids(set, "段落式样")) {
            String id = Uof.attr(s, "标识符");
            if (id == null || id.isBlank()) {
                continue;
            }
            b.append("<w:style w:type=\"paragraph\" w:styleId=\"").append(Uof.esc(styleId(id))).append('"');
            if (id.equals("Standard") || id.equals("Normal")) {
                b.append(" w:default=\"1\"");
            }
            b.append("><w:name w:val=\"").append(Uof.esc(name(s, id))).append("\"/>");
            String base = Uof.attr(s, "基式样引用");
            if (base != null && !base.isBlank() && !base.equals(id)) {
                b.append("<w:basedOn w:val=\"").append(Uof.esc(styleId(base))).append("\"/>");
            }
            b.append("<w:pPr>").append(paragraphProps(s, false)).append("</w:pPr>");
            b.append("<w:rPr>").append(runProps(Uof.kid(s, "句属性"))).append("</w:rPr></w:style>");
        }
        for (Element s : Uof.kids(set, "句式样")) {
            String id = Uof.attr(s, "标识符");
            if (id == null || id.isBlank()) {
                continue;
            }
            b.append("<w:style w:type=\"character\" w:styleId=\"").append(Uof.esc(styleId(id)))
                    .append("\"><w:name w:val=\"").append(Uof.esc(name(s, id))).append("\"/>");
            String base = Uof.attr(s, "基式样引用");
            if (base != null && !base.isBlank() && !base.equals(id)) {
                b.append("<w:basedOn w:val=\"").append(Uof.esc(styleId(base))).append("\"/>");
            }
            b.append("<w:rPr>").append(runProps(s)).append("</w:rPr></w:style>");
        }
        b.append("</w:styles>");
        return b.toString().replace("<w:pPr></w:pPr>", "").replace("<w:rPr></w:rPr>", "");
    }

    private static String name(Element s, String id) {
        String alias = Uof.attr(s, "别名");
        if (alias != null && !alias.isBlank()) {
            return alias;
        }
        String n = Uof.attr(s, "名称");
        return n == null || n.isBlank() ? id : n;
    }

    private String numberingPart() {
        StringBuilder b = new StringBuilder();
        b.append(UofParts.XML).append("<w:numbering ").append(UofParts.W_NS).append(">");
        Map<String, Element> lists = new HashMap<>();
        for (Element n : Uof.kids(Uof.path(root, "式样集", "自动编号集"), "自动编号")) {
            String id = Uof.attr(n, "标识符");
            if (id != null) {
                lists.putIfAbsent(id, n);
            }
        }
        for (Map.Entry<String, Integer> e : numbering.entrySet()) {
            Element list = lists.get(e.getKey());
            b.append("<w:abstractNum w:abstractNumId=\"").append(e.getValue()).append("\">");
            for (int lvl = 0; lvl < 9; lvl++) {
                Element level = null;
                for (Element l : Uof.kids(list, "级别")) {
                    double v = Uof.number(Uof.attr(l, "级别值"));
                    if (v == lvl) {
                        level = l;
                        break;
                    }
                }
                b.append(level(level, lvl));
            }
            b.append("</w:abstractNum>");
        }
        for (Integer id : numbering.values()) {
            b.append("<w:num w:numId=\"").append(id).append("\"><w:abstractNumId w:val=\"").append(id)
                    .append("\"/></w:num>");
        }
        b.append("</w:numbering>");
        return b.toString();
    }

    private String level(Element level, int lvl) {
        String format = level == null ? "decimal" : Uof.text(Uof.kid(level, "编号格式")).strip();
        String bullet = level == null ? null : Uof.text(Uof.kid(level, "项目符号"));
        String pattern = level == null ? "%" + (lvl + 1) + "." : Uof.text(Uof.kid(level, "编号格式表示")).strip();
        String fmt = switch (format.toLowerCase(Locale.ROOT)) {
            case "upper-roman" -> "upperRoman";
            case "lower-roman" -> "lowerRoman";
            case "upper-letter" -> "upperLetter";
            case "lower-letter" -> "lowerLetter";
            case "chinese-counting" -> "chineseCounting";
            case "chinese-legal-simplified" -> "chineseLegalSimplified";
            case "ideograph-traditional" -> "ideographTraditional";
            case "ideograph-zodiac" -> "ideographZodiac";
            case "decimal-enclosed-circle" -> "decimalEnclosedCircle";
            case "decimal-full-width" -> "decimalFullWidth";
            case "", "none" -> "none";
            default -> "decimal";
        };
        String text = pattern;
        if (bullet != null && !bullet.isBlank()) {
            fmt = "bullet";
            text = bullet.strip().substring(0, bullet.strip().offsetByCodePoints(0, 1));
        }
        double start = Uof.number(Uof.text(Uof.kid(level, "起始编号")));
        long indent = 420L * (lvl + 1);
        return "<w:lvl w:ilvl=\"" + lvl + "\"><w:start w:val=\"" + (Double.isNaN(start) ? 1 : (long) Math.clamp(start, 0,
                32767)) + "\"/><w:numFmt w:val=\"" + fmt + "\"/><w:lvlText w:val=\"" + Uof.esc(text)
                + "\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"" + indent + "\" w:hanging=\"420\"/></w:pPr></w:lvl>";
    }

    private static String notesPart(String root, String tag, StringBuilder body) {
        return UofParts.XML + "<w:" + root + " " + UofParts.W_NS + "><w:" + tag + " w:type=\"separator\" w:id=\"-1\">"
                + "<w:p><w:r><w:separator/></w:r></w:p></w:" + tag + "><w:" + tag
                + " w:type=\"continuationSeparator\" w:id=\"0\"><w:p><w:r><w:continuationSeparator/></w:r></w:p></w:"
                + tag + ">" + body + "</w:" + root + ">";
    }
}
