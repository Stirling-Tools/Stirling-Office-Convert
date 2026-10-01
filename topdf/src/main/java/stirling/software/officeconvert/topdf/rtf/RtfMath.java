package stirling.software.officeconvert.topdf.rtf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class RtfMath {

    static final int MAX_NODES = 20_000;

    static final int MAX_TEXT = 1 << 16;

    private static final Map<String, String> NAMES = new HashMap<>();

    private static final Set<String> VALUES = Set.of("type", "degHide", "chr", "limLoc", "subHide", "supHide", "grow",
            "begChr", "endChr", "sepChr", "shp", "count", "mcJc", "pos", "hideTop", "hideBot", "hideLeft", "hideRight",
            "strikeH", "strikeV", "strikeBLTR", "strikeTLBR", "opEmu", "noBreak", "diff", "aln", "vertJc", "show",
            "zeroWid", "zeroAsc", "zeroDesc", "transp", "argSz", "baseJc", "plcHide", "rSp", "rSpRule", "cGp",
            "cGpRule", "cSp", "maxDist", "objDist", "jc");

    private static final String[] STY = {"p", "b", "i", "bi"};

    private static final String[] SCR = {"roman", "script", "fraktur", "double-struck", "sans-serif", "monospace"};

    static {
        for (String n : List.of("oMathPara", "oMathParaPr", "oMath", "f", "fPr", "num", "den", "r", "sSup", "sSupPr",
                "sSub", "sSubPr", "sSubSup", "sSubSupPr", "sPre", "sPrePr", "sub", "sup", "e", "rad", "radPr", "deg",
                "nary", "naryPr", "d", "dPr", "m", "mPr", "mcs", "mc", "mcPr", "mr", "eqArr", "eqArrPr", "acc",
                "accPr", "bar", "barPr", "borderBox", "borderBoxPr", "box", "boxPr", "func", "funcPr", "fName",
                "limLow", "limLowPr", "limUpp", "limUppPr", "lim", "groupChr", "groupChrPr", "phant", "phantPr",
                "argPr")) {
            NAMES.put(n.toLowerCase(Locale.ROOT), n);
        }
        for (String n : VALUES) {
            NAMES.put(n.toLowerCase(Locale.ROOT), n);
        }
    }

    static final class Node {
        final String name;
        final List<Node> kids = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        final List<String> props = new ArrayList<>();
        final Node root;
        String rPr;
        int count;

        Node(String name, Node root) {
            this.name = name;
            this.root = root == null ? this : root;
        }
    }

    private RtfMath() {}

    static Node root() {
        return new Node("mmath", null);
    }

    static String element(String word) {
        if (word.length() < 2 || word.charAt(0) != 'm') {
            return null;
        }
        return NAMES.get(word.substring(1).toLowerCase(Locale.ROOT));
    }

    static Node open(Node parent, String name) {
        if (parent.root.count >= MAX_NODES) {
            return parent;
        }
        parent.root.count++;
        Node n = new Node(name, parent.root);
        parent.kids.add(n);
        return n;
    }

    static boolean runWord(Node n, String w, int p) {
        if (!n.name.equals("r")) {
            return false;
        }
        switch (w) {
            case "msty" -> n.props.add("<m:sty m:val=\"" + STY[Math.floorMod(p, STY.length)] + "\"/>");
            case "mscr" -> n.props.add("<m:scr m:val=\"" + SCR[Math.floorMod(p, SCR.length)] + "\"/>");
            case "mnor" -> n.props.add("<m:nor/>");
            case "mlit" -> n.props.add("<m:lit/>");
            default -> {
                return false;
            }
        }
        return true;
    }

    static void text(Node n, String s, String rPr) {
        if (n.root.text.length() + s.length() > MAX_TEXT) {
            return;
        }
        n.root.text.append(s);
        Node target = n;
        if (!VALUES.contains(n.name) && !n.name.equals("r")) {
            Node last = n.kids.isEmpty() ? null : n.kids.get(n.kids.size() - 1);
            if (last != null && last.name.equals("r") && last.props.isEmpty() && rPr.equals(last.rPr)) {
                target = last;
            } else {
                target = open(n, "r");
                if (target == n) {
                    return;
                }
            }
        }
        if (target.rPr == null) {
            target.rPr = rPr;
        }
        target.text.append(s);
    }

    static String xml(Node root) {
        StringBuilder b = new StringBuilder();
        boolean zone = false;
        for (Node k : root.kids) {
            zone |= k.name.equals("oMath") || k.name.equals("oMathPara");
        }
        if (!zone) {
            b.append("<m:oMath>");
            children(root, b);
            b.append("</m:oMath>");
        } else {
            for (Node k : root.kids) {
                if (k.name.equals("oMath") || k.name.equals("oMathPara")) {
                    write(k, b);
                }
            }
        }
        return b.toString();
    }

    private static void children(Node n, StringBuilder b) {
        for (Node k : n.kids) {
            write(k, b);
        }
    }

    private static void write(Node n, StringBuilder b) {
        if (VALUES.contains(n.name)) {
            String v = n.text.toString().strip();
            b.append("<m:").append(n.name);
            if (!v.isEmpty() || n.name.endsWith("Chr")) {
                b.append(" m:val=\"").append(Xml.attr(v)).append('"');
            }
            b.append("/>");
            return;
        }
        if (n.name.equals("r")) {
            b.append("<m:r>");
            if (!n.props.isEmpty()) {
                b.append("<m:rPr>");
                n.props.forEach(b::append);
                b.append("</m:rPr>");
            }
            b.append(n.rPr == null ? "" : n.rPr).append("<m:t xml:space=\"preserve\">");
            Xml.text(n.text, b);
            b.append("</m:t></m:r>");
            return;
        }
        b.append("<m:").append(n.name).append('>');
        children(n, b);
        b.append("</m:").append(n.name).append('>');
    }
}
