package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;

record Border(String style, float width, float space, Color color) {

    static final Border NIL = new Border("nil", 0, 0, Color.BLACK);

    static Border parse(XEl e, Theme theme) {
        if (e == null) {
            return null;
        }
        String style = e.val();
        if (style == null || style.equals("nil") || style.equals("none")) {
            return NIL;
        }
        float width = Ooxml.integer(e.attr("sz"), 4) / 8f;
        width = Math.max(0.25f, Math.min(12, width));
        if (style.equals("double")) {
            width = Math.max(width, 0.25f);
        }
        float space = Math.max(0, Math.min(31, Ooxml.integer(e.attr("space"), 0)));
        Color color = Colors.attribute(e, theme);
        return new Border(style, width, space, color == null ? Color.BLACK : color);
    }

    boolean visible() {
        return !style.equals("nil") && !style.equals("none") && width > 0;
    }

    float total() {
        if (!visible()) {
            return 0;
        }
        float sum = 0;
        for (float f : stripes()) {
            sum += f;
        }
        return sum;
    }

    // The lines across the border from its outer edge inward, each but the last followed by the gap after it
    float[] stripes() {
        float w = width;
        return switch (style) {
            case "double" -> new float[] {w, w, w};
            case "triple" -> new float[] {w, w, w, w, w};
            case "thinThickSmallGap" -> new float[] {w / 2, 0.75f, w};
            case "thickThinSmallGap" -> new float[] {w, 0.75f, w / 2};
            case "thinThickMediumGap" -> new float[] {w / 2, w / 2, w};
            case "thickThinMediumGap" -> new float[] {w, w / 2, w / 2};
            case "thinThickLargeGap" -> new float[] {w / 2, w, w};
            case "thickThinLargeGap" -> new float[] {w, w, w / 2};
            case "thinThickThinSmallGap" -> new float[] {w / 2, 0.75f, w, 0.75f, w / 2};
            case "thinThickThinMediumGap" -> new float[] {w / 2, w / 2, w, w / 2, w / 2};
            case "thinThickThinLargeGap" -> new float[] {w / 2, w, w, w, w / 2};
            case "threeDEmboss", "threeDEngrave" -> new float[] {w / 2, w, w / 2};
            default -> new float[] {w};
        };
    }

    float weight() {
        if (!visible()) {
            return 0;
        }
        int rank = switch (style) {
            case "single" -> 1;
            case "thick" -> 2;
            case "double" -> 3;
            case "dotted" -> 4;
            case "dashed" -> 5;
            default -> 6;
        };
        return width * 100 - rank;
    }
}
