package stirling.software.officeconvert.topdf.doc;

import java.util.List;

import org.apache.poi.hwpf.usermodel.DropCapSpecifier;
import org.apache.poi.hwpf.usermodel.ParagraphProperties;

final class FrameXml {

    private static final int[] OPS = {0x261B, 0x8418, 0x8419, 0x841A, 0x442B};

    private FrameXml() {}

    static boolean framed(List<Sprm> sprms) {
        for (Sprm s : sprms) {
            for (int op : OPS) {
                if (s.opcode() == op) {
                    return true;
                }
            }
        }
        return false;
    }

    static void write(StringBuilder b, ParagraphProperties p, List<Sprm> sprms) {
        DropCapSpecifier dcs = p.getDcs();
        boolean drop = dcs != null && !dcs.isEmpty() && dcs.getDropCapType() != 0;
        if (!drop && !framed(sprms)) {
            return;
        }
        b.append("<w:framePr");
        if (drop) {
            b.append(" w:dropCap=\"").append(dcs.getDropCapType() == 2 ? "margin" : "drop").append("\" w:lines=\"")
                    .append(Math.max(1, dcs.getCountOfLinesToDrop())).append('"');
        }
        if (p.getDxaWidth() > 0) {
            b.append(" w:w=\"").append(p.getDxaWidth()).append('"');
        }
        int h = p.getDyaHeight() & 0x7FFF;
        if (h > 0) {
            b.append(" w:h=\"").append(h).append("\" w:hRule=\"").append(p.getFMinHeight() ? "atLeast" : "exact")
                    .append('"');
        }
        if (p.getDxaFromText() > 0) {
            b.append(" w:hSpace=\"").append(p.getDxaFromText()).append('"');
        }
        if (p.getDyaFromText() > 0) {
            b.append(" w:vSpace=\"").append(p.getDyaFromText()).append('"');
        }
        String wrap = switch (p.getWr()) {
            case 1 -> "notBeside";
            case 2 -> "around";
            case 3 -> "none";
            case 4 -> "tight";
            case 5 -> "through";
            default -> null;
        };
        if (wrap != null) {
            b.append(" w:wrap=\"").append(wrap).append('"');
        }
        b.append(" w:hAnchor=\"").append(switch (p.getPcHorz()) {
            case 1 -> "margin";
            case 2 -> "page";
            default -> "text";
        }).append("\" w:vAnchor=\"").append(switch (p.getPcVert()) {
            case 0 -> "margin";
            case 1 -> "page";
            default -> "text";
        }).append('"');
        int x = p.getDxaAbs();
        String xAlign = switch (x) {
            case -4 -> "center";
            case -8 -> "right";
            case -12 -> "inside";
            case -16 -> "outside";
            default -> null;
        };
        if (xAlign != null) {
            b.append(" w:xAlign=\"").append(xAlign).append('"');
        } else if (!drop) {
            b.append(" w:x=\"").append(x).append('"');
        }
        int y = p.getDyaAbs();
        String yAlign = switch (y) {
            case -4 -> "top";
            case -8 -> "center";
            case -12 -> "bottom";
            case -16 -> "inside";
            case -20 -> "outside";
            default -> null;
        };
        if (yAlign != null) {
            b.append(" w:yAlign=\"").append(yAlign).append('"');
        } else if (!drop) {
            b.append(" w:y=\"").append(y).append('"');
        }
        b.append("/>");
    }
}
