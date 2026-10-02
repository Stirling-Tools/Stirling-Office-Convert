package stirling.software.officeconvert.topdf.odf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.io.PictureDecoder;

final class SlideWriter {

    static final int MAX_SHAPES = 20_000;

    private static final Set<String> PLACEHOLDERS = Set.of("title", "outline", "subtitle", "text", "notes", "object",
            "graphic", "chart", "table", "orgchart", "page", "handout", "vertical_title", "vertical_outline");

    private static final Set<String> DECORATIONS = Set.of("footer", "page-number", "date-time", "header");

    private final OdpWriter w;

    private final Element page;

    private final int number;

    private final Part part;

    private final StringBuilder shapes = new StringBuilder();

    private int id = 2;

    private Props pageProps;

    private String masterName;

    SlideWriter(OdpWriter w, Element page, int number, Part part) {
        this.w = w;
        this.page = page;
        this.number = number;
        this.part = part;
    }

    void write() throws IOException {
        masterName = Dom.attr(page, Ns.DRAW, "master-page-name");
        Element master = w.styles.master(masterName);
        if (master == null) {
            master = w.styles.firstMaster();
        }
        pageProps = w.styles.props("drawing-page", Dom.attr(page, Ns.DRAW, "style-name"), Styles.Scope.CONTENT,
                "drawing-page-properties", false);
        Props masterProps = w.styles.props("drawing-page", Dom.attr(master, Ns.DRAW, "style-name"),
                Styles.Scope.STYLES, "drawing-page-properties", false);
        String bg = null;
        if (!"false".equals(pageProps.get("presentation:background-visible"))) {
            bg = pageProps.has("draw:fill") || pageProps.has("draw:fill-color") ? background(pageProps)
                    : background(masterProps);
        }
        if (!"false".equals(pageProps.get("presentation:background-objects-visible")) && master != null) {
            for (Element k : Dom.kids(master)) {
                shape(k, Styles.Scope.STYLES, true);
            }
        }
        for (Element k : Dom.kids(page)) {
            shape(k, Styles.Scope.CONTENT, false);
        }
        boolean hidden = "hidden".equals(pageProps.get("presentation:visibility"));
        StringBuilder x = new StringBuilder(Xml.HEAD).append("<p:sld xmlns:a=\"").append(Xml.A).append("\" xmlns:r=\"")
                .append(Xml.R).append("\" xmlns:p=\"").append(Xml.P).append('"').append(hidden ? " show=\"0\"" : "")
                .append("><p:cSld>");
        if (bg != null) {
            x.append("<p:bg><p:bgPr>").append(bg).append("<a:effectLst/></p:bgPr></p:bg>");
        }
        x.append("<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>")
                .append("<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/>")
                .append("<a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>").append(shapes)
                .append("</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>");
        part.rels.add("slideLayout", "../slideLayouts/slideLayout1.xml");
        w.out.xml(part.name, Xml.CT + "presentationml.slide+xml", x);
        String file = part.name.substring(part.name.lastIndexOf('/') + 1);
        w.out.xml("ppt/slides/_rels/" + file + ".rels", null, part.rels.xml());
    }

    private String background(Props p) {
        String fill = Dml.fill(p, w.styles, this::blip);
        return fill == null || fill.equals("<a:noFill/>") ? null : fill;
    }

    private String blip(Element fillImage) {
        String href = Dom.attr(fillImage, Ns.XLINK, "href");
        byte[] data = OdfDocument.binaryData(fillImage);
        if (data == null) {
            if (OdfDocument.external(href)) {
                w.externalSkipped = true;
                return null;
            }
            data = w.doc.picture(href);
        }
        try {
            return part.picture(w.out, "ppt/media/", data);
        } catch (IOException e) {
            return null;
        }
    }

    private void shape(Element e, Styles.Scope scope, boolean master) throws IOException {
        if (id > MAX_SHAPES || !Ns.DRAW.equals(e.getNamespaceURI())) {
            return;
        }
        if ("none".equals(Dom.attr(e, Ns.DRAW, "display")) || "none".equals(e.getAttributeNS(
                "http://openoffice.org/2010/draw", "display"))) {
            return;
        }
        String layer = Dom.attr(e, Ns.DRAW, "layer");
        if (layer != null && w.unprinted.contains(layer)) {
            return;
        }
        String cls = Dom.attr(e, Ns.PRESENTATION, "class");
        if (cls != null) {
            if ("true".equals(Dom.attr(e, Ns.PRESENTATION, "placeholder"))) {
                return;
            }
            if (master && PLACEHOLDERS.contains(cls)) {
                return;
            }
            if (master && DECORATIONS.contains(cls) && !"true".equals(pageProps.get("presentation:display-"
                    + cls))) {
                return;
            }
        }
        switch (Dom.local(e)) {
            case "g", "a" -> {
                for (Element k : Dom.kids(e)) {
                    shape(k, scope, master);
                }
            }
            case "frame" -> frame(e, scope);
            case "custom-shape", "rect", "ellipse", "circle", "polygon", "polyline", "path", "regular-polygon",
                    "caption" -> geometric(e, scope);
            case "line", "connector" -> line(e, scope);
            default -> {
            }
        }
    }

    private Props graphic(Element e, Styles.Scope scope) {
        String pr = Dom.attr(e, Ns.PRESENTATION, "style-name");
        Props p = new Props(w.styles.props("graphic", null, scope, "graphic-properties", true));
        if (pr != null) {
            p.merge(w.styles.props("presentation", pr, scope, "graphic-properties", false));
        } else {
            p.merge(w.styles.props("graphic", Dom.attr(e, Ns.DRAW, "style-name"), scope, "graphic-properties", false));
        }
        return p;
    }

    private DmlText.Levels levels(Element e, Styles.Scope scope) {
        String pr = Dom.attr(e, Ns.PRESENTATION, "style-name");
        String gs = Dom.attr(e, Ns.DRAW, "style-name");
        String textStyle = Dom.attr(e, Ns.DRAW, "text-style-name");
        String cls = Dom.attr(e, Ns.PRESENTATION, "class", "");
        String master = masterOf(pr, scope);
        Props listHolder = graphic(e, scope);
        return new DmlText.Levels() {
            @Override
            public Props paragraph(int level) {
                return props(level, "paragraph-properties");
            }

            @Override
            public Props text(int level) {
                return props(level, "text-properties");
            }

            @Override
            public Element listStyle() {
                return listHolder.kid("list-style");
            }

            private Props props(int level, String kind) {
                Props p = new Props(w.styles.props("graphic", null, scope, kind, true));
                if (pr != null) {
                    String outline = master + "-outline" + (level + 1);
                    if (level > 0 && cls.equals("outline") && w.styles.common("presentation", outline) != null) {
                        p.merge(w.styles.props("presentation", outline, scope, kind, false));
                        p.merge(own(pr, scope, kind));
                    } else {
                        p.merge(w.styles.props("presentation", pr, scope, kind, false));
                    }
                } else {
                    p.merge(w.styles.props("graphic", gs, scope, kind, false));
                }
                if (textStyle != null && level == 0) {
                    p.merge(w.styles.props("paragraph", textStyle, scope, kind, false));
                }
                return p;
            }
        };
    }

    private Props own(String style, Styles.Scope scope, String kind) {
        Props p = new Props();
        Element s = w.styles.style("presentation", style, scope);
        if (s != null && !w.styles.isCommon("presentation", style)) {
            p.merge(Dom.kid(s, Ns.STYLE, kind));
        }
        return p;
    }

    private String masterOf(String pr, Styles.Scope scope) {
        for (Element s : w.styles.chain("presentation", pr, scope)) {
            String n = Dom.attr(s, Ns.STYLE, "name", "");
            int dash = n.lastIndexOf('-');
            if (dash > 0 && w.styles.isCommon("presentation", n)) {
                return n.substring(0, dash);
            }
        }
        return masterName == null ? "Default" : masterName;
    }

    private DmlText.Fields fields() {
        return f -> switch (Dom.local(f)) {
            case "page-number" -> String.valueOf(number);
            case "footer" -> w.footers.getOrDefault(Dom.attr(page, Ns.PRESENTATION, "use-footer-name", ""), "");
            case "header" -> w.headers.getOrDefault(Dom.attr(page, Ns.PRESENTATION, "use-header-name", ""), "");
            case "date-time" -> w.dates.getOrDefault(Dom.attr(page, Ns.PRESENTATION, "use-date-time-name", ""), "");
            default -> null;
        };
    }

    private String body(Element container, Element shape, Styles.Scope scope, Props g, Box box) {
        DmlText t = new DmlText(w.styles, scope, levels(shape, scope), fields());
        String paragraphs = t.paragraphs(container);
        int[] fit = null;
        if (shrinks(g)) {
            double width = box.w() - g.pt("fo:padding-left", 7.2) - g.pt("fo:padding-right", 7.2);
            double height = box.h() - g.pt("fo:padding-top", 3.6) - g.pt("fo:padding-bottom", 3.6);
            fit = Autofit.fit(t.layout, width, height, w.fonts);
        }
        return "<p:txBody>" + bodyPr(g, fit) + "<a:lstStyle/>" + paragraphs + "</p:txBody>";
    }

    private static boolean shrinks(Props g) {
        String fit = g.get("draw:fit-to-size", "false");
        return "true".equals(g.get("style:shrink-to-fit")) || fit.equals("shrink-to-fit") || fit.equals("true");
    }

    private static String bodyPr(Props g, int[] fit) {
        double l = g.pt("fo:padding-left", 7.2);
        double r = g.pt("fo:padding-right", 7.2);
        double t = g.pt("fo:padding-top", 3.6);
        double b = g.pt("fo:padding-bottom", 3.6);
        String anchor = switch (g.get("draw:textarea-vertical-align", "top")) {
            case "middle" -> "ctr";
            case "bottom" -> "b";
            default -> "t";
        };
        String mode = g.get("loext:writing-mode", g.get("style:writing-mode", "lr-tb"));
        String vert = switch (mode) {
            case "tb-rl", "tb" -> "vert";
            case "bt-lr" -> "vert270";
            default -> "horz";
        };
        StringBuilder x = new StringBuilder("<a:bodyPr rot=\"0\" vert=\"").append(vert).append("\" wrap=\"")
                .append("no-wrap".equals(g.get("fo:wrap-option")) ? "none" : "square").append("\" lIns=\"")
                .append(Length.emu(l)).append("\" tIns=\"").append(Length.emu(t)).append("\" rIns=\"")
                .append(Length.emu(r)).append("\" bIns=\"").append(Length.emu(b)).append("\" anchor=\"").append(anchor)
                .append('"');
        if ("center".equals(g.get("draw:textarea-horizontal-align"))) {
            x.append(" anchorCtr=\"1\"");
        }
        x.append('>');
        if (shrinks(g)) {
            x.append("<a:normAutofit");
            if (fit != null) {
                x.append(" fontScale=\"").append(fit[0]).append('"');
                if (fit[1] > 0) {
                    x.append(" lnSpcReduction=\"").append(fit[1]).append('"');
                }
            }
            x.append("/>");
        } else if ("true".equals(g.get("draw:auto-grow-height"))) {
            x.append("<a:spAutoFit/>");
        } else {
            x.append("<a:noAutofit/>");
        }
        return x.append("</a:bodyPr>").toString();
    }

    private static String xfrm(Box b, boolean flipH, boolean flipV) {
        StringBuilder x = new StringBuilder("<a:xfrm");
        if (Math.abs(b.rot()) > 0.01 && Math.abs(b.rot() - 360) > 0.01) {
            x.append(" rot=\"").append(Math.round(b.rot() * 60000)).append('"');
        }
        if (flipH) {
            x.append(" flipH=\"1\"");
        }
        if (flipV) {
            x.append(" flipV=\"1\"");
        }
        return x.append("><a:off x=\"").append(Length.emu(b.x())).append("\" y=\"").append(Length.emu(b.y()))
                .append("\"/><a:ext cx=\"").append(Length.emu(Math.max(0, b.w()))).append("\" cy=\"")
                .append(Length.emu(Math.max(0, b.h()))).append("\"/></a:xfrm>").toString();
    }

    private String nv(String tag, String extra) {
        int i = id++;
        return "<p:nv" + tag + "Pr><p:cNvPr id=\"" + i + "\" name=\"Shape " + i + "\"/><p:cNv" + tag + "Pr" + extra
                + "/><p:nvPr/></p:nv" + tag + "Pr>";
    }

    private void frame(Element f, Styles.Scope scope) throws IOException {
        Box box = Box.of(f);
        Props g = graphic(f, scope);
        Element textBox = Dom.kid(f, Ns.DRAW, "text-box");
        if (textBox != null) {
            String fill = Dml.fill(g, w.styles, this::blip);
            String ln = Dml.line(g);
            shapes.append("<p:sp>").append(nv("Sp", " txBox=\"1\"")).append("<p:spPr>").append(xfrm(box, false, false))
                    .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>")
                    .append(fill == null ? "<a:noFill/>" : fill).append(ln == null ? "<a:ln><a:noFill/></a:ln>" : ln)
                    .append("</p:spPr>").append(body(textBox, f, scope, g, box)).append("</p:sp>");
            return;
        }
        Element table = Dom.kid(f, Ns.TABLE, "table");
        if (table != null) {
            shapes.append(new SlideTable(w.styles, scope, fields()).xml(table, box, id++));
            return;
        }
        Element object = Dom.kid(f, Ns.DRAW, "object");
        String chart = object == null ? null : w.doc.chart(object);
        if (chart != null) {
            String rid = part.chart(w.out, "ppt/charts/", chart);
            int i = id++;
            shapes.append("<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"").append(i).append("\" name=\"Chart ")
                    .append(i).append("\"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x=\"")
                    .append(Length.emu(box.x())).append("\" y=\"").append(Length.emu(box.y())).append("\"/><a:ext cx=\"")
                    .append(Length.emu(box.w())).append("\" cy=\"").append(Length.emu(box.h()))
                    .append("\"/></p:xfrm><a:graphic><a:graphicData uri=\"").append(WordDrawings.CHART_URI)
                    .append("\"><c:chart xmlns:c=\"").append(WordDrawings.CHART_URI).append("\" r:id=\"").append(rid)
                    .append("\"/></a:graphicData></a:graphic></p:graphicFrame>");
            return;
        }
        byte[] data = image(f);
        if (data == null) {
            return;
        }
        String rid = part.picture(w.out, "ppt/media/", data);
        if (rid == null) {
            return;
        }
        String mirror = g.get("style:mirror", "none");
        shapes.append("<p:pic>").append(nv("Pic", "")).append("<p:blipFill><a:blip r:embed=\"").append(rid)
                .append("\"/>").append(crop(g, data)).append("<a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr>")
                .append(xfrm(box, mirror.contains("horizontal"), mirror.contains("vertical")))
                .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>");
        String ln = Dml.line(g);
        if (ln != null) {
            shapes.append(ln);
        }
        shapes.append("</p:spPr></p:pic>");
    }

    private static String crop(Props g, byte[] data) {
        String clip = g.get("fo:clip");
        if (clip == null || !clip.startsWith("rect(")) {
            return "";
        }
        int close = clip.indexOf(')');
        String[] parts = clip.substring(5, close < 0 ? clip.length() : close).split("[,\\s]+");
        if (parts.length != 4) {
            return "";
        }
        double[] v = new double[4];
        for (int i = 0; i < 4; i++) {
            v[i] = Length.pt(parts[i], 0);
        }
        if (v[0] == 0 && v[1] == 0 && v[2] == 0 && v[3] == 0) {
            return "";
        }
        try {
            java.awt.Dimension px = PictureDecoder.pixelSize(data);
            double iw = px.width * 0.75;
            double ih = px.height * 0.75;
            if (iw <= 0 || ih <= 0) {
                return "";
            }
            return "<a:srcRect l=\"" + Math.round(v[3] / iw * 100_000) + "\" t=\"" + Math.round(v[0] / ih * 100_000)
                    + "\" r=\"" + Math.round(v[1] / iw * 100_000) + "\" b=\"" + Math.round(v[2] / ih * 100_000) + "\"/>";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private byte[] image(Element frame) {
        byte[] fallback = null;
        List<Element> images = new ArrayList<>(Dom.kids(frame, Ns.DRAW, "image"));
        for (Element img : images) {
            byte[] data = OdfDocument.binaryData(img);
            if (data == null) {
                String href = Dom.attr(img, Ns.XLINK, "href");
                if (OdfDocument.external(href)) {
                    w.externalSkipped = true;
                    continue;
                }
                data = w.doc.picture(href);
            }
            if (data == null) {
                continue;
            }
            PictureDecoder.Kind kind = PictureDecoder.sniff(data);
            if (kind == PictureDecoder.Kind.UNKNOWN) {
                continue;
            }
            if (kind == PictureDecoder.Kind.SVG) {
                fallback = fallback == null ? data : fallback;
                continue;
            }
            return data;
        }
        return fallback;
    }

    private void geometric(Element s, Styles.Scope scope) {
        Box box = Box.of(s);
        Props g = graphic(s, scope);
        Shapes.Geometry geom = Shapes.geometry(s, box.w(), box.h());
        if (geom.flipV() && box.rot() != 0) {
            box = new Box(box.x(), box.y(), box.w(), box.h(), (360 - box.rot()) % 360);
        }
        String fill = Dom.local(s).equals("polyline") ? "<a:noFill/>" : Dml.fill(g, w.styles, this::blip);
        String ln = Dml.line(g);
        shapes.append("<p:sp>").append(nv("Sp", "")).append("<p:spPr>").append(xfrm(box, geom.flipH(), geom.flipV()))
                .append(geom.xml()).append(fill == null ? "<a:solidFill><a:srgbClr val=\"729FCF\"/></a:solidFill>" : fill)
                .append(ln == null ? "<a:ln w=\"0\"><a:solidFill><a:srgbClr val=\"3465A4\"/></a:solidFill></a:ln>" : ln)
                .append("</p:spPr>");
        if (hasText(s)) {
            shapes.append(body(s, s, scope, g, box));
        }
        shapes.append("</p:sp>");
    }

    private static boolean hasText(Element s) {
        for (Element k : Dom.kids(s)) {
            if ((Dom.is(k, Ns.TEXT, "p") || Dom.is(k, Ns.TEXT, "list") || Dom.is(k, Ns.TEXT, "h"))
                    && !k.getTextContent().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private void line(Element s, Styles.Scope scope) {
        double x1 = Length.pt(Dom.attr(s, Ns.SVG, "x1"), 0);
        double y1 = Length.pt(Dom.attr(s, Ns.SVG, "y1"), 0);
        double x2 = Length.pt(Dom.attr(s, Ns.SVG, "x2"), 0);
        double y2 = Length.pt(Dom.attr(s, Ns.SVG, "y2"), 0);
        Box box = new Box(Math.min(x1, x2), Math.min(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1), 0);
        Props g = graphic(s, scope);
        String ln = Dml.line(g);
        shapes.append("<p:cxnSp><p:nvCxnSpPr><p:cNvPr id=\"").append(id).append("\" name=\"Line ").append(id++)
                .append("\"/><p:cNvCxnSpPr/><p:nvPr/></p:nvCxnSpPr><p:spPr>").append(xfrm(box, x2 < x1, y2 < y1))
                .append("<a:prstGeom prst=\"line\"><a:avLst/></a:prstGeom><a:noFill/>")
                .append(ln == null ? "<a:ln><a:solidFill><a:srgbClr val=\"3465A4\"/></a:solidFill></a:ln>" : ln)
                .append("</p:spPr></p:cxnSp>");
    }
}
