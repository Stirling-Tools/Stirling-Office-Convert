package stirling.software.officeconvert.docx;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.Stacking;

final class DrawingXml {

    private final PartContext ctx;
    private final BodyXml body;

    DrawingXml(PartContext ctx, BodyXml body) {
        this.ctx = ctx;
        this.body = body;
    }

    void drawing(StringBuilder sb, Picture pic) {
        String rel = ctx.imageRel(pic.media);
        int id = ctx.docPr();
        long cx = Xml.emu(pic.width);
        long cy = Xml.emu(pic.height);
        boolean floating = pic.wrap != Picture.Wrap.INLINE;
        int shift = Math.round(pic.baselineShift * 2);
        sb.append(!floating && shift != 0 ? "<w:r><w:rPr><w:position w:val=\"" + shift + "\"/></w:rPr><w:drawing>" : "<w:r><w:drawing>");
        if (floating) {
            boolean quarter = Math.floorMod(pic.rotation, 180) == 90;
            float x = quarter ? pic.x + (pic.height - pic.width) / 2f : pic.x;
            float y = quarter ? pic.y + (pic.width - pic.height) / 2f : pic.y;
            long dist = Xml.emu(pic.wrapGap);
            sb.append("<wp:anchor distT=\"0\" distB=\"0\" distL=\"").append(dist).append("\" distR=\"").append(dist)
                    .append("\" simplePos=\"0\" relativeHeight=\"")
                    .append(Stacking.picture(pic, id)).append("\" behindDoc=\"").append(pic.behind ? 1 : 0)
                    .append("\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">")
                    .append("<wp:simplePos x=\"0\" y=\"0\"/>")
                    .append("<wp:positionH relativeFrom=\"page\"><wp:posOffset>").append(Xml.emuOffset(x))
                    .append("</wp:posOffset></wp:positionH>")
                    .append("<wp:positionV relativeFrom=\"").append(pic.fromParagraph ? "paragraph" : "page")
                    .append("\"><wp:posOffset>").append(Xml.emuOffsetDown(y))
                    .append("</wp:posOffset></wp:positionV>");
        } else {
            sb.append("<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">");
        }
        sb.append("<wp:extent cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/>")
                .append("<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>");
        if (floating) {
            switch (pic.wrap) {
                case SQUARE -> sb.append("<wp:wrapSquare wrapText=\"bothSides\"/>");
                case TOP_BOTTOM -> sb.append("<wp:wrapTopAndBottom/>");
                default -> sb.append("<wp:wrapNone/>");
            }
        }
        sb.append("<wp:docPr id=\"").append(id).append("\" name=\"Picture ").append(id).append("\" descr=\"")
                .append(String.join("&#10;", pic.description.lines().map(Xml::esc).toList())).append("\"/>")
                .append("<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>")
                .append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
                .append("<pic:pic><pic:nvPicPr><pic:cNvPr id=\"").append(id).append("\" name=\"")
                .append(pic.media.name()).append("\"/><pic:cNvPicPr/></pic:nvPicPr>")
                .append("<pic:blipFill><a:blip r:embed=\"").append(rel).append("\"/>");
        if (pic.cropLeft > 0 || pic.cropTop > 0 || pic.cropRight > 0 || pic.cropBottom > 0) {
            sb.append("<a:srcRect l=\"").append(Math.round(pic.cropLeft * 100000)).append("\" t=\"")
                    .append(Math.round(pic.cropTop * 100000)).append("\" r=\"")
                    .append(Math.round(pic.cropRight * 100000)).append("\" b=\"")
                    .append(Math.round(pic.cropBottom * 100000)).append("\"/>");
        }
        sb.append("<a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
                .append("<pic:spPr><a:xfrm");
        if (pic.rotation != 0) {
            sb.append(" rot=\"").append(pic.rotation * 60000L).append('"');
        }
        if (pic.flipH) {
            sb.append(" flipH=\"1\"");
        }
        sb.append("><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(cx).append("\" cy=\"").append(cy)
                .append("\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>")
                .append("</a:graphicData></a:graphic>")
                .append(floating ? "</wp:anchor>" : "</wp:inline>")
                .append("</w:drawing></w:r>");
    }

    void textBox(StringBuilder sb, Inline.TextBox t) {
        int id = ctx.docPr();
        long cx = Xml.emu(t.width());
        long cy = Xml.emu(t.height());
        long gap = Xml.emu(t.wrapGap());
        sb.append("<w:r><mc:AlternateContent><mc:Choice Requires=\"wps\"><w:drawing>")
                .append("<wp:anchor distT=\"0\" distB=\"0\" distL=\"").append(gap).append("\" distR=\"").append(gap)
                .append("\" simplePos=\"0\" relativeHeight=\"").append(Stacking.box(id))
                .append("\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">")
                .append("<wp:simplePos x=\"0\" y=\"0\"/>")
                .append("<wp:positionH relativeFrom=\"page\"><wp:posOffset>").append(Xml.emuOffset(t.x())).append("</wp:posOffset></wp:positionH>")
                .append("<wp:positionV relativeFrom=\"page\"><wp:posOffset>").append(Xml.emuOffsetDown(t.y())).append("</wp:posOffset></wp:positionV>")
                .append("<wp:extent cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/>")
                .append("<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>")
                .append(t.overlay() ? "<wp:wrapNone/>" : "<wp:wrapSquare wrapText=\"bothSides\"/>")
                .append("<wp:docPr id=\"").append(id).append("\" name=\"Text Box ").append(id).append("\"/><wp:cNvGraphicFramePr/>")
                .append("<a:graphic><a:graphicData uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\">")
                .append("<wps:wsp><wps:cNvSpPr txBox=\"1\"/><wps:spPr><a:xfrm").append(t.direction() == 180 ? " rot=\"10800000\"" : "")
                .append("><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(cx)
                .append("\" cy=\"").append(cy).append("\"/></a:xfrm><a:prstGeom prst=\"").append(t.rounded() ? "roundRect" : "rect")
                .append("\">").append(adjust(t.rounded(), t.width(), t.height())).append("</a:prstGeom>");
        if (t.fillRgb() >= 0) {
            sb.append("<a:solidFill><a:srgbClr val=\"").append(Xml.hex(t.fillRgb())).append("\"/></a:solidFill>");
        } else if (t.groundRgb() >= 0) {
            sb.append("<a:solidFill><a:srgbClr val=\"").append(Xml.hex(t.groundRgb()))
                    .append("\"><a:alpha val=\"0\"/></a:srgbClr></a:solidFill>");
        } else {
            sb.append("<a:noFill/>");
        }
        outline(sb, t.lineRgb(), t.lineWidth());
        sb.append("</wps:spPr><wps:txbx><w:txbxContent>");
        if (t.paragraphs().isEmpty()) {
            sb.append("<w:p/>");
        }
        for (Paragraph p : t.paragraphs()) {
            body.paragraph(sb, p, false);
        }
        sb.append("</w:txbxContent></wps:txbx><wps:bodyPr rot=\"0\" vert=\"").append(t.upright() ? "eaVert" : vert(t.direction()))
                .append("\" wrap=\"square\" lIns=\"")
                .append(Xml.emu(t.insetLeft())).append("\" tIns=\"0\" rIns=\"").append(Xml.emu(t.insetRight()))
                .append("\" bIns=\"0\" anchor=\"t\" anchorCtr=\"0\"><a:noAutofit/></wps:bodyPr></wps:wsp>")
                .append("</a:graphicData></a:graphic></wp:anchor></w:drawing></mc:Choice></mc:AlternateContent></w:r>");
    }

    private static String vert(int direction) {
        return switch (direction) {
            case 90 -> "vert270";
            case 270 -> "vert";
            default -> "horz";
        };
    }

    private static void outline(StringBuilder sb, int rgb, float width) {
        if (rgb < 0 || width <= 0) {
            sb.append("<a:ln><a:noFill/></a:ln>");
            return;
        }
        sb.append("<a:ln w=\"").append(Xml.emu(width)).append("\"><a:solidFill><a:srgbClr val=\"").append(Xml.hex(rgb))
                .append("\"/></a:solidFill></a:ln>");
    }

    private static String adjust(boolean rounded, float width, float height) {
        if (!rounded) {
            return "<a:avLst/>";
        }
        long adj = Math.min(50000, Math.round(Stacking.CORNER / Math.max(1f, Math.min(width, height)) * 100000));
        return "<a:avLst><a:gd name=\"adj\" fmla=\"val " + adj + "\"/></a:avLst>";
    }

    private static String geometry(Inline.Shape s) {
        if (s.ellipse()) {
            return "ellipse";
        }
        return s.rounded() ? "roundRect" : "rect";
    }

    void shape(StringBuilder sb, Inline.Shape s) {
        int id = ctx.docPr();
        long cx = Xml.emu(s.width());
        long cy = Xml.emu(s.height());
        sb.append("<w:r><mc:AlternateContent><mc:Choice Requires=\"wps\"><w:drawing>")
                .append("<wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\" relativeHeight=\"")
                .append(Stacking.shape(s, id)).append("\" behindDoc=\"1\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">")
                .append("<wp:simplePos x=\"0\" y=\"0\"/>")
                .append("<wp:positionH relativeFrom=\"page\"><wp:posOffset>").append(Xml.emuOffset(s.x())).append("</wp:posOffset></wp:positionH>")
                .append("<wp:positionV relativeFrom=\"").append(s.fromParagraph() ? "paragraph" : "page").append("\"><wp:posOffset>")
                .append(s.fromParagraph() ? Xml.emuOffset(s.y()) : Xml.emuOffsetDown(s.y())).append("</wp:posOffset></wp:positionV>")
                .append("<wp:extent cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/>")
                .append("<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:wrapNone/>")
                .append("<wp:docPr id=\"").append(id).append("\" name=\"Shape ").append(id).append("\"/><wp:cNvGraphicFramePr/>")
                .append("<a:graphic><a:graphicData uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\">")
                .append("<wps:wsp><wps:cNvSpPr/><wps:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(cx)
                .append("\" cy=\"").append(cy).append("\"/></a:xfrm><a:prstGeom prst=\"").append(geometry(s))
                .append("\">").append(adjust(s.rounded() && !s.ellipse(), s.width(), s.height())).append("</a:prstGeom>");
        if (s.rgb() >= 0) {
            sb.append("<a:solidFill><a:srgbClr val=\"").append(Xml.hex(s.rgb())).append("\"/></a:solidFill>");
        } else {
            sb.append("<a:noFill/>");
        }
        outline(sb, s.lineRgb(), s.lineWidth());
        sb.append("</wps:spPr>")
                .append("<wps:bodyPr/></wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing></mc:Choice></mc:AlternateContent></w:r>");
    }
}
