package stirling.software.officeconvert.topdf.rtf;

final class TableFloat {

    String horzAnchor = "column";
    String vertAnchor = "margin";
    Integer x;
    Integer y;
    String xSpec;
    String ySpec;
    int left;
    int right;
    int top;
    int bottom;
    boolean noOverlap;

    boolean apply(String w, int v) {
        switch (w) {
            case "tposx", "tposnegx" -> x = v;
            case "tposy", "tposnegy" -> y = v;
            case "tposxc" -> xSpec = "center";
            case "tposxl" -> xSpec = "left";
            case "tposxr" -> xSpec = "right";
            case "tposxi" -> xSpec = "inside";
            case "tposxo" -> xSpec = "outside";
            case "tposyt" -> ySpec = "top";
            case "tposyc" -> ySpec = "center";
            case "tposyb" -> ySpec = "bottom";
            case "tposyin" -> ySpec = "inside";
            case "tposyout" -> ySpec = "outside";
            case "tposyil" -> ySpec = "inline";
            case "tphmrg" -> horzAnchor = "margin";
            case "tphpg" -> horzAnchor = "page";
            case "tphcol" -> horzAnchor = "text";
            case "tpvmrg" -> vertAnchor = "margin";
            case "tpvpg" -> vertAnchor = "page";
            case "tpvpara" -> vertAnchor = "text";
            case "tdfrmtxtLeft" -> left = Math.max(0, v);
            case "tdfrmtxtRight" -> right = Math.max(0, v);
            case "tdfrmtxtTop" -> top = Math.max(0, v);
            case "tdfrmtxtBottom" -> bottom = Math.max(0, v);
            case "tabsnoovrlp" -> noOverlap = v != 0;
            default -> {
                return false;
            }
        }
        return true;
    }

    String xml() {
        StringBuilder b = new StringBuilder("<w:tblpPr w:leftFromText=\"").append(left).append("\" w:rightFromText=\"")
                .append(right).append("\" w:topFromText=\"").append(top).append("\" w:bottomFromText=\"")
                .append(bottom).append("\" w:vertAnchor=\"").append(vertAnchor).append("\" w:horzAnchor=\"")
                .append(horzAnchor).append('"');
        if (xSpec != null) {
            b.append(" w:tblpXSpec=\"").append(xSpec).append('"');
        } else if (x != null) {
            b.append(" w:tblpX=\"").append(x).append('"');
        }
        if (ySpec != null) {
            b.append(" w:tblpYSpec=\"").append(ySpec).append('"');
        } else if (y != null) {
            b.append(" w:tblpY=\"").append(y).append('"');
        }
        b.append("/>");
        if (noOverlap) {
            b.append("<w:tblOverlap w:val=\"never\"/>");
        }
        return b.toString();
    }
}
