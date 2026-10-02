package stirling.software.officeconvert.topdf.docx;

import java.awt.geom.Ellipse2D;
import java.awt.geom.FlatteningPathIterator;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.Stroke;

// VML outlines: Office shape type numbers as DrawingML presets, and the plain commands of a v:path
final class ShapePath {

    private static final int MAX_COMMANDS = 20_000;

    private static final java.util.Set<String> TWO_LETTER = java.util.Set.of("nf", "ns", "ae", "al", "at", "ar", "wa",
            "wr", "qx", "qy", "qb", "ha", "hb", "hc", "hd", "he", "hf", "hg", "hh", "hi");

    private static final Map<Integer, String> PRESETS = Map.ofEntries(Map.entry(1, "rect"),
            Map.entry(2, "roundRect"), Map.entry(3, "ellipse"), Map.entry(4, "diamond"), Map.entry(5, "triangle"),
            Map.entry(6, "rtTriangle"), Map.entry(7, "parallelogram"), Map.entry(8, "trapezoid"),
            Map.entry(9, "hexagon"), Map.entry(10, "octagon"), Map.entry(11, "plus"), Map.entry(12, "star5"),
            Map.entry(13, "rightArrow"), Map.entry(15, "homePlate"), Map.entry(16, "cube"), Map.entry(21, "plaque"),
            Map.entry(22, "can"), Map.entry(23, "donut"), Map.entry(55, "chevron"), Map.entry(56, "pentagon"),
            Map.entry(57, "noSmoking"), Map.entry(58, "star8"), Map.entry(59, "star16"), Map.entry(60, "star32"),
            Map.entry(61, "wedgeRectCallout"), Map.entry(62, "wedgeRoundRectCallout"),
            Map.entry(63, "wedgeEllipseCallout"), Map.entry(64, "wave"), Map.entry(65, "foldedCorner"),
            Map.entry(66, "leftArrow"), Map.entry(67, "downArrow"), Map.entry(68, "upArrow"),
            Map.entry(69, "leftRightArrow"), Map.entry(70, "upDownArrow"), Map.entry(71, "irregularSeal1"),
            Map.entry(72, "irregularSeal2"), Map.entry(73, "lightningBolt"), Map.entry(74, "heart"),
            Map.entry(75, "rect"), Map.entry(76, "quadArrow"), Map.entry(77, "leftArrowCallout"),
            Map.entry(78, "rightArrowCallout"), Map.entry(79, "upArrowCallout"), Map.entry(80, "downArrowCallout"),
            Map.entry(84, "bevel"), Map.entry(85, "leftBracket"), Map.entry(86, "rightBracket"),
            Map.entry(87, "leftBrace"), Map.entry(88, "rightBrace"), Map.entry(92, "star24"),
            Map.entry(93, "stripedRightArrow"), Map.entry(94, "notchedRightArrow"), Map.entry(95, "blockArc"),
            Map.entry(96, "smileyFace"), Map.entry(97, "verticalScroll"), Map.entry(98, "horizontalScroll"),
            Map.entry(99, "circularArrow"), Map.entry(102, "curvedRightArrow"), Map.entry(103, "curvedLeftArrow"),
            Map.entry(104, "curvedUpArrow"), Map.entry(105, "curvedDownArrow"), Map.entry(106, "cloudCallout"),
            Map.entry(107, "ellipseRibbon"), Map.entry(108, "ellipseRibbon2"), Map.entry(109, "flowChartProcess"),
            Map.entry(110, "flowChartDecision"), Map.entry(111, "flowChartInputOutput"),
            Map.entry(112, "flowChartPredefinedProcess"), Map.entry(113, "flowChartInternalStorage"),
            Map.entry(114, "flowChartDocument"), Map.entry(115, "flowChartMultidocument"),
            Map.entry(116, "flowChartTerminator"), Map.entry(117, "flowChartPreparation"),
            Map.entry(118, "flowChartManualInput"), Map.entry(119, "flowChartManualOperation"),
            Map.entry(120, "flowChartConnector"), Map.entry(121, "flowChartPunchedCard"),
            Map.entry(122, "flowChartPunchedTape"), Map.entry(123, "flowChartSummingJunction"),
            Map.entry(124, "flowChartOr"), Map.entry(125, "flowChartCollate"), Map.entry(126, "flowChartSort"),
            Map.entry(127, "flowChartExtract"), Map.entry(128, "flowChartMerge"),
            Map.entry(130, "flowChartOnlineStorage"), Map.entry(131, "flowChartMagneticTape"),
            Map.entry(132, "flowChartMagneticDisk"), Map.entry(133, "flowChartMagneticDrum"),
            Map.entry(134, "flowChartDisplay"), Map.entry(135, "flowChartDelay"),
            Map.entry(176, "flowChartAlternateProcess"), Map.entry(177, "flowChartOffpageConnector"),
            Map.entry(183, "sun"), Map.entry(184, "moon"), Map.entry(185, "bracketPair"),
            Map.entry(186, "bracePair"), Map.entry(187, "star4"), Map.entry(188, "doubleWave"),
            Map.entry(202, "rect"));

    private ShapePath() {}

    // The preset for a VML shape type reference such as "#_x0000_t13", or null
    static String preset(String type) {
        if (type == null) {
            return null;
        }
        int t = type.lastIndexOf("_t");
        if (t < 0) {
            return null;
        }
        int n = 0;
        int digits = 0;
        for (int i = t + 2; i < type.length() && digits < 4; i++, digits++) {
            char c = type.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
            n = n * 10 + (c - '0');
        }
        return digits == 0 ? null : PRESETS.get(n);
    }

    // A v:path in its coordinate space mapped onto the box; null for formulas, arcs or anything else it cannot read
    static Path2D.Float vml(String path, float[] size, float[] origin, float x, float y, float w, float h) {
        if (path == null || path.isBlank() || size[0] == 0 || size[1] == 0) {
            return null;
        }
        float sx = w / size[0];
        float sy = h / size[1];
        Path2D.Float out = new Path2D.Float();
        String s = path.trim();
        int i = 0;
        char cmd = 0;
        float cx = 0;
        float cy = 0;
        int commands = 0;
        float[] nums = new float[6];
        while (i < s.length()) {
            if (++commands > MAX_COMMANDS) {
                return null;
            }
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == ',') {
                i++;
                continue;
            }
            if (Character.isLetter(c)) {
                String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
                String word = TWO_LETTER.contains(two) ? two : s.substring(i, i + 1);
                i += word.length();
                switch (word) {
                    case "x" -> {
                        if (out.getCurrentPoint() != null) {
                            out.closePath();
                        }
                        cmd = 0;
                        continue;
                    }
                    case "e", "nf", "ns" -> {
                        cmd = 0;
                        continue;
                    }
                    case "m", "l", "c", "t", "r", "v" -> {
                        cmd = word.charAt(0);
                    }
                    default -> {
                        return null;
                    }
                }
            }
            if (cmd == 0) {
                return null;
            }
            int need = cmd == 'c' || cmd == 'v' ? 6 : 2;
            int got = 0;
            while (got < need) {
                while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) {
                    i++;
                }
                if (i >= s.length() || Character.isLetter(s.charAt(i))) {
                    break;
                }
                if (s.charAt(i) == '@') {
                    return null;
                }
                int j = i;
                if (s.charAt(j) == '-' || s.charAt(j) == '+') {
                    j++;
                }
                while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) {
                    j++;
                }
                if (j == i) {
                    return null;
                }
                try {
                    nums[got++] = Float.parseFloat(s.substring(i, j));
                } catch (NumberFormatException e) {
                    return null;
                }
                i = j;
            }
            if (got == 0) {
                continue;
            }
            for (int k = got; k < need; k++) {
                nums[k] = 0;
            }
            switch (cmd) {
                case 'm', 't' -> {
                    cx = cmd == 't' ? cx + nums[0] : nums[0];
                    cy = cmd == 't' ? cy + nums[1] : nums[1];
                    out.moveTo(x + (cx - origin[0]) * sx, y + (cy - origin[1]) * sy);
                }
                case 'l', 'r' -> {
                    cx = cmd == 'r' ? cx + nums[0] : nums[0];
                    cy = cmd == 'r' ? cy + nums[1] : nums[1];
                    lineTo(out, x + (cx - origin[0]) * sx, y + (cy - origin[1]) * sy);
                }
                default -> {
                    float bx = cmd == 'v' ? cx : 0;
                    float by = cmd == 'v' ? cy : 0;
                    if (out.getCurrentPoint() == null) {
                        out.moveTo(x + (cx - origin[0]) * sx, y + (cy - origin[1]) * sy);
                    }
                    out.curveTo(x + (bx + nums[0] - origin[0]) * sx, y + (by + nums[1] - origin[1]) * sy,
                            x + (bx + nums[2] - origin[0]) * sx, y + (by + nums[3] - origin[1]) * sy,
                            x + (bx + nums[4] - origin[0]) * sx, y + (by + nums[5] - origin[1]) * sy);
                    cx = bx + nums[4];
                    cy = by + nums[5];
                }
            }
        }
        return out.getCurrentPoint() == null ? null : out;
    }

    static float[] pair(String v, float a, float b) {
        float[] out = {a, b};
        if (v == null) {
            return out;
        }
        String[] parts = v.split(",");
        for (int i = 0; i < Math.min(2, parts.length); i++) {
            try {
                float f = Float.parseFloat(parts[i].trim());
                if (Float.isFinite(f)) {
                    out[i] = f;
                }
            } catch (NumberFormatException e) {
                return out;
            }
        }
        return out;
    }

    // Arrowheads drawn at the ends of an open outline in the line's colour, sized from its width as Office does
    static void arrows(java.awt.Shape outline, Drawing.LineEnds ends, Stroke line, List<Op> ops) {
        if (ends == null || line == null) {
            return;
        }
        List<float[]> pts = new ArrayList<>();
        PathIterator it = new FlatteningPathIterator(outline.getPathIterator(null), 0.5, 8);
        float[] c = new float[6];
        while (!it.isDone() && pts.size() < 10_000) {
            int t = it.currentSegment(c);
            if (t == PathIterator.SEG_MOVETO && !pts.isEmpty()) {
                break;
            }
            if (t == PathIterator.SEG_MOVETO || t == PathIterator.SEG_LINETO) {
                pts.add(new float[] {c[0], c[1]});
            }
            it.next();
        }
        if (pts.size() < 2) {
            return;
        }
        float[] first = pts.get(0);
        float[] last = pts.get(pts.size() - 1);
        head(ends.head(), ends.headW(), ends.headL(), first, away(pts, first, true), line, ops);
        head(ends.tail(), ends.tailW(), ends.tailL(), last, away(pts, last, false), line, ops);
    }

    private static float[] away(List<float[]> pts, float[] end, boolean fromStart) {
        for (int k = 1; k < pts.size(); k++) {
            float[] p = pts.get(fromStart ? k : pts.size() - 1 - k);
            if (Math.hypot(p[0] - end[0], p[1] - end[1]) > 0.01) {
                return p;
            }
        }
        return null;
    }

    // A straight line stops inside a closed arrowhead, so a thick line never shows past the head's sides
    static java.awt.Shape trimmed(java.awt.Shape outline, Drawing.LineEnds ends, Stroke line) {
        if (ends == null || line == null || !(outline instanceof Line2D l)) {
            return outline;
        }
        double len = l.getP1().distance(l.getP2());
        double head = closed(ends.head()) ? headLength(ends.headL(), line) * 5 / 6 : 0;
        double tail = closed(ends.tail()) ? headLength(ends.tailL(), line) * 5 / 6 : 0;
        if (head + tail <= 0 || head + tail >= len) {
            return outline;
        }
        double ux = (l.getX2() - l.getX1()) / len;
        double uy = (l.getY2() - l.getY1()) / len;
        return new Line2D.Double(l.getX1() + ux * head, l.getY1() + uy * head, l.getX2() - ux * tail,
                l.getY2() - uy * tail);
    }

    private static boolean closed(String type) {
        return "triangle".equals(type) || "stealth".equals(type);
    }

    private static float headLength(String len, Stroke line) {
        return base(line) * scale(len);
    }

    // Office sizes arrowheads from the line width, but never from less than 2 pt
    private static float base(Stroke line) {
        return Math.max(2f, line.width());
    }

    private static float scale(String size) {
        return switch (size == null ? "med" : size) {
            case "sm" -> 2;
            case "lg" -> 5;
            default -> 3;
        };
    }

    private static void head(String type, String w, String len, float[] tip, float[] from, Stroke line, List<Op> ops) {
        if (type == null || type.equals("none") || from == null) {
            return;
        }
        float lw = base(line);
        float width = lw * scale(w);
        float length = lw * scale(len);
        double dx = tip[0] - from[0];
        double dy = tip[1] - from[1];
        double d = Math.hypot(dx, dy);
        float ux = (float) (dx / d);
        float uy = (float) (dy / d);
        float nx = -uy;
        float ny = ux;
        float bx = tip[0] - ux * length;
        float by = tip[1] - uy * length;
        Fill fill = Fill.solid(line.color());
        switch (type) {
            case "triangle", "stealth" -> {
                Path2D.Float p = new Path2D.Float();
                p.moveTo(tip[0], tip[1]);
                p.lineTo(bx + nx * width / 2, by + ny * width / 2);
                if (type.equals("stealth")) {
                    p.lineTo(tip[0] - ux * length * 0.7f, tip[1] - uy * length * 0.7f);
                }
                p.lineTo(bx - nx * width / 2, by - ny * width / 2);
                p.closePath();
                ops.add(new Op.Path(p, fill, null));
            }
            case "arrow" -> {
                Path2D.Float p = new Path2D.Float();
                p.moveTo(bx + nx * width / 2, by + ny * width / 2);
                p.lineTo(tip[0], tip[1]);
                p.lineTo(bx - nx * width / 2, by - ny * width / 2);
                ops.add(new Op.Path(p, null, Stroke.solid(line.width(), line.color())));
            }
            case "diamond" -> {
                Path2D.Float p = new Path2D.Float();
                p.moveTo(tip[0] + ux * length / 2, tip[1] + uy * length / 2);
                p.lineTo(tip[0] + nx * width / 2, tip[1] + ny * width / 2);
                p.lineTo(tip[0] - ux * length / 2, tip[1] - uy * length / 2);
                p.lineTo(tip[0] - nx * width / 2, tip[1] - ny * width / 2);
                p.closePath();
                ops.add(new Op.Path(p, fill, null));
            }
            case "oval" -> {
                float r = Math.max(width, length) / 2;
                ops.add(new Op.Path(new Ellipse2D.Float(tip[0] - r, tip[1] - r, 2 * r, 2 * r), fill, null));
            }
            default -> {
            }
        }
    }

    private static void lineTo(Path2D.Float p, float x, float y) {
        if (p.getCurrentPoint() == null) {
            p.moveTo(x, y);
        } else {
            p.lineTo(x, y);
        }
    }
}
