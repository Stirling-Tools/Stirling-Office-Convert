package stirling.software.officeconvert.topdf.rtf;

final class Border {

    String style;

    int width;

    int color = -1;

    int space;

    Border copy() {
        Border b = new Border();
        b.style = style;
        b.width = width;
        b.color = color;
        b.space = space;
        return b;
    }

    boolean apply(String word, int param, boolean hasParam) {
        switch (word) {
            case "brdrw" -> width = Math.max(0, Math.min(255, param));
            case "brdrcf" -> color = param;
            case "brsp" -> space = Math.max(0, Math.min(31 * 20, param));
            default -> {
                String s = style(word);
                if (s == null) {
                    return false;
                }
                style = s;
            }
        }
        return true;
    }

    static String style(String word) {
        return switch (word) {
            case "brdrs", "brdrsh" -> "single";
            case "brdrth" -> "thick";
            case "brdrdb" -> "double";
            case "brdrdot" -> "dotted";
            case "brdrdash" -> "dashed";
            case "brdrhair" -> "hair";
            case "brdrdashsm" -> "dashSmallGap";
            case "brdrdashd" -> "dotDash";
            case "brdrdashdd" -> "dotDotDash";
            case "brdrinset" -> "inset";
            case "brdroutset" -> "outset";
            case "brdrtriple" -> "triple";
            case "brdrtnthsg" -> "thinThickSmallGap";
            case "brdrthtnsg" -> "thickThinSmallGap";
            case "brdrtnthtnsg" -> "thinThickThinSmallGap";
            case "brdrtnthmg" -> "thinThickMediumGap";
            case "brdrthtnmg" -> "thickThinMediumGap";
            case "brdrtnthtnmg" -> "thinThickThinMediumGap";
            case "brdrtnthlg" -> "thinThickLargeGap";
            case "brdrthtnlg" -> "thickThinLargeGap";
            case "brdrtnthtnlg" -> "thinThickThinLargeGap";
            case "brdrwavy" -> "wave";
            case "brdrwavydb" -> "doubleWave";
            case "brdrdashdotstr" -> "dashDotStroked";
            case "brdremboss" -> "threeDEmboss";
            case "brdrengrave" -> "threeDEngrave";
            case "brdrframe" -> "single";
            case "brdrnone", "brdrnil", "brdrtbl" -> "none";
            default -> null;
        };
    }

    String xml(String tag, ColorTable colors) {
        if (style == null || "none".equals(style)) {
            return "<w:" + tag + " w:val=\"nil\"/>";
        }
        int w = width <= 0 ? 10 : width;
        if ("thick".equals(style)) {
            w *= 2;
        }
        int sz = Math.max(2, Math.min(96, Math.round(w * 8 / 20f)));
        String c = color > 0 || color == 0 && colors.explicit(0) ? Shading.hex(colors.rgb(color, 0)) : "auto";
        return "<w:" + tag + " w:val=\"" + ("thick".equals(style) ? "single" : style) + "\" w:sz=\"" + sz
                + "\" w:space=\"" + Math.round(space / 20f) + "\" w:color=\"" + c + "\"/>";
    }
}
