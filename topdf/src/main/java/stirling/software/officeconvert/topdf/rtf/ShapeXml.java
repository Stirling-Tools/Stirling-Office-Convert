package stirling.software.officeconvert.topdf.rtf;

import java.util.List;

final class ShapeXml {

    private static final long EMU = PictureXml.EMU_PER_TWIP;

    private static final int MAX_TWIPS = 31680 * 4;

    static void drawings(Shape s, Rels rels, Media media, boolean libreOffice, List<String> out) {
        new ShapeXml(libreOffice).frame(s, s, s.left, s.top, s.right, s.bottom, rels, media, out, 0);
    }

    private final boolean libreOffice;

    private ShapeXml(boolean libreOffice) {
        this.libreOffice = libreOffice;
    }

    private void frame(Shape root, Shape s, long l, long t, long r, long b, Rels rels, Media media,
            List<String> out, int depth) {
        if (s.flag("fHidden", false) || depth > 16) {
            return;
        }
        if (!s.group) {
            String d = drawing(root, s, l, t, r, b, rels, media);
            if (d != null) {
                out.add(d);
            }
            return;
        }
        long gl = s.integer("groupLeft", 0);
        long gt = s.integer("groupTop", 0);
        long gr = s.integer("groupRight", 20000);
        long gb = s.integer("groupBottom", 20000);
        double sx = gr == gl ? 1 : (double) (r - l) / (gr - gl);
        double sy = gb == gt ? 1 : (double) (b - t) / (gb - gt);
        for (Shape c : s.children) {
            long cl = c.integer("relLeft", c.left);
            long ct = c.integer("relTop", c.top);
            long cr = c.integer("relRight", c.right);
            long cb = c.integer("relBottom", c.bottom);
            frame(root, c, l + Math.round((cl - gl) * sx), t + Math.round((ct - gt) * sy),
                    l + Math.round((cr - gl) * sx), t + Math.round((cb - gt) * sy), rels, media, out, depth + 1);
        }
    }

    private String drawing(Shape root, Shape s, long l, long t, long r, long b, Rels rels, Media media) {
        long left = Math.min(l, r);
        long top = Math.min(t, b);
        long w = clamp(Math.abs(r - l));
        long h = clamp(Math.abs(b - t));
        int type = s.integer("shapeType", s.picture != null ? 75 : 1);
        boolean line = type == 20 || type == 32 || type == 33 || type == 34 || type == 38;
        if (s.picture == null && s.text == null && !line && !s.flag("fFilled", true) && !s.flag("fLine", true)) {
            return null;
        }
        int id = media.nextId();
        if (root.inline && root == s) {
            return inline(s, type, line, w, h, rels, id);
        }
        boolean behind = root.behind || root.flag("fBehindDocument", false);
        int wrap = root.wrap < 0 ? 3 : root.wrap;
        StringBuilder x = new StringBuilder(1024);
        x.append("<w:drawing><wp:anchor distT=\"").append(root.integer("dyWrapDistTop", 0)).append("\" distB=\"")
                .append(root.integer("dyWrapDistBottom", 0)).append("\" distL=\"")
                .append(root.integer("dxWrapDistLeft", 114300)).append("\" distR=\"")
                .append(root.integer("dxWrapDistRight", 114300)).append("\" simplePos=\"0\" relativeHeight=\"")
                .append(251_658_240L + Math.max(0, Math.min(1_000_000, root.z))).append("\" behindDoc=\"")
                .append(behind ? 1 : 0).append("\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">")
                .append("<wp:simplePos x=\"0\" y=\"0\"/>");
        position(x, "H", horizontal(root), root.integer("posh", 0), left);
        position(x, "V", vertical(root), root.integer("posv", 0), top);
        x.append("<wp:extent cx=\"").append(w * EMU).append("\" cy=\"").append(h * EMU)
                .append("\"/><wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>");
        if (behind || wrap == 3) {
            x.append("<wp:wrapNone/>");
        } else if (wrap == 1) {
            x.append("<wp:wrapTopAndBottom/>");
        } else {
            String side = switch (root.wrapSide) {
                case 1 -> "left";
                case 2 -> "right";
                case 3 -> "largest";
                default -> "bothSides";
            };
            x.append("<wp:wrapSquare wrapText=\"").append(side).append("\"/>");
        }
        x.append("<wp:docPr id=\"").append(id).append("\" name=\"Shape ").append(id).append("\"/>");
        if (s.picture != null) {
            String rid = rels.add("image", s.picture.target(), false);
            PictureXml.Image sized = new PictureXml.Image(s.picture.target(), w * EMU, h * EMU, s.picture.crop());
            x.append(PictureXml.graphic(sized, rid, id));
        } else {
            x.append(shape(s, type, line, w, h));
        }
        return x.append("</wp:anchor></w:drawing>").toString();
    }

    private String inline(Shape s, int type, boolean line, long w, long h, Rels rels, int id) {
        StringBuilder x = new StringBuilder(1024).append("<w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\"")
                .append(" distR=\"0\"><wp:extent cx=\"").append(w * EMU).append("\" cy=\"").append(h * EMU)
                .append("\"/><wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:docPr id=\"").append(id)
                .append("\" name=\"Shape ").append(id).append("\"/>");
        if (s.picture != null) {
            String rid = rels.add("image", s.picture.target(), false);
            x.append(PictureXml.graphic(new PictureXml.Image(s.picture.target(), w * EMU, h * EMU, s.picture.crop()),
                    rid, id));
        } else {
            x.append(shape(s, type, line, w, h));
        }
        return x.append("</wp:inline></w:drawing>").toString();
    }

    private static String wordArt(Shape s, String text, long h) {
        int bgr = s.integer("fillColor", 0xC0C0C0);
        int rgb = bgr >>> 24 != 0 ? 0xC0C0C0 : (bgr & 0xFF) << 16 | (bgr >> 8 & 0xFF) << 8 | bgr >> 16 & 0xFF;
        long size = Math.max(2, Math.min(3276, h / 10 * 3 / 2));
        StringBuilder b = new StringBuilder("<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"240\""
                + " w:lineRule=\"auto\"/><w:jc w:val=\"center\"/></w:pPr><w:r><w:rPr>");
        String font = s.props.get("gtextFont");
        if (font != null && !font.isBlank()) {
            b.append("<w:rFonts w:ascii=\"").append(Xml.attr(font)).append("\" w:hAnsi=\"").append(Xml.attr(font))
                    .append("\"/>");
        }
        b.append("<w:color w:val=\"").append(Shading.hex(rgb)).append("\"/><w:sz w:val=\"").append(size)
                .append("\"/></w:rPr><w:t xml:space=\"preserve\">");
        Xml.text(text, b);
        return b.append("</w:t></w:r></w:p>").toString();
    }

    private static long clamp(long v) {
        return Math.max(0, Math.min(MAX_TWIPS, v));
    }

    private static void position(StringBuilder x, String axis, String from, int align, long offset) {
        x.append("<wp:position").append(axis).append(" relativeFrom=\"").append(from).append("\">");
        String a = null;
        if (align > 0) {
            a = switch (align) {
                case 1 -> "H".equals(axis) ? "left" : "top";
                case 2 -> "center";
                case 3 -> "H".equals(axis) ? "right" : "bottom";
                case 4 -> "inside";
                case 5 -> "outside";
                default -> null;
            };
        }
        if (a != null) {
            x.append("<wp:align>").append(a).append("</wp:align>");
        } else {
            x.append("<wp:posOffset>").append(Math.max(-MAX_TWIPS, Math.min(MAX_TWIPS, offset)) * EMU)
                    .append("</wp:posOffset>");
        }
        x.append("</wp:position").append(axis).append('>');
    }

    private String horizontal(Shape s) {
        String rel = s.props.get("posrelh");
        if (rel != null && (s.bxIgnore || s.bx == null)) {
            if (libreOffice && "3".equals(rel) && !s.props.containsKey("posrelv")) {
                return s.left < 0 || s.top < 0 ? "margin" : "page";
            }
            return switch (s.integer("posrelh", 2)) {
                case 0 -> "margin";
                case 1 -> "page";
                case 3 -> "character";
                default -> "column";
            };
        }
        if (libreOffice && "column".equals(s.bx)) {
            return "margin";
        }
        return s.bx != null ? s.bx : "page";
    }

    private String vertical(Shape s) {
        String rel = s.props.get("posrelv");
        if (rel != null && (s.byIgnore || s.by == null)) {
            return switch (s.integer("posrelv", 2)) {
                case 0 -> "margin";
                case 1 -> "page";
                case 3 -> "line";
                default -> "paragraph";
            };
        }
        if (s.by == null && libreOffice && "3".equals(s.props.get("posrelh")) && rel == null) {
            return s.left < 0 || s.top < 0 ? "margin" : "page";
        }
        return s.by != null ? s.by : "page";
    }

    private String shape(Shape s, int type, boolean line, long w, long h) {
        StringBuilder x = new StringBuilder(512);
        x.append("<a:graphic><a:graphicData uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\">")
                .append("<wps:wsp><wps:cNvSpPr");
        if (s.text != null) {
            x.append(" txBox=\"1\"");
        }
        x.append("/><wps:spPr><a:xfrm");
        int rot = s.integer("rotation", 0);
        if (rot != 0) {
            x.append(" rot=\"").append(Math.round(rot / 65536.0 * 60000)).append('"');
        }
        if (s.flag("fFlipH", false)) {
            x.append(" flipH=\"1\"");
        }
        if (s.flag("fFlipV", false)) {
            x.append(" flipV=\"1\"");
        }
        x.append("><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(w * EMU).append("\" cy=\"").append(h * EMU)
                .append("\"/></a:xfrm><a:prstGeom prst=\"").append(line ? "line" : geometry(type))
                .append("\"><a:avLst/></a:prstGeom>");
        String art = s.props.get("gtextUNICODE");
        if (art != null && !art.isBlank() && s.text == null) {
            s.text = wordArt(s, art, h);
        }
        boolean unstated = !s.props.containsKey("fillColor") && !s.props.containsKey("fillType");
        boolean filled = art == null && (libreOffice && unstated ? s.flag("fFilled", false) : s.flag("fFilled", true));
        if (!line && filled) {
            x.append("<a:solidFill>").append(color(s.integer("fillColor", 0xFFFFFF), 0xFFFFFF,
                    s.integer("fillOpacity", 65536))).append("</a:solidFill>");
        } else {
            x.append("<a:noFill/>");
        }
        if (art == null && s.flag("fLine", true)) {
            x.append("<a:ln w=\"").append(Math.max(0, s.integer("lineWidth", 9525))).append("\"><a:solidFill>")
                    .append(color(s.integer("lineColor", 0), 0, 65536)).append("</a:solidFill>");
            String dash = dash(s.integer("lineDashing", 0));
            if (dash != null) {
                x.append("<a:prstDash val=\"").append(dash).append("\"/>");
            }
            x.append("</a:ln>");
        } else {
            x.append("<a:ln><a:noFill/></a:ln>");
        }
        x.append("</wps:spPr>");
        if (s.text != null) {
            x.append("<wps:txbx><w:txbxContent>").append(s.text.isEmpty() ? "<w:p/>" : s.text)
                    .append("</w:txbxContent></wps:txbx>");
        }
        String anchor = switch (s.integer("anchorText", 0)) {
            case 1, 4, 7 -> "ctr";
            case 2, 5, 8 -> "b";
            default -> "t";
        };
        x.append("<wps:bodyPr wrap=\"").append(s.integer("WrapText", 0) == 2 ? "none" : "square")
                .append("\" lIns=\"").append(s.integer("dxTextLeft", 91440)).append("\" tIns=\"")
                .append(s.integer("dyTextTop", 45720)).append("\" rIns=\"").append(s.integer("dxTextRight", 91440))
                .append("\" bIns=\"").append(s.integer("dyTextBottom", 45720)).append("\" anchor=\"").append(anchor)
                .append("\">");
        if (s.flag("fFitShapeToText", false)) {
            x.append("<a:spAutoFit/>");
        }
        x.append("</wps:bodyPr></wps:wsp></a:graphicData></a:graphic>");
        return x.toString();
    }

    private static String color(int bgr, int fallback, int opacity) {
        int rgb;
        if (bgr >>> 24 != 0) {
            rgb = fallback;
        } else {
            rgb = (bgr & 0xFF) << 16 | (bgr >> 8 & 0xFF) << 8 | bgr >> 16 & 0xFF;
        }
        StringBuilder b = new StringBuilder("<a:srgbClr val=\"").append(Shading.hex(rgb)).append('"');
        if (opacity >= 0 && opacity < 65536) {
            b.append("><a:alpha val=\"").append(opacity * 100_000L / 65536).append("\"/></a:srgbClr>");
        } else {
            b.append("/>");
        }
        return b.toString();
    }

    private static String dash(int d) {
        return switch (d) {
            case 1 -> "dash";
            case 2 -> "sysDot";
            case 3 -> "dashDot";
            case 4 -> "lgDashDotDot";
            case 5 -> "sysDash";
            case 6 -> "dot";
            case 7 -> "lgDash";
            case 8 -> "lgDashDot";
            case 9 -> "lgDashDotDot";
            case 10 -> "sysDashDot";
            default -> null;
        };
    }

    private static String geometry(int type) {
        return switch (type) {
            case 2 -> "roundRect";
            case 3, 120 -> "ellipse";
            case 4, 110 -> "diamond";
            case 5 -> "triangle";
            case 6 -> "rtTriangle";
            case 7, 111 -> "parallelogram";
            case 8 -> "trapezoid";
            case 9 -> "hexagon";
            case 10 -> "octagon";
            case 11 -> "plus";
            case 12 -> "star5";
            case 13 -> "rightArrow";
            case 15 -> "homePlate";
            case 16 -> "cube";
            case 21 -> "plaque";
            case 22 -> "can";
            case 23 -> "donut";
            case 55 -> "chevron";
            case 56 -> "pentagon";
            case 61 -> "wedgeRectCallout";
            case 62 -> "wedgeRoundRectCallout";
            case 63 -> "wedgeEllipseCallout";
            case 66 -> "leftArrow";
            case 67 -> "downArrow";
            case 68 -> "upArrow";
            case 69 -> "leftRightArrow";
            case 70 -> "upDownArrow";
            case 92 -> "star24";
            case 96 -> "smileyFace";
            case 116 -> "flowChartPredefinedProcess";
            case 176 -> "flowChartAlternateProcess";
            case 183 -> "sun";
            case 184 -> "moon";
            case 187 -> "star4";
            case 188 -> "doubleWave";
            default -> "rect";
        };
    }
}
