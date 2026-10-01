package stirling.software.officeconvert.topdf.odf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.io.PictureDecoder;

/** ODF frames (pictures, text boxes, object previews) and shapes written as Word drawings, inline or anchored. */
final class WordDrawings {

    static final int MAX_DRAWINGS = 50_000;

    private final OdtWriter w;

    private int count;

    WordDrawings(OdtWriter w) {
        this.w = w;
    }

    /** A drawing met inside a paragraph. */
    String inline(Element k, TextBody body) throws IOException {
        return drawing(k, body, null);
    }

    /** A drawing met between paragraphs (anchored to the page): it goes into the next paragraph. */
    String anchored(Element k, TextBody body) throws IOException {
        return drawing(k, body, null);
    }

    private String drawing(Element k, TextBody body, Element group) throws IOException {
        if (count >= MAX_DRAWINGS || body.tooDeep()) {
            return null;
        }
        String local = Dom.local(k);
        switch (local) {
            case "a" -> {
                StringBuilder b = new StringBuilder();
                for (Element c : Dom.kids(k)) {
                    if (Ns.DRAW.equals(c.getNamespaceURI())) {
                        String d = drawing(c, body, group);
                        if (d != null) {
                            b.append(d);
                        }
                    }
                }
                return b.isEmpty() ? null : b.toString();
            }
            case "g" -> {
                StringBuilder b = new StringBuilder();
                for (Element c : Dom.kids(k)) {
                    if (Ns.DRAW.equals(c.getNamespaceURI())) {
                        String d = drawing(c, body, group == null ? k : group);
                        if (d != null) {
                            b.append(d);
                        }
                    }
                }
                return b.isEmpty() ? null : b.toString();
            }
            case "frame" -> {
                return frame(k, body, group);
            }
            case "rect", "ellipse", "circle", "line", "custom-shape", "polygon", "polyline", "path", "connector",
                    "caption", "measure", "regular-polygon" -> {
                return shape(k, body, group);
            }
            default -> {
                return null;
            }
        }
    }

    private String frame(Element f, TextBody body, Element group) throws IOException {
        Element box = Dom.kid(f, Ns.DRAW, "text-box");
        if (box != null) {
            return textBox(f, box, body, group);
        }
        String chart = chart(f);
        if (chart != null) {
            Props g = graphic(f, body, group);
            Box b = Box.of(f);
            int id = ++count;
            String rid = body.part.chart(w.out, "word/charts/", chart);
            String graphic = "<a:graphic xmlns:a=\"" + Xml.A + "\"><a:graphicData uri=\"" + CHART_URI + "\"><c:chart"
                    + " xmlns:c=\"" + CHART_URI + "\" r:id=\"" + rid + "\"/></a:graphicData></a:graphic>";
            return wrap(f, group, g, b, graphic, id);
        }
        byte[] data = image(f);
        if (data == null) {
            return null;
        }
        String rid = body.part.picture(w.out, "word/media/", data);
        if (rid == null) {
            return null;
        }
        Props g = graphic(f, body, group);
        Box b = Box.of(f);
        double width = b.w();
        double height = b.h();
        if (width <= 0 || height <= 0) {
            try {
                java.awt.Dimension px = PictureDecoder.pixelSize(data);
                width = width > 0 ? width : px.width * 0.75;
                height = height > 0 ? height : px.height * 0.75;
            } catch (IOException | RuntimeException e) {
                width = width > 0 ? width : 72;
                height = height > 0 ? height : 72;
            }
        }
        String mirror = g.get("style:mirror", "none");
        boolean flipH = mirror.contains("horizontal");
        boolean flipV = mirror.contains("vertical");
        int id = ++count;
        StringBuilder pic = new StringBuilder("<a:graphic xmlns:a=\"").append(Xml.A)
                .append("\"><a:graphicData uri=\"").append(Xml.PIC).append("\"><pic:pic xmlns:pic=\"")
                .append(Xml.PIC).append("\"><pic:nvPicPr><pic:cNvPr id=\"").append(id).append("\" name=\"Picture ")
                .append(id).append("\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"").append(rid)
                .append("\"/>").append(crop(g, data)).append("<a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
                .append("<pic:spPr><a:xfrm").append(rot(b.rot())).append(flipH ? " flipH=\"1\"" : "")
                .append(flipV ? " flipV=\"1\"" : "").append("><a:off x=\"0\" y=\"0\"/><a:ext cx=\"")
                .append(Length.emu(width)).append("\" cy=\"").append(Length.emu(height)).append("\"/></a:xfrm>")
                .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>");
        String border = borderLine(g);
        if (border != null) {
            pic.append(border);
        }
        pic.append("</pic:spPr></pic:pic></a:graphicData></a:graphic>");
        return wrap(f, group, g, new Box(b.x(), b.y(), width, height, b.rot()), pic.toString(), id);
    }

    private String crop(Props g, byte[] data) {
        String clip = g.get("fo:clip");
        if (clip == null || !clip.startsWith("rect(")) {
            return "";
        }
        String[] parts = clip.substring(5, clip.indexOf(')') < 0 ? clip.length() : clip.indexOf(')')).split("[,\\s]+");
        if (parts.length != 4) {
            return "";
        }
        double top = Length.pt(parts[0], 0);
        double right = Length.pt(parts[1], 0);
        double bottom = Length.pt(parts[2], 0);
        double left = Length.pt(parts[3], 0);
        if (top == 0 && right == 0 && bottom == 0 && left == 0) {
            return "";
        }
        double iw;
        double ih;
        try {
            java.awt.Dimension px = PictureDecoder.pixelSize(data);
            iw = px.width * 0.75;
            ih = px.height * 0.75;
        } catch (IOException | RuntimeException e) {
            return "";
        }
        if (iw <= 0 || ih <= 0) {
            return "";
        }
        return "<a:srcRect l=\"" + Math.round(left / iw * 100_000) + "\" t=\"" + Math.round(top / ih * 100_000)
                + "\" r=\"" + Math.round(right / iw * 100_000) + "\" b=\"" + Math.round(bottom / ih * 100_000) + "\"/>";
    }

    static final String CHART_URI = "http://schemas.openxmlformats.org/drawingml/2006/chart";

    private String chart(Element frame) {
        Element object = Dom.kid(frame, Ns.DRAW, "object");
        return object == null ? null : w.doc.chart(object);
    }

    private byte[] image(Element frame) {
        byte[] fallback = null;
        for (Element img : Dom.kids(frame, Ns.DRAW, "image")) {
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
                if (fallback == null) {
                    fallback = data;
                }
                continue;
            }
            return data;
        }
        return fallback;
    }

    private Props graphic(Element e, TextBody body, Element group) {
        Props g = new Props(w.styles.props("graphic", Dom.attr(e, Ns.DRAW, "style-name"), body.scope,
                "graphic-properties", true));
        if (group != null) {
            Props gg = w.styles.props("graphic", Dom.attr(group, Ns.DRAW, "style-name"), body.scope,
                    "graphic-properties", false);
            for (String k : new String[] {"style:wrap", "style:horizontal-pos", "style:horizontal-rel",
                "style:vertical-pos", "style:vertical-rel", "style:run-through"}) {
                if (gg.has(k)) {
                    g.put(k, gg.get(k));
                }
            }
        }
        return g;
    }

    private String borderLine(Props g) {
        Border border = null;
        for (String side : new String[] {"top", "left", "bottom", "right"}) {
            border = Border.parse(g.get("fo:border-" + side), g.get("style:border-line-width-" + side));
            if (border != null) {
                break;
            }
        }
        if (border == null) {
            return null;
        }
        return "<a:ln w=\"" + Length.emu(border.width()) + "\"><a:solidFill><a:srgbClr val=\"" + border.color()
                + "\"/></a:solidFill></a:ln>";
    }

    private String textBox(Element f, Element box, TextBody body, Element group) throws IOException {
        Props g = graphic(f, body, group);
        Box b = Box.of(f);
        double minH = Length.pt(Dom.attr(box, Ns.FO, "min-height"), Double.NaN);
        boolean grow = !Double.isNaN(minH) || Dom.attr(f, Ns.SVG, "height") == null;
        double height = b.h() > 0 ? b.h() : Double.isNaN(minH) ? 14 : minH;
        int id = ++count;
        TextBody inner = body.nested(body.part, body.scope);
        inner.blocks(box, null);
        String fill = Dml.fill(g, w.styles, null);
        StringBuilder sp = new StringBuilder("<wps:spPr><a:xfrm").append(rot(b.rot())).append("><a:off x=\"0\" y=\"0\"/>")
                .append("<a:ext cx=\"").append(Length.emu(b.w())).append("\" cy=\"").append(Length.emu(height))
                .append("\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>")
                .append(fill == null ? "<a:noFill/>" : fill);
        String border = borderLine(g);
        sp.append(border == null ? "<a:ln><a:noFill/></a:ln>" : border).append("</wps:spPr>");
        String xml = shapeGraphic(id, true, sp.toString(), inner.cellXml(), bodyPr(g, true, grow));
        return wrap(f, group, g, new Box(b.x(), b.y(), b.w(), height, b.rot()), xml, id);
    }

    static String bodyPr(Props g, boolean frame, boolean grow) {
        double l = g.pt("fo:padding-left", g.pt("fo:padding", frame ? 0 : 7.2));
        double r = g.pt("fo:padding-right", g.pt("fo:padding", frame ? 0 : 7.2));
        double t = g.pt("fo:padding-top", g.pt("fo:padding", frame ? 0 : 3.6));
        double bt = g.pt("fo:padding-bottom", g.pt("fo:padding", frame ? 0 : 3.6));
        String v = g.get("draw:textarea-vertical-align", "top");
        String anchor = switch (v) {
            case "middle" -> "ctr";
            case "bottom" -> "b";
            default -> "t";
        };
        if (frame) {
            String va = g.get("style:vertical-align");
            anchor = "middle".equals(va) ? "ctr" : "bottom".equals(va) ? "b" : anchor;
        }
        String mode = g.get("loext:writing-mode", g.get("style:writing-mode", "lr-tb"));
        String vert = switch (mode) {
            case "tb-rl", "tb" -> "vert";
            case "bt-lr" -> "vert270";
            default -> "horz";
        };
        return "<wps:bodyPr rot=\"0\" vert=\"" + vert + "\" wrap=\"square\" lIns=\"" + Length.emu(l) + "\" tIns=\""
                + Length.emu(t) + "\" rIns=\"" + Length.emu(r) + "\" bIns=\"" + Length.emu(bt) + "\" anchor=\"" + anchor
                + "\" anchorCtr=\"0\">" + (grow ? "<a:spAutoFit/>" : "<a:noAutofit/>") + "</wps:bodyPr>";
    }

    private static String shapeGraphic(int id, boolean textBox, String spPr, String text, String bodyPr) {
        StringBuilder b = new StringBuilder("<a:graphic xmlns:a=\"").append(Xml.A).append("\"><a:graphicData uri=\"")
                .append(Xml.WPS).append("\"><wps:wsp>");
        if (textBox) {
            b.append("<wps:cNvSpPr txBox=\"1\"/>");
        } else {
            b.append("<wps:cNvSpPr/>");
        }
        b.append(spPr);
        if (text != null) {
            b.append("<wps:txbx><w:txbxContent>").append(text).append("</w:txbxContent></wps:txbx>");
        }
        b.append(bodyPr).append("</wps:wsp></a:graphicData></a:graphic>");
        return b.toString();
    }

    private String shape(Element s, TextBody body, Element group) throws IOException {
        Props g = graphic(s, body, group);
        String local = Dom.local(s);
        Box b;
        boolean flipH = false;
        boolean flipV = false;
        String geom;
        if (local.equals("line") || local.equals("connector")) {
            double x1 = Length.pt(Dom.attr(s, Ns.SVG, "x1"), 0);
            double y1 = Length.pt(Dom.attr(s, Ns.SVG, "y1"), 0);
            double x2 = Length.pt(Dom.attr(s, Ns.SVG, "x2"), 0);
            double y2 = Length.pt(Dom.attr(s, Ns.SVG, "y2"), 0);
            flipH = x2 < x1;
            flipV = y2 < y1;
            b = new Box(Math.min(x1, x2), Math.min(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1), 0);
            geom = "<a:prstGeom prst=\"line\"><a:avLst/></a:prstGeom>";
        } else {
            b = Box.of(s);
            Shapes.Geometry sg = Shapes.geometry(s, b.w(), b.h());
            geom = sg.xml();
            flipH = sg.flipH();
            flipV = sg.flipV();
            if (flipV && b.rot() != 0) {
                b = new Box(b.x(), b.y(), b.w(), b.h(), (360 - b.rot()) % 360);
            }
        }
        if (b.w() <= 0 && b.h() <= 0) {
            return null;
        }
        int id = ++count;
        String fill = local.equals("line") || local.equals("connector") || local.equals("polyline") ? "<a:noFill/>"
                : Dml.fill(g, w.styles, null);
        if (fill == null) {
            fill = "<a:solidFill><a:srgbClr val=\"729FCF\"/></a:solidFill>";
        }
        String ln = Dml.line(g);
        if (ln == null) {
            ln = "<a:ln w=\"0\"><a:solidFill><a:srgbClr val=\"3465A4\"/></a:solidFill></a:ln>";
        }
        StringBuilder sp = new StringBuilder("<wps:spPr><a:xfrm").append(rot(b.rot()))
                .append(flipH ? " flipH=\"1\"" : "").append(flipV ? " flipV=\"1\"" : "")
                .append("><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(Length.emu(Math.max(b.w(), 0)))
                .append("\" cy=\"").append(Length.emu(Math.max(b.h(), 0))).append("\"/></a:xfrm>").append(geom)
                .append(fill).append(ln).append("</wps:spPr>");
        String text = null;
        if (!Dom.kids(s, Ns.TEXT, "p").isEmpty() || !Dom.kids(s, Ns.TEXT, "list").isEmpty()) {
            TextBody inner = body.nested(body.part, body.scope);
            String gs = Dom.attr(s, Ns.DRAW, "style-name");
            inner.shapeText(w.styles.props("graphic", gs, body.scope, "paragraph-properties", true),
                    w.styles.props("graphic", gs, body.scope, "text-properties", true));
            inner.blocks(s, null);
            text = inner.cellXml();
        }
        boolean grow = "true".equals(g.get("draw:auto-grow-height"));
        String xml = shapeGraphic(id, false, sp.toString(), text, bodyPr(g, false, grow && text != null));
        return wrap(s, group, g, b, xml, id);
    }

    private static String rot(double deg) {
        if (Math.abs(deg) < 0.01 || Math.abs(deg - 360) < 0.01) {
            return "";
        }
        return " rot=\"" + Math.round(deg * 60000) + "\"";
    }

    private String wrap(Element e, Element group, Props g, Box b, String graphic, int id) {
        String anchorType = Dom.attr(group != null ? group : e, Ns.TEXT, "anchor-type", "paragraph");
        String distT = String.valueOf(Length.emu(g.pt("fo:margin-top", 0)));
        String distB = String.valueOf(Length.emu(g.pt("fo:margin-bottom", 0)));
        String distL = String.valueOf(Length.emu(g.pt("fo:margin-left", 0)));
        String distR = String.valueOf(Length.emu(g.pt("fo:margin-right", 0)));
        String docPr = "<wp:docPr id=\"" + id + "\" name=\"Shape " + id + "\"/>";
        String extent = "<wp:extent cx=\"" + Length.emu(Math.max(b.w(), 0)) + "\" cy=\"" + Length.emu(Math.max(b.h(), 0))
                + "\"/>";
        if (anchorType.equals("as-char") && group == null) {
            return "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" + extent
                    + "<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>" + docPr + "<wp:cNvGraphicFramePr/>" + graphic
                    + "</wp:inline></w:drawing></w:r>";
        }
        String wrapMode = g.get("style:wrap", "none");
        boolean behind = false;
        String wrapXml;
        switch (wrapMode) {
            case "run-through" -> {
                behind = "background".equals(g.get("style:run-through"));
                wrapXml = "<wp:wrapNone/>";
            }
            case "none" -> wrapXml = "<wp:wrapTopAndBottom/>";
            case "left" -> wrapXml = "<wp:wrapSquare wrapText=\"left\"/>";
            case "right" -> wrapXml = "<wp:wrapSquare wrapText=\"right\"/>";
            case "biggest", "dynamic" -> wrapXml = "<wp:wrapSquare wrapText=\"largest\"/>";
            default -> wrapXml = "<wp:wrapSquare wrapText=\"bothSides\"/>";
        }
        if (anchorType.equals("as-char")) {
            wrapXml = "<wp:wrapNone/>";
        }
        int z = Dom.integer(e, Ns.DRAW, "z-index", 0);
        StringBuilder a = new StringBuilder("<w:r><w:drawing><wp:anchor distT=\"").append(distT).append("\" distB=\"")
                .append(distB).append("\" distL=\"").append(distL).append("\" distR=\"").append(distR)
                .append("\" simplePos=\"0\" relativeHeight=\"").append(251_659_264L + Math.max(0, Math.min(1_000_000, z)) * 1024L)
                .append("\" behindDoc=\"").append(behind ? 1 : 0).append("\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">")
                .append("<wp:simplePos x=\"0\" y=\"0\"/>");
        a.append(horizontal(g, b, anchorType)).append(vertical(g, b, anchorType));
        a.append(extent).append("<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>").append(wrapXml).append(docPr)
                .append("<wp:cNvGraphicFramePr/>").append(graphic).append("</wp:anchor></w:drawing></w:r>");
        return a.toString();
    }

    private static String horizontal(Props g, Box b, String anchorType) {
        String rel = g.get("style:horizontal-rel", anchorType.equals("page") ? "page" : "paragraph");
        String from = switch (rel) {
            case "page" -> "page";
            case "page-content", "page-start-margin" -> rel.equals("page-content") ? "margin" : "leftMargin";
            case "page-end-margin" -> "rightMargin";
            case "paragraph-start-margin" -> "leftMargin";
            case "paragraph-end-margin" -> "rightMargin";
            case "char" -> "character";
            default -> "column";
        };
        String pos = g.get("style:horizontal-pos", "from-left");
        String align = switch (pos) {
            case "left" -> "left";
            case "center" -> "center";
            case "right" -> "right";
            case "inside" -> "inside";
            case "outside" -> "outside";
            default -> null;
        };
        String inner = align != null ? "<wp:align>" + align + "</wp:align>"
                : "<wp:posOffset>" + Length.emu(b.x()) + "</wp:posOffset>";
        return "<wp:positionH relativeFrom=\"" + from + "\">" + inner + "</wp:positionH>";
    }

    private static String vertical(Props g, Box b, String anchorType) {
        String rel = g.get("style:vertical-rel", anchorType.equals("page") ? "page" : "paragraph");
        String from = switch (rel) {
            case "page" -> "page";
            case "page-content" -> "margin";
            case "char", "line", "baseline", "text" -> "line";
            case "page-content-top" -> "topMargin";
            case "page-content-bottom" -> "bottomMargin";
            default -> "paragraph";
        };
        String pos = g.get("style:vertical-pos", "from-top");
        String align = switch (pos) {
            case "top" -> "top";
            case "middle" -> "center";
            case "bottom" -> "bottom";
            default -> null;
        };
        if (from.equals("paragraph") && align != null) {
            align = null;
        }
        String inner = align != null ? "<wp:align>" + align + "</wp:align>"
                : "<wp:posOffset>" + Length.emu(b.y()) + "</wp:posOffset>";
        return "<wp:positionV relativeFrom=\"" + from + "\">" + inner + "</wp:positionV>";
    }

    static List<Element> drawingKids(Element e) {
        List<Element> out = new ArrayList<>();
        for (Element k : Dom.kids(e)) {
            if (Ns.DRAW.equals(k.getNamespaceURI())) {
                out.add(k);
            }
        }
        return out;
    }
}
