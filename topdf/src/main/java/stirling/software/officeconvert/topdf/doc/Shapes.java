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
        if (sp == null) {
            return null;
        }
        EscherSpRecord rec = sp.getChildById(EscherSpRecord.RECORD_ID);
        if (rec == null || (rec.getFlags() & 0x01) != 0) {
            return null;
        }
        int type = rec.getShapeType();
        String geom = switch (type) {
            case 1, 202, 75 -> "rect";
            case 2 -> "roundRect";
            case 3 -> "ellipse";
            case 4 -> "diamond";
            case 5 -> "triangle";
            case 20, 32 -> "line";
            default -> "rect";
        };
        long cx = Math.max(0, (long) d.getRectangleRight() - d.getRectangleLeft()) * Drawings.EMU_PER_TWIP;
        long cy = Math.max(0, (long) d.getRectangleBottom() - d.getRectangleTop()) * Drawings.EMU_PER_TWIP;
        boolean line = geom.equals("line");
        int[] text = line ? null : c.textboxes().text(d.getShapeId(), story.kind == Story.Kind.HEADER);
        Boolean fill = bit(sp, 0x01BF, 4);
        boolean filled = fill == null || fill;
        Boolean stroke = bit(sp, 0x01FF, 3);
        boolean stroked = stroke == null || stroke;
        boolean wraps = fspa != null && fspa.getWr() != 3;
        if (text == null && !stroked && (!filled || line) && !wraps) {
            return null;
        }
        int id = c.nextId();
        StringBuilder b = new StringBuilder("<w:drawing>").append(open(c, d, fspa, id, Math.max(1, cx), Math.max(1, cy)));
        b.append("<a:graphic><a:graphicData uri=\"").append(Xml.WPS).append("\"><wps:wsp><wps:cNvSpPr")
                .append(text != null ? " txBox=\"1\"" : "").append("/><wps:spPr><a:xfrm");
        if ((rec.getFlags() & 0x40) != 0) {
            b.append(" flipH=\"1\"");
        }
        if ((rec.getFlags() & 0x80) != 0) {
            b.append(" flipV=\"1\"");
        }
        b.append("><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(cx).append("\" cy=\"").append(cy)
                .append("\"/></a:xfrm><a:prstGeom prst=\"").append(geom).append("\"><a:avLst/></a:prstGeom>");
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
                    .append(color(prop(sp, 0x01C0, 0), 0)).append("\"/></a:solidFill></a:ln>");
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
                }).append("\"/></wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing>");
        return b.toString();
    }

    private static String color(long v, int fallback) {
        int rgb = (v & 0xFF000000L) != 0 ? fallback
                : (int) ((v & 0xFF) << 16 | (v & 0xFF00) | (v >> 16) & 0xFF);
        return Xml.hex(rgb);
    }

    static String open(Conv c, OfficeDrawing d, FSPA fspa, int id, long cx, long cy) {
        EscherContainerRecord sp = container(d);
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
            b.append("<wp:posOffset>").append((long) d.getRectangleLeft() * Drawings.EMU_PER_TWIP)
                    .append("</wp:posOffset>");
        }
        b.append("</wp:positionH><wp:positionV relativeFrom=\"").append(verticalFrom(sp, fspa)).append("\">");
        String v = verticalAlign(sp);
        if (v != null) {
            b.append("<wp:align>").append(v).append("</wp:align>");
        } else {
            b.append("<wp:posOffset>").append((long) d.getRectangleTop() * Drawings.EMU_PER_TWIP)
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
