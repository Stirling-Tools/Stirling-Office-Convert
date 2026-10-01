package stirling.software.officeconvert.topdf.docx;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

// Turns an Office Math zone into laid-out equation objects in the paragraph; a display equation (m:oMathPara)
// sits on its own line, centred between the margins unless its justification says otherwise
final class MathReader {

    private MathReader() {}

    static boolean read(XEl zone, List<Inline> out, RunProps paraRun, Inline.Link link, Fonts fonts,
            Function<XEl, RunProps> runProps) {
        if (fonts == null || zone == null) {
            return false;
        }
        List<Inline> laid = new ArrayList<>();
        if (!laidOut(zone, laid, paraRun, link, fonts, runProps)) {
            return false;
        }
        out.addAll(laid);
        return true;
    }

    static boolean display(XEl zone, List<Inline> out, RunProps paraRun, Inline.Link link, Fonts fonts,
            Function<XEl, RunProps> runProps, boolean first) {
        if (fonts == null || zone == null) {
            return false;
        }
        try {
            Inline.Obj o = object(zone, paraRun, link, fonts, runProps, true);
            if (first) {
                out.add(new Inline.PTab("center", "margin", (char) 0, paraRun));
            }
            out.add(o);
            return true;
        } catch (RuntimeException | StackOverflowError e) {
            return false;
        }
    }

    static boolean alone(List<XEl> kids) {
        boolean math = false;
        for (XEl k : kids) {
            switch (k.name) {
                case "m:oMath" -> math = true;
                case "w:pPr", "w:bookmarkStart", "w:bookmarkEnd", "w:proofErr", "w:permStart", "w:permEnd" -> {
                }
                case "w:r" -> {
                    for (XEl c : k.kids) {
                        if (!c.is("w:rPr")) {
                            return false;
                        }
                    }
                }
                default -> {
                    return false;
                }
            }
        }
        return math;
    }

    private static boolean laidOut(XEl zone, List<Inline> out, RunProps paraRun, Inline.Link link, Fonts fonts,
            Function<XEl, RunProps> runProps) {
        try {
            if (zone.is("m:oMathPara")) {
                String jc = MathLayout.attr(zone.child("m:oMathParaPr"), "m:jc", "centerGroup");
                boolean first = true;
                for (XEl k : zone.kids) {
                    if (!k.is("m:oMath")) {
                        continue;
                    }
                    if (!first) {
                        out.add(new Inline.Break("textWrapping", paraRun));
                    }
                    first = false;
                    String align = switch (jc) {
                        case "left" -> null;
                        case "right" -> "right";
                        default -> "center";
                    };
                    if (align != null) {
                        out.add(new Inline.PTab(align, "margin", (char) 0, paraRun));
                    }
                    out.add(object(k, paraRun, link, fonts, runProps, true));
                }
                return true;
            }
            out.add(object(zone, paraRun, link, fonts, runProps, false));
            return true;
        } catch (RuntimeException | StackOverflowError e) {
            return false;
        }
    }

    private static Inline.Obj object(XEl oMath, RunProps paraRun, Inline.Link link, Fonts fonts,
            Function<XEl, RunProps> runProps, boolean display) {
        RunProps base = firstRun(oMath, runProps, paraRun);
        MathBox box = new MathLayout(fonts, runProps, base, display).equation(oMath);
        Drawing d = new Drawing();
        d.inline = true;
        d.wrap = "inline";
        d.width = Math.max(0.1f, box.width);
        d.height = Math.max(0.1f, box.ascent + box.descent);
        d.baselineDepth = Math.max(0, box.descent);
        d.graphic = new Drawing.MathGraphic(box);
        return new Inline.Obj(d, paraRun, link);
    }

    // The equation takes its size from its first run, as Word sizes the whole zone
    private static RunProps firstRun(XEl e, Function<XEl, RunProps> runProps, RunProps paraRun) {
        XEl r = find(e, 0);
        return r == null ? paraRun : runProps.apply(r.child("w:rPr"));
    }

    private static XEl find(XEl e, int depth) {
        if (depth > 40) {
            return null;
        }
        for (XEl k : e.kids) {
            if (k.is("m:r")) {
                return k;
            }
            XEl f = find(k, depth + 1);
            if (f != null) {
                return f;
            }
        }
        return null;
    }
}
