package stirling.software.officeconvert.topdf.uof;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.io.OfficeZip;

final class UofSlides {

    static final long EMU_PER_CM = 360_000;

    static final int MAX_SLIDES = 5_000;

    static final int MAX_SHAPES = 200_000;

    static final String P_NS = "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
            + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
            + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"";

    private final Element root;

    private final UofParts parts;

    private final UofUnits units;

    private final UofObjects objects;

    private final UofRuns runs;

    private final Map<String, Element> shapes = new HashMap<>();

    private final Map<String, Element> masters = new LinkedHashMap<>();

    private final Map<byte[], String> written = new IdentityHashMap<>();

    private int shapeCount;

    private long writtenChars;

    private int pictureCount;

    private UofSlides(Element root, UofParts parts) {
        this.root = root;
        this.parts = parts;
        Element rules = Uof.path(root, "演示文稿", "公用处理规则");
        String unit = Uof.text(Uof.kid(rules, "度量单位")).strip();
        this.units = UofUnits.of(unit.isEmpty() ? "cm" : unit);
        this.objects = new UofObjects(root, units);
        this.runs = new UofRuns(root);
        for (Element g : Uof.kids(Uof.kid(root, "对象集"), "图形")) {
            String id = Uof.attr(g, "标识符");
            if (id != null) {
                shapes.putIfAbsent(id, g);
            }
        }
        for (Element m : Uof.kids(Uof.path(root, "演示文稿", "主体", "母版集"), "母版")) {
            String id = Uof.attr(m, "标识符");
            if (id != null) {
                masters.putIfAbsent(id, m);
            }
        }
    }

    static void write(Element root, UofParts parts) throws IOException {
        new UofSlides(root, parts).write();
    }

    private long emu(String value) {
        long t = units.twips(value);
        return UofUnits.known(t) ? t * 635 : Long.MIN_VALUE;
    }

    private void write() throws IOException {
        long[] size = slideSize();
        List<Element> slides = Uof.kids(Uof.path(root, "演示文稿", "主体", "幻灯片集"), "幻灯片");
        if (slides.size() > MAX_SLIDES) {
            slides = slides.subList(0, MAX_SLIDES);
        }
        List<String> slideNames = new ArrayList<>();
        int n = 0;
        for (Element s : slides) {
            OfficeZip.checkNotInterrupted();
            n++;
            List<String[]> rels = new ArrayList<>();
            String xml = slide(s, rels, size);
            writtenChars += xml.length();
            if (writtenChars > parts.limit()) {
                throw new IOException("The UOF presentation is too large to convert");
            }
            parts.add("ppt/slides/slide" + n + ".xml", xml);
            StringBuilder r = new StringBuilder(UofParts.XML);
            r.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
                    .append("<Relationship Id=\"rL\" Type=\"").append(UofParts.REL)
                    .append("slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>");
            for (String[] rel : rels) {
                r.append("<Relationship Id=\"").append(rel[0]).append("\" Type=\"").append(UofParts.REL)
                        .append("image\" Target=\"../media/").append(rel[1]).append("\"/>");
            }
            r.append("</Relationships>");
            parts.add("ppt/slides/_rels/slide" + n + ".xml.rels", r.toString());
            slideNames.add("slide" + n);
        }
        UofPresentation.write(parts, slideNames, size);
    }

    private long[] slideSize() {
        Element rules = Uof.path(root, "演示文稿", "公用处理规则");
        Map<String, Element> setups = new HashMap<>();
        Element first = null;
        for (Element s : Uof.kids(Uof.kid(rules, "页面设置集"), "页面设置")) {
            String id = Uof.attr(s, "标识符");
            if (id != null) {
                setups.putIfAbsent(id, s);
            }
            if (first == null) {
                first = s;
            }
        }
        Element setup = null;
        for (Element m : masters.values()) {
            if ("slide".equals(Uof.attr(m, "类型"))) {
                setup = setups.get(String.valueOf(Uof.attr(m, "页面设置引用")));
                if (setup != null) {
                    break;
                }
            }
        }
        if (setup == null) {
            setup = first;
        }
        Element paper = Uof.kid(setup, "纸张");
        long w = emu(Uof.attr(paper, "宽度"));
        long h = emu(Uof.attr(paper, "高度"));
        if (w > 100_000 && h > 100_000 && w < 51_206_400 && h < 51_206_400) {
            return new long[] {w, h};
        }
        return new long[] {9_144_000, 6_858_000};
    }

    private String slide(Element s, List<String[]> rels, long[] size) throws IOException {
        StringBuilder b = new StringBuilder(UofParts.XML);
        b.append("<p:sld ").append(P_NS).append("><p:cSld>");
        String bg = background(Uof.kid(s, "背景"), rels);
        Element master = masters.get(String.valueOf(Uof.attr(s, "母版引用")));
        if (bg == null && master != null) {
            bg = background(Uof.kid(master, "背景"), rels);
        }
        if (bg != null) {
            b.append("<p:bg><p:bgPr>").append(bg).append("<a:effectLst/></p:bgPr></p:bg>");
        }
        b.append("<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
                + "<p:grpSpPr/>");
        int id = 2;
        if (master != null && !"false".equalsIgnoreCase(String.valueOf(Uof.attr(s, "显示背景对象")))) {
            for (Element a : ordered(Uof.kids(master, "锚点"))) {
                if (Uof.attr(a, "占位符") == null) {
                    id = shape(b, a, rels, id);
                }
            }
        }
        for (Element a : ordered(Uof.kids(s, "锚点"))) {
            id = shape(b, a, rels, id);
        }
        b.append("</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>");
        return b.toString();
    }

    private List<Element> ordered(List<Element> anchors) {
        List<Element> out = new ArrayList<>(anchors);
        out.sort(Comparator.comparingDouble(a -> {
            Element g = shapes.get(String.valueOf(Uof.attr(a, "图形引用")));
            double z = Uof.number(Uof.attr(g, "层次"));
            return Double.isNaN(z) ? 0 : z;
        }));
        return out;
    }

    private String background(Element bg, List<String[]> rels) throws IOException {
        if (bg == null) {
            return null;
        }
        String colour = Uof.colour(Uof.text(Uof.kid(bg, "颜色")));
        Element picture = Uof.kid(bg, "图片");
        if (picture != null) {
            byte[] data = objects.picture(String.valueOf(Uof.attr(picture, "名称")));
            String rel = data == null ? null : media(data, rels);
            if (rel != null) {
                return "<a:blipFill><a:blip r:embed=\"" + rel + "\"/><a:stretch><a:fillRect/></a:stretch></a:blipFill>";
            }
        }
        return colour == null ? null : "<a:solidFill><a:srgbClr val=\"" + colour + "\"/></a:solidFill>";
    }

    private String media(byte[] data, List<String[]> rels) throws IOException {
        String name = written.get(data);
        if (name == null) {
            String ext = UofParts.extension(data, null);
            if (ext == null) {
                return null;
            }
            name = "image" + (++pictureCount) + "." + ext;
            parts.add("ppt/media/" + name, data);
            written.put(data, name);
        }
        for (String[] r : rels) {
            if (r[1].equals(name)) {
                return r[0];
            }
        }
        String rel = "rI" + (rels.size() + 1);
        rels.add(new String[] {rel, name});
        return rel;
    }

    private int shape(StringBuilder b, Element anchor, List<String[]> rels, int id) throws IOException {
        if (shapeCount++ > MAX_SHAPES) {
            return id;
        }
        Element g = shapes.get(String.valueOf(Uof.attr(anchor, "图形引用")));
        if (g == null) {
            return id;
        }
        long x = emu(Uof.attr(anchor, "x坐标"));
        long y = emu(Uof.attr(anchor, "y坐标"));
        long w = emu(Uof.attr(anchor, "宽度"));
        long h = emu(Uof.attr(anchor, "高度"));
        Element props = Uof.path(g, "预定义图形", "属性");
        if (!(w >= 0)) {
            w = emu(Uof.text(Uof.kid(props, "宽度")));
        }
        if (!(h >= 0)) {
            h = emu(Uof.text(Uof.kid(props, "高度")));
        }
        if (x == Long.MIN_VALUE || y == Long.MIN_VALUE || w < 0 || h < 0 || w > 1L << 33 || h > 1L << 33) {
            return id;
        }
        String placeholder = Uof.attr(anchor, "占位符");
        if ("notes".equals(placeholder) || "page".equals(placeholder) || "handout".equals(placeholder)) {
            return id;
        }
        double rot = Uof.number(Uof.text(Uof.kid(props, "旋转角度")));
        String xfrm = "<a:xfrm" + (rot > 0 && rot < 360 ? " rot=\"" + Math.round((360 - rot) * 60000) % 21_600_000 + "\""
                : "") + "><a:off x=\"" + x + "\" y=\"" + y + "\"/><a:ext cx=\"" + w + "\" cy=\"" + h + "\"/></a:xfrm>";
        UofObjects.Shape found = objects.shape(Uof.attr(anchor, "图形引用"));
        byte[] picture = found == null ? null : found.picture();
        Element text = Uof.kid(g, "文本内容");
        if (picture != null && (text == null || Uof.kids(text, "段落").isEmpty())) {
            String rel = media(picture, rels);
            if (rel != null) {
                b.append("<p:pic><p:nvPicPr><p:cNvPr id=\"").append(id).append("\" name=\"Picture ").append(id)
                        .append("\"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed=\"").append(rel)
                        .append("\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr>").append(xfrm)
                        .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>");
                return id + 1;
            }
        }
        b.append("<p:sp><p:nvSpPr><p:cNvPr id=\"").append(id).append("\" name=\"Shape ").append(id)
                .append("\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>").append(xfrm).append("<a:prstGeom prst=\"")
                .append(geometry(g)).append("\"><a:avLst/></a:prstGeom>");
        String fill = Uof.colour(Uof.text(Uof.path(props, "填充", "颜色")));
        b.append(fill == null ? "<a:noFill/>" : "<a:solidFill><a:srgbClr val=\"" + fill + "\"/></a:solidFill>");
        String line = Uof.text(Uof.kid(props, "线型")).strip();
        String lineColour = Uof.colour(Uof.text(Uof.kid(props, "线颜色")));
        if (line.isEmpty() || line.equals("none")) {
            b.append("<a:ln><a:noFill/></a:ln>");
        } else {
            double pt = Uof.number(Uof.text(Uof.kid(props, "线粗细")));
            long lw = Double.isNaN(pt) || pt <= 0 ? 9525 : Math.round(Math.min(pt, 100) * 12700);
            b.append("<a:ln w=\"").append(lw).append("\"><a:solidFill><a:srgbClr val=\"")
                    .append(lineColour == null ? "000000" : lineColour).append("\"/></a:solidFill></a:ln>");
        }
        b.append("</p:spPr>");
        b.append(runs.textBody(text, placeholder));
        b.append("</p:sp>");
        return id + 1;
    }

    private static String geometry(Element g) {
        String name = Uof.text(Uof.path(g, "预定义图形", "名称")).strip();
        return switch (name) {
            case "椭圆", "圆", "ellipse" -> "ellipse";
            case "圆角矩形" -> "roundRect";
            case "直线", "line" -> "line";
            case "三角形", "等腰三角形" -> "triangle";
            default -> "rect";
        };
    }
}
