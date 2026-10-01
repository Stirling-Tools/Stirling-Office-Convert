package stirling.software.officeconvert.topdf.doc;

import java.io.IOException;

import org.apache.poi.ddf.EscherComplexProperty;
import org.apache.poi.ddf.EscherContainerRecord;
import org.apache.poi.ddf.EscherProperty;
import org.apache.poi.ddf.EscherRecord;
import org.apache.poi.ddf.EscherSimpleProperty;
import org.apache.poi.ddf.EscherSpRecord;
import org.apache.poi.ddf.AbstractEscherOptRecord;
import org.apache.poi.hwpf.model.FSPA;
import org.apache.poi.hwpf.usermodel.OfficeDrawing;

final class Shapes {

    private static final int WRAP_LEFT = 0x0384;

    private static final int WRAP_TOP = 0x0385;

    private static final int WRAP_RIGHT = 0x0386;

    private static final int WRAP_BOTTOM = 0x0387;

    private Shapes() {}

    static String anchor(Conv c, Story story, OfficeDrawing d, FSPA fspa) throws IOException {
        EscherContainerRecord sp = container(d);
        EscherSpRecord rec = sp == null ? null : sp.getChildById(EscherSpRecord.RECORD_ID);
        if (rec == null) {
            sp = Groups.head(c, d.getShapeId());
            rec = sp == null ? null : sp.getChildById(EscherSpRecord.RECORD_ID);
        }
        if (rec == null) {
            return null;
        }
        long cx = Math.max(1, (long) d.getRectangleRight() - d.getRectangleLeft()) * Drawings.EMU_PER_TWIP;
        long cy = Math.max(1, (long) d.getRectangleBottom() - d.getRectangleTop()) * Drawings.EMU_PER_TWIP;
        boolean wraps = fspa != null && fspa.getWr() != 3;
        boolean group = (rec.getFlags() & 0x01) != 0;
        long[] box = group ? new long[] {0, 0, cx, cy} : unrotated(sp, 0, 0, cx, cy);
        String inner = group ? Groups.group(c, story, d.getShapeId(), cx, cy)
                : shape(c, story, sp, 0, 0, box[2], box[3], wraps);
        if (inner == null) {
            return null;
        }
        int id = c.nextId();
        return "<w:drawing>" + open(d, sp, fspa, id, box[2], box[3], box[0], box[1]) + "<a:graphic><a:graphicData uri=\""
                + (group ? Groups.WPG : Xml.WPS) + "\">" + inner + "</a:graphicData></a:graphic></wp:anchor></w:drawing>";
    }

    static String shape(Conv c, Story story, EscherContainerRecord sp, long x, long y, long cx, long cy,
            boolean keepInvisible) throws IOException {
        EscherSpRecord rec = sp.getChildById(EscherSpRecord.RECORD_ID);
        if (rec == null) {
            return null;
        }
        String geom = switch (rec.getShapeType()) {
            case 1, 202, 75 -> "rect";
            case 2 -> "roundRect";
            case 3 -> "ellipse";
            case 4 -> "diamond";
            case 5 -> "triangle";
            case 20, 32 -> "line";
            default -> "rect";
        };
        String art = rec.getShapeType() >= 136 && rec.getShapeType() <= 175 ? string(sp, 0x00C0) : null;
        if (art != null && !art.isBlank()) {
            return wordArt(sp, rec, art, x, y, cx, cy);
        }
        boolean line = geom.equals("line");
        int[] text = line ? null : c.textboxes().text(rec.getShapeId(), story.kind == Story.Kind.HEADER);
        Boolean fill = bit(sp, 0x01BF, 4);
        boolean filled = fill == null || fill;
        Boolean stroke = bit(sp, 0x01FF, 3);
        boolean stroked = stroke == null || stroke;
        if (text == null && !stroked && (!filled || line) && !keepInvisible) {
            return null;
        }
        StringBuilder b = new StringBuilder("<wps:wsp><wps:cNvSpPr").append(text != null ? " txBox=\"1\"" : "")
                .append("/><wps:spPr>");
        xfrm(b, rec, rotation(sp), x, y, cx, cy);
        b.append("<a:prstGeom prst=\"").append(geom).append("\"><a:avLst/></a:prstGeom>");
        long opacity = prop(sp, 0x0182, 0x10000);
        if (filled && !line && opacity > 0) {
            b.append("<a:solidFill><a:srgbClr val=\"").append(color(prop(sp, 0x0181, 0xFFFFFF), 0xFFFFFF))
                    .append(opacity < 0x10000 ? "\"><a:alpha val=\"" + opacity * 100000 / 0x10000 + "\"/></a:srgbClr>"
                            : "\"/>").append("</a:solidFill>");
        } else {
            b.append("<a:noFill/>");
        }
        if (stroked) {
            b.append("<a:ln w=\"").append(prop(sp, 0x01CB, 9525)).append("\"><a:solidFill><a:srgbClr val=\"")
                    .append(color(prop(sp, 0x01C0, 0), 0)).append("\"/></a:solidFill>");
            String dash = switch ((int) prop(sp, 0x01CE, 0)) {
                case 1, 6 -> "dash";
                case 2, 5 -> "sysDot";
                case 3, 7 -> "dashDot";
                case 4, 9 -> "sysDashDotDot";
                case 8 -> "lgDash";
                case 10 -> "lgDashDot";
                default -> null;
            };
            if (dash != null) {
                b.append("<a:prstDash val=\"").append(dash).append("\"/>");
            }
            arrow(b, "headEnd", prop(sp, 0x01D0, 0));
            arrow(b, "tailEnd", prop(sp, 0x01D1, 0));
            b.append("</a:ln>");
        } else {
            b.append("<a:ln><a:noFill/></a:ln>");
        }
        b.append("</wps:spPr>");
        if (text != null) {
            b.append("<wps:txbx><w:txbxContent>");
            int mark = b.length();
            new Story(c, story.rels, Story.Kind.TEXTBOX, 0, null).write(text[0],
                    Stories.trim(c.src.text, text[0], text[1]), b);
            if (b.length() == mark) {
                b.append("<w:p/>");
            }
            b.append("</w:txbxContent></wps:txbx>");
        }
        b.append("<wps:bodyPr wrap=\"square\" lIns=\"").append(prop(sp, 0x0081, 91440)).append("\" tIns=\"")
                .append(prop(sp, 0x0082, 45720)).append("\" rIns=\"").append(prop(sp, 0x0083, 91440))
                .append("\" bIns=\"").append(prop(sp, 0x0084, 45720)).append("\" anchor=\"")
                .append(switch ((int) prop(sp, 0x0087, 0)) {
                    case 1, 4 -> "ctr";
                    case 2, 5, 7, 9 -> "b";
                    default -> "t";
                }).append("\"/></wps:wsp>");
        return b.toString();
    }

    private static void arrow(StringBuilder b, String end, long kind) {
        String type = switch ((int) kind) {
            case 1 -> "triangle";
            case 2 -> "stealth";
            case 3 -> "diamond";
            case 4 -> "oval";
            case 5 -> "arrow";
            default -> null;
        };
        if (type != null) {
            b.append("<a:").append(end).append(" type=\"").append(type).append("\"/>");
        }
    }

    private static String wordArt(EscherContainerRecord sp, EscherSpRecord rec, String text, long x, long y, long cx,
            long cy) {
        StringBuilder b = new StringBuilder("<wps:wsp><wps:cNvSpPr/><wps:spPr>");
        xfrm(b, rec, rotation(sp), x, y, cx, cy);
        b.append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></wps:spPr>")
                .append("<wps:txbx><w:txbxContent><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:rPr>");
        String font = string(sp, 0x00C5);
        if (font != null && !font.isBlank()) {
            String f = Xml.esc(font.strip());
            b.append("<w:rFonts w:ascii=\"").append(f).append("\" w:hAnsi=\"").append(f).append("\" w:cs=\"").append(f)
                    .append("\"/>");
        }
        Boolean bold = bit(sp, 0x00FF, 5);
        Boolean italic = bit(sp, 0x00FF, 4);
        if (bold != null && bold) {
            b.append("<w:b/>");
        }
        if (italic != null && italic) {
            b.append("<w:i/>");
        }
        b.append("<w:color w:val=\"").append(color(prop(sp, 0x0181, 0), 0)).append("\"/>");
        long size = prop(sp, 0x00C3, 36 << 16) >> 15;
        b.append("<w:sz w:val=\"").append(Math.max(2, Math.min(3276, size))).append("\"/></w:rPr><w:t xml:space=\"preserve\">")
                .append(Xml.esc(text.replace('\n', ' ').replace('\r', ' '))).append("</w:t></w:r></w:p></w:txbxContent></wps:txbx>")
                .append("<wps:bodyPr wrap=\"none\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\"><a:prstTxWarp prst=\"textPlain\">")
                .append("<a:avLst/></a:prstTxWarp></wps:bodyPr></wps:wsp>");
        return b.toString();
    }

    private static String string(EscherContainerRecord sp, int number) {
        byte[] d = complex(sp, number);
        if (d == null || d.length < 2) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i + 1 < d.length && out.length() < 4096; i += 2) {
            char ch = (char) Sprm.u16(d, i);
            if (ch == 0) {
                break;
            }
            out.append(ch);
        }
        return out.toString();
    }

    static void xfrm(StringBuilder b, EscherSpRecord rec, double deg, long x, long y, long cx, long cy) {
        b.append("<a:xfrm");
        if (deg != 0) {
            b.append(" rot=\"").append(Math.round(deg * 60000)).append('"');
        }
        if ((rec.getFlags() & 0x40) != 0) {
            b.append(" flipH=\"1\"");
        }
        if ((rec.getFlags() & 0x80) != 0) {
            b.append(" flipV=\"1\"");
        }
        b.append("><a:off x=\"").append(x).append("\" y=\"").append(y).append("\"/><a:ext cx=\"").append(cx)
                .append("\" cy=\"").append(cy).append("\"/></a:xfrm>");
    }

    private static String color(long v, int fallback) {
        int rgb = (v & 0xFF000000L) != 0 ? fallback
                : (int) ((v & 0xFF) << 16 | (v & 0xFF00) | (v >> 16) & 0xFF);
        return Xml.hex(rgb);
    }

    static long[] unrotated(EscherContainerRecord sp, long x, long y, long cx, long cy) {
        double deg = rotation(sp);
        boolean sideways = deg > 45 && deg <= 135 || deg > 225 && deg <= 315;
        if (!sideways) {
            return new long[] {x, y, cx, cy};
        }
        return new long[] {x + (cx - cy) / 2, y + (cy - cx) / 2, cy, cx};
    }

    static double rotation(EscherContainerRecord sp) {
        double deg = ((int) prop(sp, 0x0004, 0)) / 65536.0 % 360;
        return deg < 0 ? deg + 360 : deg;
    }

    static String open(OfficeDrawing d, EscherContainerRecord sp, FSPA fspa, int id, long cx, long cy, long shiftX,
            long shiftY) {
        long left = prop(sp, WRAP_LEFT, 114300);
        long right = prop(sp, WRAP_RIGHT, 114300);
        long top = prop(sp, WRAP_TOP, 0);
        long bottom = prop(sp, WRAP_BOTTOM, 0);
        int wr = fspa == null ? 3 : fspa.getWr();
        Boolean flag = bit(sp, 0x03BF, 5);
        boolean behind = flag != null ? flag : fspa != null && fspa.isFBelowText();
        StringBuilder b = new StringBuilder(512);
        b.append("<wp:anchor distT=\"").append(top).append("\" distB=\"").append(bottom).append("\" distL=\"")
                .append(left).append("\" distR=\"").append(right).append("\" simplePos=\"0\" relativeHeight=\"")
                .append(id).append("\" behindDoc=\"").append(behind ? 1 : 0)
                .append("\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\"><wp:simplePos x=\"0\" y=\"0\"/>");
        b.append("<wp:positionH relativeFrom=\"").append(horizontalFrom(sp, fspa)).append("\">");
        String h = horizontalAlign(sp);
        if (h != null) {
            b.append("<wp:align>").append(h).append("</wp:align>");
        } else {
            b.append("<wp:posOffset>").append((long) d.getRectangleLeft() * Drawings.EMU_PER_TWIP + shiftX)
                    .append("</wp:posOffset>");
        }
        b.append("</wp:positionH><wp:positionV relativeFrom=\"").append(verticalFrom(sp, fspa)).append("\">");
        String v = verticalAlign(sp);
        if (v != null) {
            b.append("<wp:align>").append(v).append("</wp:align>");
        } else {
            b.append("<wp:posOffset>").append((long) d.getRectangleTop() * Drawings.EMU_PER_TWIP + shiftY)
                    .append("</wp:posOffset>");
        }
        b.append("</wp:positionV><wp:extent cx=\"").append(cx).append("\" cy=\"").append(cy)
                .append("\"/><wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>");
        String side = switch (fspa == null ? 0 : fspa.getWrk()) {
            case 1 -> "left";
            case 2 -> "right";
            case 3 -> "largest";
            default -> "bothSides";
        };
        switch (wr) {
            case 1 -> b.append("<wp:wrapTopAndBottom/>");
            case 3 -> b.append("<wp:wrapNone/>");
            case 4, 5 -> {
                String tag = wr == 4 ? "wrapTight" : "wrapThrough";
                b.append("<wp:").append(tag).append(" wrapText=\"").append(side).append("\"><wp:wrapPolygon edited=\"0\">")
                        .append(polygon(sp)).append("</wp:wrapPolygon></wp:").append(tag).append('>');
            }
            default -> b.append("<wp:wrapSquare wrapText=\"").append(side).append("\"/>");
        }
        b.append("<wp:docPr id=\"").append(id).append("\" name=\"Shape ").append(id).append("\"/>");
        return b.toString();
    }

    static final int MAX_POINTS = 4096;

    private static String polygon(EscherContainerRecord sp) {
        byte[] d = complex(sp, 0x0383);
        StringBuilder b = new StringBuilder();
        if (d != null && d.length >= 6) {
            int n = Sprm.u16(d, 0);
            int cb = Sprm.u16(d, 4);
            int size = cb == 0xFFF0 ? 4 : cb;
            for (int i = 0; i < Math.min(n, MAX_POINTS) && (size == 4 || size == 8) && 6 + (i + 1) * size <= d.length;
                    i++) {
                int at = 6 + i * size;
                int x = size == 4 ? (short) Sprm.u16(d, at) : Sprm.s32(d, at);
                int y = size == 4 ? (short) Sprm.u16(d, at + 2) : Sprm.s32(d, at + 4);
                b.append(i == 0 ? "<wp:start" : "<wp:lineTo").append(" x=\"").append(x).append("\" y=\"").append(y)
                        .append("\"/>");
            }
        }
        if (b.isEmpty()) {
            b.append("<wp:start x=\"0\" y=\"0\"/><wp:lineTo x=\"0\" y=\"21600\"/><wp:lineTo x=\"21600\" y=\"21600\"/>")
                    .append("<wp:lineTo x=\"21600\" y=\"0\"/><wp:lineTo x=\"0\" y=\"0\"/>");
        }
        return b.toString();
    }

    private static byte[] complex(EscherContainerRecord sp, int number) {
        if (sp == null) {
            return null;
        }
        for (EscherRecord r : sp.getChildRecords()) {
            if (r instanceof AbstractEscherOptRecord opt && opt.lookup(number) instanceof EscherComplexProperty c) {
                return c.getComplexData();
            }
        }
        return null;
    }

    static EscherContainerRecord container(OfficeDrawing d) {
        try {
            return d.getOfficeArtSpContainer();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static long prop(EscherContainerRecord sp, int number, long fallback) {
        if (sp == null) {
            return fallback;
        }
        for (EscherRecord r : sp.getChildRecords()) {
            if (r instanceof AbstractEscherOptRecord opt) {
                EscherProperty p = opt.lookup(number);
                if (p instanceof EscherSimpleProperty s) {
                    return s.getPropertyValue();
                }
            }
        }
        return fallback;
    }

    static Boolean bit(EscherContainerRecord sp, int number, int bit) {
        if (sp == null) {
            return null;
        }
        for (EscherRecord r : sp.getChildRecords()) {
            if (r instanceof AbstractEscherOptRecord opt && opt.lookup(number) instanceof EscherSimpleProperty s
                    && (s.getPropertyValue() & (1 << (bit + 16))) != 0) {
                return (s.getPropertyValue() & (1 << bit)) != 0;
            }
        }
        return null;
    }

    private static String horizontalFrom(EscherContainerRecord sp, FSPA fspa) {
        long v = prop(sp, 0x0390, -1);
        if (v < 0) {
            v = fspa == null ? 2 : switch (fspa.getBx()) {
                case 0 -> 0;
                case 1 -> 1;
                default -> 2;
            };
        }
        return switch ((int) v) {
            case 0 -> "margin";
            case 1 -> "page";
            case 3 -> "character";
            default -> "column";
        };
    }

    private static String verticalFrom(EscherContainerRecord sp, FSPA fspa) {
        long v = prop(sp, 0x0392, -1);
        if (v < 0) {
            v = fspa == null ? 2 : switch (fspa.getBy()) {
                case 0 -> 0;
                case 1 -> 1;
                default -> 2;
            };
        }
        return switch ((int) v) {
            case 0 -> "margin";
            case 1 -> "page";
            case 3 -> "line";
            default -> "paragraph";
        };
    }

    private static String horizontalAlign(EscherContainerRecord sp) {
        return switch ((int) prop(sp, 0x038F, 0)) {
            case 1 -> "left";
            case 2 -> "center";
            case 3 -> "right";
            case 4 -> "inside";
            case 5 -> "outside";
            default -> null;
        };
    }

    private static String verticalAlign(EscherContainerRecord sp) {
        return switch ((int) prop(sp, 0x0391, 0)) {
            case 1 -> "top";
            case 2 -> "center";
            case 3 -> "bottom";
            case 4 -> "inside";
            case 5 -> "outside";
            default -> null;
        };
    }
}
