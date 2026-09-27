package stirling.software.officeconvert.pptx;

import java.util.Map;

import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.slides.Frame;
import stirling.software.officeconvert.slides.LineShape;
import stirling.software.officeconvert.slides.PictureShape;
import stirling.software.officeconvert.slides.RectShape;
import stirling.software.officeconvert.slides.Slide;
import stirling.software.officeconvert.slides.SlideShape;
import stirling.software.officeconvert.slides.TableShape;
import stirling.software.officeconvert.slides.TextPara;
import stirling.software.officeconvert.slides.TextShape;

final class SlideXml {

    static final String TITLE_LAYOUT = "slideLayout1.xml";
    static final String BLANK_LAYOUT = "slideLayout2.xml";

    private final SlideRels rels;
    private final TextXml text;
    private final TableXml tables;
    private int nextId = 2;

    SlideXml(Slide slide, TextXml.Links links, Map<String, Integer> fonts) {
        boolean titled = slide.shapes().stream().anyMatch(s -> s instanceof TextShape t && t.title());
        this.rels = new SlideRels(titled ? TITLE_LAYOUT : BLANK_LAYOUT);
        this.text = new TextXml(rels, links, fonts);
        this.tables = new TableXml(text);
    }

    SlideRels rels() {
        return rels;
    }

    String xml(Slide slide) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append(Ooxml.HEADER).append("<p:sld").append(Ooxml.NAMESPACES).append("><p:cSld>");
        if (slide.background() >= 0) {
            sb.append("<p:bg><p:bgPr>");
            Ooxml.solidFill(sb, slide.background());
            sb.append("<a:effectLst/></p:bgPr></p:bg>");
        }
        sb.append("<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>")
                .append("<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/>")
                .append("<a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>");
        for (SlideShape s : slide.shapes()) {
            switch (s) {
                case TextShape t -> text(sb, t);
                case PictureShape p -> picture(sb, p);
                case TableShape t -> tables.table(sb, t, nextId++);
                case RectShape r -> rect(sb, r);
                case LineShape l -> line(sb, l);
            }
        }
        sb.append("</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>");
        return sb.toString();
    }

    private static void xfrm(StringBuilder sb, Frame f, boolean flipH) {
        sb.append("<a:xfrm");
        if (f.rotation() != 0) {
            sb.append(" rot=\"").append(Ooxml.angle(f.rotation())).append('"');
        }
        if (flipH) {
            sb.append(" flipH=\"1\"");
        }
        sb.append("><a:off x=\"").append(Ooxml.offset(f.x())).append("\" y=\"").append(Ooxml.offset(f.y()))
                .append("\"/><a:ext cx=\"").append(Ooxml.emu(f.width())).append("\" cy=\"").append(Ooxml.emu(f.height()))
                .append("\"/></a:xfrm>");
    }

    private void text(StringBuilder sb, TextShape t) {
        int id = nextId++;
        sb.append("<p:sp><p:nvSpPr><p:cNvPr id=\"").append(id).append("\" name=\"")
                .append(t.title() ? "Title " : "TextBox ").append(id - 1).append("\"/>")
                .append(t.title() ? "<p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"title\"/></p:nvPr>"
                        : "<p:cNvSpPr txBox=\"1\"/><p:nvPr/>")
                .append("</p:nvSpPr><p:spPr>");
        xfrm(sb, t.frame(), false);
        geometry(sb, t.frame(), t.radius());
        if (t.fillRgb() >= 0) {
            Ooxml.solidFill(sb, t.fillRgb());
        } else {
            sb.append("<a:noFill/>");
        }
        Ooxml.line(sb, t.lineRgb(), t.lineWidth());
        sb.append("</p:spPr><p:txBody><a:bodyPr vert=\"horz\" wrap=\"").append(t.wrap() ? "square" : "none")
                .append("\" lIns=\"").append(Ooxml.emu(t.insetLeft()))
                .append("\" tIns=\"").append(Ooxml.emu(t.insetTop())).append("\" rIns=\"").append(Ooxml.emu(t.insetRight()))
                .append("\" bIns=\"0\" rtlCol=\"0\" anchor=\"t\" anchorCtr=\"0\">")
                .append(t.title() ? "<a:noAutofit/>" : "<a:spAutoFit/>").append("</a:bodyPr><a:lstStyle/>");
        for (TextPara p : t.paras()) {
            text.paragraph(sb, p);
        }
        sb.append("</p:txBody></p:sp>");
    }

    private void picture(StringBuilder sb, PictureShape p) {
        Picture pic = p.picture();
        int id = nextId++;
        sb.append("<p:pic><p:nvPicPr><p:cNvPr id=\"").append(id).append("\" name=\"Picture ").append(id - 1)
                .append("\" descr=\"").append(String.join("&#10;", pic.description.lines().map(Ooxml::esc).toList())).append("\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/>")
                .append("</p:cNvPicPr><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed=\"")
                .append(rels.image(pic.media.name())).append("\"/>");
        if (pic.cropLeft > 0 || pic.cropTop > 0 || pic.cropRight > 0 || pic.cropBottom > 0) {
            sb.append("<a:srcRect l=\"").append(Math.round(pic.cropLeft * 100000)).append("\" t=\"")
                    .append(Math.round(pic.cropTop * 100000)).append("\" r=\"").append(Math.round(pic.cropRight * 100000))
                    .append("\" b=\"").append(Math.round(pic.cropBottom * 100000)).append("\"/>");
        }
        sb.append("<a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr>");
        xfrm(sb, p.frame(), pic.flipH);
        sb.append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>");
    }

    private static void geometry(StringBuilder sb, Frame f, float radius) {
        if (radius <= 0.1f) {
            sb.append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>");
            return;
        }
        float side = Math.max(0.1f, Math.min(f.width(), f.height()));
        long adj = Math.clamp(Math.round(radius / side * 100000), 0, 50000);
        sb.append("<a:prstGeom prst=\"roundRect\"><a:avLst><a:gd name=\"adj\" fmla=\"val ").append(adj)
                .append("\"/></a:avLst></a:prstGeom>");
    }

    private void rect(StringBuilder sb, RectShape r) {
        int id = nextId++;
        sb.append("<p:sp><p:nvSpPr><p:cNvPr id=\"").append(id).append("\" name=\"Rectangle ").append(id - 1)
                .append("\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>");
        xfrm(sb, r.frame(), false);
        geometry(sb, r.frame(), r.radius());
        if (r.fillRgb() >= 0) {
            Ooxml.solidFill(sb, r.fillRgb(), r.alpha());
        } else {
            sb.append("<a:noFill/>");
        }
        Ooxml.line(sb, r.lineRgb(), r.lineWidth());
        sb.append("</p:spPr></p:sp>");
    }

    private void line(StringBuilder sb, LineShape l) {
        int id = nextId++;
        float x = Math.min(l.x1(), l.x2());
        float y = Math.min(l.y1(), l.y2());
        boolean flipH = l.x2() < l.x1();
        boolean flipV = l.y2() < l.y1();
        sb.append("<p:cxnSp><p:nvCxnSpPr><p:cNvPr id=\"").append(id).append("\" name=\"Straight Connector ").append(id - 1)
                .append("\"/><p:cNvCxnSpPr/><p:nvPr/></p:nvCxnSpPr><p:spPr><a:xfrm")
                .append(flipH ? " flipH=\"1\"" : "").append(flipV ? " flipV=\"1\"" : "")
                .append("><a:off x=\"").append(Ooxml.offset(x)).append("\" y=\"").append(Ooxml.offset(y))
                .append("\"/><a:ext cx=\"").append(Ooxml.emu(Math.abs(l.x2() - l.x1()))).append("\" cy=\"")
                .append(Ooxml.emu(Math.abs(l.y2() - l.y1()))).append("\"/></a:xfrm><a:prstGeom prst=\"line\"><a:avLst/></a:prstGeom>")
                .append("<a:ln w=\"").append(Ooxml.emu(l.width())).append("\">");
        Ooxml.solidFill(sb, Math.max(0, l.rgb()));
        sb.append("</a:ln></p:spPr></p:cxnSp>");
    }
}
