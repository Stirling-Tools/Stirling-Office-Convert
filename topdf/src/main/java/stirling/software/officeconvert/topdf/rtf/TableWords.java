package stirling.software.officeconvert.topdf.rtf;

final class TableWords {

    static final Border HANDLED = new Border();

    private TableWords() {}

    static Border apply(RowProps r, String w, int v) {
        RowProps.CellDef c = r.pending;
        switch (w) {
            case "trleft" -> r.left = v;
            case "trgaph" -> r.gap = Math.max(0, v);
            case "trrh" -> r.height = v;
            case "trhdr" -> r.header = true;
            case "trkeep" -> r.keep = true;
            case "trql" -> r.align = null;
            case "trqc" -> r.align = "center";
            case "trqr" -> r.align = "right";
            case "trautofit" -> r.autofit = v != 0;
            case "rtlrow" -> r.rtl = true;
            case "ltrrow" -> r.rtl = false;
            case "trpaddl" -> r.padLeft = Math.max(0, v);
            case "trpaddr" -> r.padRight = Math.max(0, v);
            case "trpaddt" -> r.padTop = Math.max(0, v);
            case "trpaddb" -> r.padBottom = Math.max(0, v);
            case "trwWidth" -> r.width = v;
            case "trftsWidth" -> r.widthType = v;
            case "trcbpat" -> r.shadeColor = v;
            case "trbrdrt" -> {
                r.top = new Border();
                return r.top;
            }
            case "trbrdrl" -> {
                r.leftBorder = new Border();
                return r.leftBorder;
            }
            case "trbrdrb" -> {
                r.bottom = new Border();
                return r.bottom;
            }
            case "trbrdrr" -> {
                r.rightBorder = new Border();
                return r.rightBorder;
            }
            case "trbrdrh" -> {
                r.insideH = new Border();
                return r.insideH;
            }
            case "trbrdrv" -> {
                r.insideV = new Border();
                return r.insideV;
            }
            case "clbrdrt" -> {
                c.top = new Border();
                return c.top;
            }
            case "clbrdrl" -> {
                c.left = new Border();
                return c.left;
            }
            case "clbrdrb" -> {
                c.bottom = new Border();
                return c.bottom;
            }
            case "clbrdrr" -> {
                c.right = new Border();
                return c.right;
            }
            case "clcbpat" -> c.shade.background = v;
            case "clcfpat" -> c.shade.foreground = v;
            case "clshdng" -> c.shade.percent = v;
            case "clvertalt" -> c.valign = "top";
            case "clvertalc" -> c.valign = "center";
            case "clvertalb" -> c.valign = "bottom";
            case "clmgf" -> c.mergeFirst = true;
            case "clmrg" -> c.merged = true;
            case "clvmgf" -> c.vmergeFirst = true;
            case "clvmrg" -> c.vmerged = true;
            case "clwWidth" -> c.width = v;
            case "clftsWidth" -> c.widthType = v;
            case "clpadt" -> c.padLeft = Math.max(0, v);
            case "clpadl" -> c.padTop = Math.max(0, v);
            case "clpadr" -> c.padRight = Math.max(0, v);
            case "clpadb" -> c.padBottom = Math.max(0, v);
            case "cltxtbrl", "cltxtbrlv" -> c.direction = "tbRl";
            case "cltxbtlr" -> c.direction = "btLr";
            case "clNoWrap" -> c.noWrap = true;
            case "cellx" -> r.addCell(v);
            default -> {
                return null;
            }
        }
        return HANDLED;
    }
}
