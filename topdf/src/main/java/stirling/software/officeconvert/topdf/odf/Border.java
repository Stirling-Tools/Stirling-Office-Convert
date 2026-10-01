package stirling.software.officeconvert.topdf.odf;

import java.util.Locale;

record Border(double width, String style, String color) {

    static Border parse(String spec, String lineWidths) {
        if (spec == null) {
            return null;
        }
        String s = spec.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("none") || s.equals("hidden")) {
            return null;
        }
        double width = 0.75;
        String style = "solid";
        String color = "000000";
        for (String t : s.split("\\s+")) {
            if (t.startsWith("#")) {
                color = Colors.hex(t, "000000");
            } else if (Character.isDigit(t.charAt(0)) || t.charAt(0) == '.') {
                width = Length.pt(t, width);
            } else {
                switch (t) {
                    case "thin" -> width = 0.75;
                    case "medium" -> width = 1.5;
                    case "thick" -> width = 3;
                    case "none", "hidden" -> {
                        return null;
                    }
                    case "solid", "dotted", "dashed", "double", "groove", "ridge", "inset", "outset", "dot-dash",
                            "dot-dot-dash", "fine-dashed", "dash-dot", "dash-dot-dot", "double-thin" -> style = t;
                    default -> {
                        String named = Colors.named(t);
                        if (named != null) {
                            color = named;
                        }
                    }
                }
            }
        }
        if (style.equals("double") && lineWidths != null) {
            double sum = 0;
            for (String t : lineWidths.trim().split("\\s+")) {
                sum += Length.pt(t, 0);
            }
            if (sum > 0) {
                width = sum;
            }
        }
        if (width <= 0) {
            return null;
        }
        return new Border(width, style, color);
    }

    String wordStyle() {
        return switch (style) {
            case "dotted" -> "dotted";
            case "dashed", "fine-dashed" -> "dashed";
            case "double", "double-thin" -> "double";
            case "groove" -> "threeDEngrave";
            case "ridge" -> "threeDEmboss";
            case "inset" -> "inset";
            case "outset" -> "outset";
            case "dot-dash", "dash-dot" -> "dotDash";
            case "dot-dot-dash", "dash-dot-dot" -> "dotDotDash";
            default -> "single";
        };
    }

    long eighths() {
        double w = style.startsWith("double") ? width / 3 : width;
        return Math.max(2, Math.min(96, Math.round(w * 8)));
    }

    String word(String element, double spacePt) {
        return "<w:" + element + " w:val=\"" + wordStyle() + "\" w:sz=\"" + eighths() + "\" w:space=\""
                + Math.max(0, Math.min(31, Math.round(spacePt))) + "\" w:color=\"" + color + "\"/>";
    }
}
