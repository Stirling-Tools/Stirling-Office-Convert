package stirling.software.officeconvert.topdf.rtf;

final class Frame implements Cloneable {

    int width;
    int height;
    boolean exactHeight;
    Integer x;
    Integer y;
    String xAlign;
    String yAlign;
    String hAnchor = "margin";
    String vAnchor = "margin";
    int hSpace;
    int vSpace;
    String wrap;

    Frame copy() {
        try {
            return (Frame) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    boolean apply(String word, int param) {
        switch (word) {
            case "absw" -> width = param;
            case "absh" -> {
                height = Math.abs(param);
                exactHeight = param < 0;
            }
            case "posx" -> x = param;
            case "posy" -> y = param;
            case "posnegx" -> x = param;
            case "posnegy" -> y = param;
            case "posxc" -> xAlign = "center";
            case "posxl" -> xAlign = "left";
            case "posxr" -> xAlign = "right";
            case "posxi" -> xAlign = "inside";
            case "posxo" -> xAlign = "outside";
            case "posyt" -> yAlign = "top";
            case "posyc" -> yAlign = "center";
            case "posyb" -> yAlign = "bottom";
            case "posyin" -> yAlign = "inside";
            case "posyout" -> yAlign = "outside";
            case "posyil" -> yAlign = "inline";
            case "phmrg" -> hAnchor = "margin";
            case "phpg" -> hAnchor = "page";
            case "phcol" -> hAnchor = "text";
            case "pvmrg" -> vAnchor = "margin";
            case "pvpg" -> vAnchor = "page";
            case "pvpara" -> vAnchor = "text";
            case "dxfrtext" -> {
                hSpace = param;
                vSpace = param;
            }
            case "dfrmtxtx" -> hSpace = param;
            case "dfrmtxty" -> vSpace = param;
            case "nowrap" -> wrap = "notBeside";
            case "overlay" -> wrap = "none";
            case "wraparound", "wraptight" -> wrap = "around";
            case "wrapdefault" -> wrap = null;
            default -> {
                return false;
            }
        }
        return true;
    }

    String xml() {
        StringBuilder b = new StringBuilder("<w:framePr");
        if (width > 0) {
            b.append(" w:w=\"").append(width).append('"');
        }
        if (height > 0) {
            b.append(" w:h=\"").append(height).append("\" w:hRule=\"").append(exactHeight ? "exact" : "atLeast")
                    .append('"');
        }
        if (wrap != null) {
            b.append(" w:wrap=\"").append(wrap).append('"');
        }
        if (hSpace > 0) {
            b.append(" w:hSpace=\"").append(hSpace).append('"');
        }
        if (vSpace > 0) {
            b.append(" w:vSpace=\"").append(vSpace).append('"');
        }
        b.append(" w:hAnchor=\"").append(hAnchor).append("\" w:vAnchor=\"").append(vAnchor).append('"');
        if (xAlign != null) {
            b.append(" w:xAlign=\"").append(xAlign).append('"');
        } else if (x != null) {
            b.append(" w:x=\"").append(x).append('"');
        }
        if (yAlign != null) {
            b.append(" w:yAlign=\"").append(yAlign).append('"');
        } else if (y != null) {
            b.append(" w:y=\"").append(y).append('"');
        }
        return b.append("/>").toString();
    }
}
