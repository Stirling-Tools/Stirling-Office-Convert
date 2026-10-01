package stirling.software.officeconvert.topdf.doc;

import org.apache.poi.ddf.EscherContainerRecord;
import org.apache.poi.ddf.EscherProperty;
import org.apache.poi.ddf.EscherRecord;
import org.apache.poi.ddf.EscherSimpleProperty;
import org.apache.poi.ddf.AbstractEscherOptRecord;
import org.apache.poi.hwpf.model.FSPA;
import org.apache.poi.hwpf.usermodel.OfficeDrawing;

final class Shapes {

    private static final int WRAP_LEFT = 0x0384;

    private static final int WRAP_TOP = 0x0385;

    private static final int WRAP_RIGHT = 0x0386;

    private static final int WRAP_BOTTOM = 0x0387;

    private Shapes() {}

    static String anchor(Conv c, Story story, OfficeDrawing d, FSPA fspa) {
        return null;
    }

    static String open(Conv c, OfficeDrawing d, FSPA fspa, int id, long cx, long cy) {
        EscherContainerRecord sp = container(d);
        long left = prop(sp, WRAP_LEFT, 114300);
        long right = prop(sp, WRAP_RIGHT, 114300);
        long top = prop(sp, WRAP_TOP, 0);
        long bottom = prop(sp, WRAP_BOTTOM, 0);
        int wr = fspa == null ? 3 : fspa.getWr();
        boolean behind = fspa != null && fspa.isFBelowText();
        StringBuilder b = new StringBuilder(512);
        b.append("<wp:anchor distT=\"").append(top).append("\" distB=\"").append(bottom).append("\" distL=\"")
                .append(left).append("\" distR=\"").append(right).append("\" simplePos=\"0\" relativeHeight=\"")
                .append(id).append("\" behindDoc=\"").append(behind && wr == 3 ? 1 : 0)
                .append("\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\"><wp:simplePos x=\"0\" y=\"0\"/>");
        b.append("<wp:positionH relativeFrom=\"").append(horizontalFrom(d)).append("\">");
        String h = horizontalAlign(d);
        if (h != null) {
            b.append("<wp:align>").append(h).append("</wp:align>");
        } else {
            b.append("<wp:posOffset>").append((long) d.getRectangleLeft() * Drawings.EMU_PER_TWIP)
                    .append("</wp:posOffset>");
        }
        b.append("</wp:positionH><wp:positionV relativeFrom=\"").append(verticalFrom(d)).append("\">");
        String v = verticalAlign(d);
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
            default -> b.append("<wp:wrapSquare wrapText=\"").append(side).append("\"/>");
        }
        b.append("<wp:docPr id=\"").append(id).append("\" name=\"Shape ").append(id).append("\"/>");
        return b.toString();
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

    private static String horizontalFrom(OfficeDrawing d) {
        return switch (d.getHorizontalRelative()) {
            case PAGE -> "page";
            case TEXT -> "column";
            case CHAR -> "character";
            default -> "margin";
        };
    }

    private static String verticalFrom(OfficeDrawing d) {
        return switch (d.getVerticalRelativeElement()) {
            case PAGE -> "page";
            case TEXT -> "paragraph";
            case LINE -> "line";
            default -> "margin";
        };
    }

    private static String horizontalAlign(OfficeDrawing d) {
        return switch (d.getHorizontalPositioning()) {
            case LEFT -> "left";
            case CENTER -> "center";
            case RIGHT -> "right";
            case INSIDE -> "inside";
            case OUTSIDE -> "outside";
            default -> null;
        };
    }

    private static String verticalAlign(OfficeDrawing d) {
        return switch (d.getVerticalPositioning()) {
            case TOP -> "top";
            case CENTER -> "center";
            case BOTTOM -> "bottom";
            case INSIDE -> "inside";
            case OUTSIDE -> "outside";
            default -> null;
        };
    }
}
