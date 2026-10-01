package stirling.software.officeconvert.topdf.odf;

import org.w3c.dom.Element;

final class WordPara {

    private WordPara() {}

    static boolean rtl(Props p) {
        String mode = p.get("style:writing-mode");
        return mode != null && (mode.startsWith("rl") || mode.equals("rl-tb"));
    }

    static String jc(Props p) {
        String a = p.get("fo:text-align");
        if (a == null) {
            return null;
        }
        boolean rtl = rtl(p);
        return switch (a) {
            case "center" -> "center";
            case "justify" -> "both";
            case "end" -> "right";
            case "start" -> "left";
            case "right" -> rtl ? "left" : "right";
            case "left" -> rtl ? "right" : "left";
            default -> null;
        };
    }

    static String spacing(Props p) {
        StringBuilder b = new StringBuilder();
        if (p.has("fo:margin-top")) {
            b.append(" w:before=\"").append(Math.max(0, Length.twips(p.pt("fo:margin-top", 0)))).append('"');
        }
        if (p.has("fo:margin-bottom")) {
            b.append(" w:after=\"").append(Math.max(0, Length.twips(p.pt("fo:margin-bottom", 0)))).append('"');
        }
        String lh = p.get("fo:line-height");
        String least = p.get("style:line-height-at-least");
        String leading = p.get("style:line-spacing");
        if (lh != null && !lh.equals("normal")) {
            if (Length.isPercent(lh)) {
                double pct = Length.percent(lh, 100);
                b.append(" w:line=\"").append(Math.max(1, Math.round(pct * 2.4))).append("\" w:lineRule=\"auto\"");
            } else {
                double pt = Length.pt(lh, Double.NaN);
                if (pt > 0) {
                    b.append(" w:line=\"").append(Length.twips(pt)).append("\" w:lineRule=\"exact\"");
                }
            }
        } else if (least != null) {
            double pt = Length.pt(least, Double.NaN);
            if (pt > 0) {
                b.append(" w:line=\"").append(Length.twips(pt)).append("\" w:lineRule=\"atLeast\"");
            }
        } else if (leading != null) {
            double pt = Length.pt(leading, 0);
            b.append(" w:line=\"").append(Math.max(1, Math.round(240 * (1 + pt / 13.8)))).append("\" w:lineRule=\"auto\"");
        } else if ("normal".equals(lh)) {
            b.append(" w:line=\"240\" w:lineRule=\"auto\"");
        }
        return b.isEmpty() ? "" : "<w:spacing" + b + "/>";
    }

    static String indent(Props p) {
        if (!p.has("fo:margin-left") && !p.has("fo:margin-right") && !p.has("fo:text-indent")) {
            return "";
        }
        StringBuilder b = new StringBuilder("<w:ind");
        b.append(" w:left=\"").append(Length.twips(p.pt("fo:margin-left", 0))).append('"');
        b.append(" w:right=\"").append(Length.twips(p.pt("fo:margin-right", 0))).append('"');
        double first = p.pt("fo:text-indent", 0);
        if (first < 0) {
            b.append(" w:hanging=\"").append(Length.twips(-first)).append('"');
        } else {
            b.append(" w:firstLine=\"").append(Length.twips(first)).append('"');
        }
        return b.append("/>").toString();
    }

    static String borders(Props p) {
        String all = p.get("fo:border");
        String[] sides = {"top", "left", "bottom", "right"};
        StringBuilder b = new StringBuilder();
        for (String side : sides) {
            String spec = p.get("fo:border-" + side);
            if (spec == null) {
                spec = all;
            }
            String widths = p.get("style:border-line-width-" + side);
            if (widths == null) {
                widths = p.get("style:border-line-width");
            }
            Border border = Border.parse(spec, widths);
            if (border == null) {
                continue;
            }
            double pad = p.pt("fo:padding-" + side, p.pt("fo:padding", 0));
            b.append(border.word(side, pad));
        }
        return b.isEmpty() ? "" : "<w:pBdr>" + b + "</w:pBdr>";
    }

    static String shading(Props p) {
        String bg = Colors.fill(p.get("fo:background-color"));
        return bg == null ? "" : "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"" + bg + "\"/>";
    }

    static String tabs(Props p, double origin) {
        Element stops = p.kid("tab-stops");
        if (stops == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (Element t : Dom.kids(stops, Ns.STYLE, "tab-stop")) {
            if (n++ > 64) {
                break;
            }
            double pos = Length.pt(Dom.attr(t, Ns.STYLE, "position"), Double.NaN);
            if (Double.isNaN(pos)) {
                continue;
            }
            String type = Dom.attr(t, Ns.STYLE, "type", "left");
            String val = switch (type) {
                case "center" -> "center";
                case "right" -> "right";
                case "char" -> "decimal";
                default -> "left";
            };
            String leaderText = Dom.attr(t, Ns.STYLE, "leader-text");
            String leaderStyle = Dom.attr(t, Ns.STYLE, "leader-style", "none");
            String leader = null;
            if (leaderText != null && !leaderText.isBlank()) {
                leader = switch (leaderText.trim()) {
                    case "." -> "dot";
                    case "-" -> "hyphen";
                    case "_" -> "underscore";
                    case "·" -> "middleDot";
                    default -> "dot";
                };
            } else if (!leaderStyle.equals("none")) {
                leader = switch (leaderStyle) {
                    case "dotted" -> "dot";
                    case "dash", "long-dash" -> "hyphen";
                    case "solid" -> "underscore";
                    default -> "dot";
                };
            }
            b.append("<w:tab w:val=\"").append(val).append("\"");
            if (leader != null) {
                b.append(" w:leader=\"").append(leader).append('"');
            }
            b.append(" w:pos=\"").append(Length.twips(pos + origin)).append("\"/>");
        }
        return b.isEmpty() ? "" : "<w:tabs>" + b + "</w:tabs>";
    }

    static String keeps(Props p) {
        StringBuilder b = new StringBuilder();
        if (p.is("fo:keep-with-next", "always")) {
            b.append("<w:keepNext/>");
        }
        if (p.is("fo:keep-together", "always")) {
            b.append("<w:keepLines/>");
        }
        int widows = (int) Length.pt(p.get("fo:widows"), -1);
        int orphans = (int) Length.pt(p.get("fo:orphans"), -1);
        if (widows >= 0 || orphans >= 0) {
            b.append(Math.max(widows, orphans) >= 2 ? "<w:widowControl/>" : "<w:widowControl w:val=\"0\"/>");
        }
        return b.toString();
    }
}
