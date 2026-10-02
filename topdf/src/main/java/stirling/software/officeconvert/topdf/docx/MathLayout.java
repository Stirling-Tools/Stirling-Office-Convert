package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.Stroke;
import stirling.software.officeconvert.topdf.pdf.TextStyle;

// Lays out Office Math (OMML) as two-dimensional math: fractions, scripts, radicals, n-ary operators, delimiters,
// matrices and accents, with the proportions of Cambria Math; nothing in the equation is evaluated
final class MathLayout {

    static final String MATH_FONT = "Cambria Math";

    private static final float SCRIPT = 0.73f;

    private static final float SCRIPT_SCRIPT = 0.6f;

    private static final float AXIS = 0.27f;

    private static final float RULE = 0.066f;

    private static final float ASCENT = 0.74f;

    private static final float DESCENT = 0.24f;

    private static final int MAX_DEPTH = 48;

    // Past this many pieces the zone falls back to its linear text, which wraps and pages like any other
    static final int MAX_PIECES = 20_000;

    private static final Set<String> FUNCTIONS = Set.of("sin", "cos", "tan", "cot", "sec", "csc", "sinh", "cosh",
            "tanh", "coth", "arcsin", "arccos", "arctan", "log", "ln", "lg", "exp", "lim", "max", "min", "sup", "inf",
            "det", "dim", "gcd", "deg", "arg", "ker", "Pr", "mod");

    private static final String BIN = "+−±∓×÷⋅·∗∘⊕⊗∩∪"
            + "∧∨∖";

    private static final String REL = "=<>≠≤≥≈≡∼≅∝→←↔⇒⇐"
            + "⇔∈∉⊂⊃⊆⊇≪≫≃≐≔≜⊥∥↦⟶";

    private static final String OPEN = "([{⟨〈⌊⌈‖";

    private static final String CLOSE = ")]}⟩〉⌋⌉";

    private final Fonts fonts;

    private final Function<XEl, RunProps> runProps;

    private final RunProps base;

    private final boolean display;

    private int depth;

    private int pieces;

    MathLayout(Fonts fonts, Function<XEl, RunProps> runProps, RunProps base, boolean display) {
        this.fonts = fonts;
        this.runProps = runProps;
        this.base = base;
        this.display = display;
    }

    // The size and colour one level of the equation is set in
    private record Style(float size, Color color, int level, boolean upright) {

        Style script() {
            return new Style(size * (level == 0 ? SCRIPT : level == 1 ? SCRIPT_SCRIPT / SCRIPT : 1), color,
                    level + 1, upright);
        }

        Style plain() {
            return new Style(size, color, level, true);
        }
    }

    MathBox equation(XEl oMath) {
        Color c = base.color == null || base.color == RunProps.AUTO ? Color.BLACK : base.color;
        return row(oMath == null ? List.of() : oMath.kids, new Style(base.fontSize(), c, 0, false));
    }

    private MathBox row(List<XEl> kids, Style st) {
        List<MathBox> parts = new ArrayList<>();
        if (++depth > MAX_DEPTH) {
            depth--;
            return new MathBox(0, st.size() * ASCENT, st.size() * DESCENT);
        }
        try {
            for (XEl k : kids) {
                element(k, st, parts);
            }
        } finally {
            depth--;
        }
        return join(parts, st);
    }

    private MathBox arg(XEl holder, Style st) {
        return holder == null ? new MathBox(0, 0, 0) : row(holder.kids, st);
    }

    // Spacing between neighbours follows the classes of TeX and Word: medium around binary operators, thick
    // around relations, thin after commas and around large operators
    private MathBox join(List<MathBox> parts, Style st) {
        MathBox out = new MathBox();
        if (parts.isEmpty()) {
            out.ascent = st.size() * ASCENT;
            out.descent = st.size() * DESCENT;
            return out;
        }
        float thin = st.size() * 3 / 18f;
        float medium = st.size() * 4 / 18f;
        float thick = st.size() * 5 / 18f;
        boolean script = st.level() > 0;
        MathBox prev = null;
        float x = 0;
        for (int i = 0; i < parts.size(); i++) {
            MathBox b = parts.get(i);
            if (b.kind == MathBox.Kind.BIN && (prev == null || prev.kind == MathBox.Kind.BIN
                    || prev.kind == MathBox.Kind.REL || prev.kind == MathBox.Kind.OPEN
                    || prev.kind == MathBox.Kind.PUNCT || prev.kind == MathBox.Kind.OP
                    || i + 1 == parts.size())) {
                b.kind = MathBox.Kind.ORD;
            }
            if (prev != null) {
                x += space(prev.kind, b.kind, thin, medium, thick, script);
            }
            out.place(b, x, 0);
            x += b.width;
            out.ascent = Math.max(out.ascent, b.ascent);
            out.descent = Math.max(out.descent, b.descent);
            prev = b;
        }
        out.width = x;
        out.italicCorrection = prev.italicCorrection;
        if (parts.size() == 1) {
            out.kind = parts.get(0).kind;
        } else {
            out.kind = MathBox.Kind.INNER;
        }
        return out;
    }

    private static float space(MathBox.Kind a, MathBox.Kind b, float thin, float medium, float thick,
            boolean script) {
        if (a == MathBox.Kind.BIN || b == MathBox.Kind.BIN) {
            return script ? 0 : medium;
        }
        if (a == MathBox.Kind.REL || b == MathBox.Kind.REL) {
            return a == b || script ? 0 : thick;
        }
        if (a == MathBox.Kind.PUNCT) {
            return script ? 0 : thin;
        }
        if (a == MathBox.Kind.OP && (b == MathBox.Kind.ORD || b == MathBox.Kind.OP)
                || b == MathBox.Kind.OP && a == MathBox.Kind.ORD) {
            return thin;
        }
        return 0;
    }

    private void count() {
        if (++pieces > MAX_PIECES) {
            throw new IllegalStateException("The equation is too large to lay out");
        }
    }

    private void element(XEl k, Style st, List<MathBox> out) {
        count();
        switch (k.name) {
            case "m:r" -> run(k, st, out);
            case "w:r" -> textRun(k, st, out);
            case "m:oMath", "m:e", "m:box", "m:argPr" -> out.add(row(k.kids, st));
            case "m:f" -> out.add(fraction(k, st));
            case "m:sSup", "m:sSub", "m:sSubSup" -> out.add(scripts(k, st));
            case "m:sPre" -> out.add(preScripts(k, st));
            case "m:rad" -> out.add(radical(k, st));
            case "m:nary" -> out.add(nary(k, st));
            case "m:d" -> out.add(delimited(k, st));
            case "m:m" -> out.add(matrix(k, st));
            case "m:eqArr" -> out.add(eqArr(k, st));
            case "m:acc" -> out.add(accent(k, st));
            case "m:bar" -> out.add(bar(k, st));
            case "m:borderBox" -> out.add(borderBox(k, st));
            case "m:func" -> out.add(function(k, st));
            case "m:limLow", "m:limUpp" -> out.add(limit(k, st));
            case "m:groupChr" -> out.add(groupChar(k, st));
            case "m:phant" -> out.add(phantom(k, st));
            case "w:ins", "w:moveTo", "w:smartTag", "w:customXml" -> {
                for (XEl c : k.kids) {
                    element(c, st, out);
                }
            }
            case "w:sdt" -> {
                XEl content = k.child("w:sdtContent");
                if (content != null) {
                    for (XEl c : content.kids) {
                        element(c, st, out);
                    }
                }
            }
            default -> {
            }
        }
    }

    // A math run: letters italic unless the run is plain or normal text, known function names upright
    private void run(XEl r, Style st, List<MathBox> out) {
        RunProps rp = runProps.apply(r.child("w:rPr"));
        XEl mr = r.child("m:rPr");
        boolean normal = flag(mr, "m:nor");
        String sty = attr(mr, "m:sty", null);
        boolean bold = "b".equals(sty) || "bi".equals(sty) || rp.isBold();
        boolean plainStyle = "p".equals(sty) || "b".equals(sty) || normal || st.upright();
        Style s = style(rp, st);
        StringBuilder text = new StringBuilder();
        for (XEl c : r.kids) {
            if (c.is("m:t") || c.is("w:t")) {
                text.append(c.text());
            } else if (c.is("w:tab")) {
                text.append(' ');
            } else if (c.is("w:br")) {
                text.append(' ');
            }
        }
        String family = rp.ascii != null && rp.asciiTheme == null && !normal ? rp.ascii : MATH_FONT;
        if (normal) {
            family = fonts.family(rp, Fonts.Slot.ASCII);
        }
        atoms(text.toString(), s, family, bold, plainStyle, normal, out);
    }

    private void textRun(XEl r, Style st, List<MathBox> out) {
        RunProps rp = runProps.apply(r.child("w:rPr"));
        StringBuilder text = new StringBuilder();
        for (XEl c : r.kids) {
            if (c.is("w:t")) {
                text.append(c.text());
            }
        }
        if (!text.isEmpty()) {
            Style s = style(rp, st);
            out.add(text(text.toString(), s, fonts.family(rp, Fonts.Slot.ASCII), rp.isBold(), rp.isItalic(),
                    MathBox.Kind.ORD));
        }
    }

    private Style style(RunProps rp, Style st) {
        Color c = rp.color == null || rp.color == RunProps.AUTO ? st.color() : rp.color;
        float size = st.level() == 0 && rp.size != null ? rp.fontSize() : st.size();
        return new Style(size, c, st.level(), st.upright());
    }

    private void atoms(String text, Style st, String family, boolean bold, boolean plain, boolean normal,
            List<MathBox> out) {
        if (normal) {
            out.add(text(text, st, family, bold, false, MathBox.Kind.ORD));
            return;
        }
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int n = Character.charCount(cp);
            if (Character.isLetter(cp)) {
                int j = i;
                while (j < text.length() && Character.isLetter(text.codePointAt(j))) {
                    j += Character.charCount(text.codePointAt(j));
                }
                String word = text.substring(i, j);
                if (FUNCTIONS.contains(word) || plain) {
                    out.add(text(word, st, family, bold, false, FUNCTIONS.contains(word) ? MathBox.Kind.OP
                            : MathBox.Kind.ORD));
                } else {
                    for (int k = 0; k < word.length(); ) {
                        int c = word.codePointAt(k);
                        String ch = new String(Character.toChars(c));
                        MathBox b = text(ch, st, family, bold, c < 0x370 || c >= 0x391 && c <= 0x3C9, MathBox.Kind.ORD);
                        out.add(b);
                        k += Character.charCount(c);
                    }
                }
                i = j;
                continue;
            }
            if (Character.isDigit(cp) || cp == '.' && i + 1 < text.length() && Character.isDigit(text.charAt(i + 1))) {
                int j = i;
                while (j < text.length() && (Character.isDigit(text.charAt(j)) || text.charAt(j) == '.'
                        && j + 1 < text.length() && Character.isDigit(text.charAt(j + 1)))) {
                    j++;
                }
                out.add(text(text.substring(i, j), st, family, bold, false, MathBox.Kind.ORD));
                i = j;
                continue;
            }
            if (cp == ' ') {
                count();
                MathBox sp = new MathBox(st.size() * 0.25f, 0, 0);
                out.add(sp);
                i += n;
                continue;
            }
            String ch = new String(Character.toChars(cp == '-' ? 0x2212 : cp == '*' ? 0x2217 : cp));
            MathBox.Kind kind = BIN.contains(ch) ? MathBox.Kind.BIN : REL.contains(ch) ? MathBox.Kind.REL
                    : OPEN.contains(ch) ? MathBox.Kind.OPEN : CLOSE.contains(ch) ? MathBox.Kind.CLOSE
                    : ch.equals(",") || ch.equals(";") ? MathBox.Kind.PUNCT : MathBox.Kind.ORD;
            out.add(text(ch, st, family, bold, false, kind));
            i += n;
        }
    }

    // Draws text with the math font, taking each character the font lacks from a fallback that has it
    MathBox text(String s, Style st, String family, boolean bold, boolean italic, MathBox.Kind kind) {
        count();
        MathBox b = new MathBox();
        b.kind = kind;
        FontFace face = fonts.face(family, bold, italic);
        float x = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            FontFace f = face.covers(cp) ? face : fonts.fallback(cp, face);
            int j = i + Character.charCount(cp);
            while (j < s.length()) {
                int c = s.codePointAt(j);
                FontFace g = face.covers(c) ? face : fonts.fallback(c, face);
                if (g != f) {
                    break;
                }
                j += Character.charCount(c);
            }
            String piece = s.substring(i, j);
            TextStyle ts = TextStyle.of(f, st.size()).color(st.color());
            count();
            b.ops.add(new Op.Text(x, 0, piece, ts));
            x += ts.width(piece);
            i = j;
        }
        b.width = x;
        b.ascent = st.size() * ASCENT;
        b.descent = st.size() * DESCENT;
        if (italic) {
            b.italicCorrection = st.size() * 0.06f;
        }
        return b;
    }

    private MathBox glyph(String ch, Style st) {
        return text(ch, st, MATH_FONT, false, false, MathBox.Kind.ORD);
    }

    private MathBox fraction(XEl f, Style st) {
        XEl pr = f.child("m:fPr");
        String type = attr(pr, "m:type", "bar");
        Style inner = st.level() > 0 ? st.script() : st;
        MathBox num = arg(f.child("m:num"), inner);
        MathBox den = arg(f.child("m:den"), inner);
        float s = st.size();
        MathBox out = new MathBox();
        if (type.equals("lin") || type.equals("skw")) {
            MathBox slash = glyph("/", st);
            out.place(num, 0, type.equals("skw") ? -s * 0.25f : 0);
            out.place(slash, num.width + s * 0.05f, 0);
            out.place(den, num.width + slash.width + s * 0.1f, type.equals("skw") ? s * 0.15f : 0);
            out.width = num.width + slash.width + den.width + s * 0.1f;
            out.ascent = Math.max(slash.ascent, num.ascent + (type.equals("skw") ? s * 0.25f : 0));
            out.descent = Math.max(slash.descent, den.descent + (type.equals("skw") ? s * 0.15f : 0));
            return out;
        }
        float rule = s * RULE;
        float gap = s * (display ? 0.12f : 0.09f);
        float axis = s * AXIS;
        float pad = s * 0.12f;
        float w = Math.max(num.width, den.width) + 2 * pad;
        float numBase = -(axis + rule / 2 + gap + num.descent);
        float denBase = -axis + rule / 2 + gap + den.ascent;
        out.place(num, (w - num.width) / 2, numBase);
        out.place(den, (w - den.width) / 2, denBase);
        if (!type.equals("noBar")) {
            out.ops.add(new Op.Rect(s * 0.02f, -axis - rule / 2, w - s * 0.04f, rule, Fill.solid(st.color()), null));
        }
        out.width = w;
        out.ascent = -numBase + num.ascent;
        out.descent = denBase + den.descent;
        out.kind = MathBox.Kind.INNER;
        return out;
    }

    private MathBox scripts(XEl k, Style st) {
        MathBox base = arg(k.child("m:e"), st);
        MathBox sup = k.child("m:sup") == null ? null : arg(k.child("m:sup"), st.script());
        MathBox sub = k.child("m:sub") == null ? null : arg(k.child("m:sub"), st.script());
        return attach(base, sup, sub, st);
    }

    private MathBox attach(MathBox base, MathBox sup, MathBox sub, Style st) {
        float s = st.size();
        MathBox out = new MathBox();
        out.place(base, 0, 0);
        float x = base.width;
        float supShift = 0;
        float subShift = 0;
        if (sup != null) {
            supShift = Math.max(s * 0.36f, base.ascent - s * ASCENT + s * 0.36f);
            supShift = Math.max(supShift, sup.descent + s * 0.14f);
        }
        if (sub != null) {
            subShift = Math.max(s * 0.2f, base.descent - s * DESCENT + s * 0.2f);
            subShift = Math.max(subShift, sub.ascent - s * 0.36f);
        }
        if (sup != null && sub != null) {
            float gap = (supShift - sup.descent) - (sub.ascent - subShift);
            if (gap < s * 0.14f) {
                subShift += s * 0.14f - gap;
            }
        }
        float w = 0;
        if (sup != null) {
            out.place(sup, x + base.italicCorrection, -supShift);
            w = Math.max(w, sup.width + base.italicCorrection);
            out.ascent = Math.max(base.ascent, supShift + sup.ascent);
        }
        if (sub != null) {
            out.place(sub, x, subShift);
            w = Math.max(w, sub.width);
            out.descent = Math.max(base.descent, subShift + sub.descent);
        }
        out.ascent = Math.max(out.ascent, base.ascent);
        out.descent = Math.max(out.descent, base.descent);
        out.width = x + w + s * 0.03f;
        out.kind = base.kind == MathBox.Kind.OP ? MathBox.Kind.OP : MathBox.Kind.ORD;
        return out;
    }

    private MathBox preScripts(XEl k, Style st) {
        MathBox sup = arg(k.child("m:sup"), st.script());
        MathBox sub = arg(k.child("m:sub"), st.script());
        MathBox base = arg(k.child("m:e"), st);
        float s = st.size();
        float w = Math.max(sup.width, sub.width);
        MathBox out = new MathBox();
        float supShift = Math.max(s * 0.36f, sup.descent + s * 0.14f);
        float subShift = Math.max(s * 0.2f, sub.ascent - s * 0.36f);
        out.place(sup, w - sup.width, -supShift);
        out.place(sub, w - sub.width, subShift);
        out.place(base, w + s * 0.03f, 0);
        out.width = w + s * 0.03f + base.width;
        out.ascent = Math.max(base.ascent, supShift + sup.ascent);
        out.descent = Math.max(base.descent, subShift + sub.descent);
        return out;
    }

    private MathBox radical(XEl k, Style st) {
        XEl pr = k.child("m:radPr");
        boolean degHide = flag(pr, "m:degHide");
        MathBox body = arg(k.child("m:e"), st);
        MathBox deg = degHide || k.child("m:deg") == null ? null : arg(k.child("m:deg"), st.script().script());
        if (deg != null && deg.width <= 0.01f) {
            deg = null;
        }
        float s = st.size();
        float rule = s * RULE;
        float gap = s * (display ? 0.14f : 0.06f);
        float top = body.ascent + gap + rule;
        float bottom = Math.max(body.descent, s * 0.1f);
        float h = top + bottom;
        float sign = s * 0.45f + h * 0.08f;
        float lead = 0;
        if (deg != null) {
            lead = Math.max(0, deg.width - sign * 0.45f);
        }
        MathBox out = new MathBox();
        Path2D.Float p = new Path2D.Float();
        float x0 = lead;
        float tickY = bottom - h * 0.45f;
        p.moveTo(x0, tickY + s * 0.04f);
        p.lineTo(x0 + sign * 0.22f, tickY - s * 0.03f);
        p.lineTo(x0 + sign * 0.52f, bottom);
        p.lineTo(x0 + sign, -top + rule / 2);
        p.lineTo(x0 + sign + body.width + s * 0.08f, -top + rule / 2);
        out.ops.add(new Op.Path(p, null, Stroke.solid(rule, st.color()).join(Stroke.Join.MITER)));
        out.place(body, x0 + sign + s * 0.04f, 0);
        if (deg != null) {
            out.place(deg, 0, tickY - s * 0.08f - deg.descent);
        }
        out.width = x0 + sign + body.width + s * 0.1f;
        out.ascent = Math.max(top + rule / 2, deg == null ? 0 : -(tickY - s * 0.08f - deg.descent) + deg.ascent);
        out.descent = bottom;
        return out;
    }

    private MathBox nary(XEl k, Style st) {
        XEl pr = k.child("m:naryPr");
        String chr = attr(pr, "m:chr", "∫");
        if (chr.isEmpty()) {
            chr = "∫";
        }
        boolean integral = chr.codePointAt(0) >= 0x222B && chr.codePointAt(0) <= 0x2233;
        String loc = display ? attr(pr, "m:limLoc", integral ? "subSup" : "undOvr") : "subSup";
        float s = st.size();
        float factor = display && st.level() == 0 ? (integral ? 2.0f : 1.55f) : (integral ? 1.35f : 1.15f);
        Style big = new Style(s * factor, st.color(), st.level(), true);
        MathBox op = text(chr, big, MATH_FONT, false, false, MathBox.Kind.OP);
        if (!integral && display && st.level() == 0) {
            // the display form of a large operator is taller than the text glyph, not much wider
            float stretch = 1.35f;
            MathBox tall = new MathBox(op.width, op.ascent * stretch, op.descent * stretch);
            tall.kind = MathBox.Kind.OP;
            tall.ops.add(new Op.Group(0, 0, AffineTransform.getScaleInstance(1, stretch), null, op.ops));
            op = tall;
        }
        float axis = s * AXIS;
        float opCenter = (op.ascent - op.descent) / 2;
        float shift = -(axis - opCenter);
        op.ascent = op.ascent - shift;
        op.descent = op.descent + shift;
        MathBox opBox = new MathBox(op.width, op.ascent, op.descent);
        opBox.place(op, 0, shift);
        MathBox sub = flag(pr, "m:subHide") || k.child("m:sub") == null ? null : arg(k.child("m:sub"), st.script());
        MathBox sup = flag(pr, "m:supHide") || k.child("m:sup") == null ? null : arg(k.child("m:sup"), st.script());
        if (sub != null && sub.width <= 0.01f) {
            sub = null;
        }
        if (sup != null && sup.width <= 0.01f) {
            sup = null;
        }
        MathBox head;
        if (loc.equals("undOvr") && (sub != null || sup != null)) {
            head = new MathBox();
            float w = Math.max(opBox.width, Math.max(sub == null ? 0 : sub.width, sup == null ? 0 : sup.width));
            head.place(opBox, (w - opBox.width) / 2, 0);
            head.ascent = opBox.ascent;
            head.descent = opBox.descent;
            float gap = s * 0.1f;
            if (sup != null) {
                float y = -(opBox.ascent + gap + sup.descent);
                head.place(sup, (w - sup.width) / 2, y);
                head.ascent = -y + sup.ascent;
            }
            if (sub != null) {
                float y = opBox.descent + gap + sub.ascent;
                head.place(sub, (w - sub.width) / 2, y);
                head.descent = y + sub.descent;
            }
            head.width = w;
        } else if (sub != null || sup != null) {
            head = new MathBox();
            head.place(opBox, 0, 0);
            float x = opBox.width + (integral ? -s * 0.08f : s * 0.03f);
            float w = 0;
            head.ascent = opBox.ascent;
            head.descent = opBox.descent;
            if (sup != null) {
                float y = -(opBox.ascent - sup.ascent * 0.6f);
                head.place(sup, x + (integral ? s * 0.12f : 0), y);
                head.ascent = Math.max(head.ascent, -y + sup.ascent);
                w = Math.max(w, sup.width + (integral ? s * 0.12f : 0));
            }
            if (sub != null) {
                float y = opBox.descent - sub.descent * 0.4f;
                head.place(sub, x, y);
                head.descent = Math.max(head.descent, y + sub.descent);
                w = Math.max(w, sub.width);
            }
            head.width = x + w;
        } else {
            head = opBox;
        }
        MathBox body = arg(k.child("m:e"), st);
        MathBox out = new MathBox();
        out.place(head, 0, 0);
        float gap = s * 3 / 18f;
        out.place(body, head.width + gap, 0);
        out.width = head.width + gap + body.width;
        out.ascent = Math.max(head.ascent, body.ascent);
        out.descent = Math.max(head.descent, body.descent);
        out.kind = MathBox.Kind.OP;
        return out;
    }

    private MathBox delimited(XEl k, Style st) {
        XEl pr = k.child("m:dPr");
        String beg = attr(pr, "m:begChr", "(");
        String end = attr(pr, "m:endChr", ")");
        String sep = attr(pr, "m:sepChr", "|");
        boolean grow = pr == null || pr.child("m:grow") == null || flag(pr, "m:grow");
        List<MathBox> parts = new ArrayList<>();
        for (XEl e : k.children("m:e")) {
            parts.add(row(e.kids, st));
        }
        float s = st.size();
        float axis = s * AXIS;
        float asc = s * ASCENT;
        float desc = s * DESCENT;
        for (MathBox p : parts) {
            asc = Math.max(asc, p.ascent);
            desc = Math.max(desc, p.descent);
        }
        float half = Math.max(asc - axis, desc + axis);
        MathBox out = new MathBox();
        float x = 0;
        if (!beg.isEmpty()) {
            MathBox b = fence(beg, st, grow ? half : 0);
            out.place(b, x, 0);
            x += b.width;
            out.ascent = Math.max(out.ascent, b.ascent);
            out.descent = Math.max(out.descent, b.descent);
        }
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0 && !sep.isEmpty()) {
                MathBox b = fence(sep, st, grow ? half : 0);
                out.place(b, x, 0);
                x += b.width;
            }
            MathBox p = parts.get(i);
            out.place(p, x, 0);
            x += p.width;
            out.ascent = Math.max(out.ascent, p.ascent);
            out.descent = Math.max(out.descent, p.descent);
        }
        if (!end.isEmpty()) {
            MathBox b = fence(end, st, grow ? half : 0);
            out.place(b, x, 0);
            x += b.width;
            out.ascent = Math.max(out.ascent, b.ascent);
            out.descent = Math.max(out.descent, b.descent);
        }
        out.width = x;
        out.kind = MathBox.Kind.INNER;
        return out;
    }

    // A bracket stretched to cover the contents symmetrically about the math axis once they outgrow one line
    private MathBox fence(String ch, Style st, float half) {
        MathBox g = glyph(ch, st);
        float s = st.size();
        float natural = s * (ASCENT + DESCENT);
        float need = 2 * half + s * 0.1f;
        if (half <= 0 || need <= natural * 1.05f) {
            return g;
        }
        float k = need / natural;
        float axis = s * AXIS;
        float center = (s * ASCENT - s * DESCENT) / 2;
        MathBox out = new MathBox();
        AffineTransform t = new AffineTransform();
        t.translate(0, -axis);
        t.scale(1, k);
        t.translate(0, center);
        out.ops.add(new Op.Group(0, 0, t, null, g.ops));
        out.width = g.width * Math.min(1.4f, 1 + (k - 1) * 0.08f);
        out.ascent = axis + need / 2;
        out.descent = need / 2 - axis;
        return out;
    }

    private MathBox matrix(XEl k, Style st) {
        List<List<MathBox>> rows = new ArrayList<>();
        int cols = 0;
        for (XEl r : k.children("m:mr")) {
            List<MathBox> cells = new ArrayList<>();
            for (XEl e : r.children("m:e")) {
                cells.add(row(e.kids, st));
            }
            cols = Math.max(cols, cells.size());
            rows.add(cells);
        }
        return grid(rows, cols, st, st.size() * 1.0f, true);
    }

    private MathBox grid(List<List<MathBox>> rows, int cols, Style st, float colGap, boolean centerCells) {
        float s = st.size();
        float[] widths = new float[cols];
        float[] asc = new float[rows.size()];
        float[] desc = new float[rows.size()];
        for (int r = 0; r < rows.size(); r++) {
            asc[r] = s * ASCENT;
            desc[r] = s * DESCENT;
            for (int c = 0; c < rows.get(r).size(); c++) {
                MathBox b = rows.get(r).get(c);
                widths[c] = Math.max(widths[c], b.width);
                asc[r] = Math.max(asc[r], b.ascent);
                desc[r] = Math.max(desc[r], b.descent);
            }
        }
        float rowGap = s * 0.2f;
        float total = 0;
        for (int r = 0; r < rows.size(); r++) {
            total += asc[r] + desc[r] + (r > 0 ? rowGap : 0);
        }
        float axis = s * AXIS;
        float y = -(axis + total / 2);
        MathBox out = new MathBox();
        float w = 0;
        for (int c = 0; c < cols; c++) {
            w += widths[c] + (c > 0 ? colGap : 0);
        }
        for (int r = 0; r < rows.size(); r++) {
            y += (r > 0 ? rowGap : 0) + asc[r];
            float x = 0;
            List<MathBox> cells = rows.get(r);
            if (!centerCells && cells.size() == 1) {
                out.place(cells.get(0), (w - cells.get(0).width) / 2, y);
            } else {
                for (int c = 0; c < cells.size(); c++) {
                    MathBox b = cells.get(c);
                    out.place(b, x + (centerCells ? (widths[c] - b.width) / 2 : 0), y);
                    x += widths[c] + colGap;
                }
            }
            y += desc[r];
        }
        out.width = w;
        out.ascent = axis + total / 2;
        out.descent = total / 2 - axis;
        out.kind = MathBox.Kind.INNER;
        return out;
    }

    private MathBox eqArr(XEl k, Style st) {
        List<List<MathBox>> rows = new ArrayList<>();
        for (XEl e : k.children("m:e")) {
            rows.add(List.of(row(e.kids, st)));
        }
        return grid(rows, 1, st, 0, false);
    }

    private MathBox accent(XEl k, Style st) {
        MathBox base = arg(k.child("m:e"), st);
        String chr = attr(k.child("m:accPr"), "m:chr", "̂");
        float s = st.size();
        MathBox out = new MathBox();
        out.place(base, 0, 0);
        out.width = base.width;
        out.descent = base.descent;
        float top = base.ascent;
        int cp = chr.isEmpty() ? 0x0302 : chr.codePointAt(0);
        if (cp == 0x0305 || cp == 0x0304 || cp == 0x00AF || cp == 0x203E) {
            float y = -(top + s * 0.06f);
            out.ops.add(new Op.Rect(0, y - s * RULE, base.width, s * RULE, Fill.solid(st.color()), null));
            out.ascent = -y + s * RULE;
            return out;
        }
        if (cp == 0x20D7 || cp == 0x2192 || cp == 0x20D6 || cp == 0x2190) {
            float y = -(top + s * 0.12f);
            boolean left = cp == 0x20D6 || cp == 0x2190;
            Path2D.Float p = new Path2D.Float();
            float x0 = 0;
            float x1 = Math.max(base.width, s * 0.4f);
            p.moveTo(x0, y);
            p.lineTo(x1, y);
            float tip = left ? x0 : x1;
            float back = left ? s * 0.12f : -s * 0.12f;
            p.moveTo(tip + back, y - s * 0.08f);
            p.lineTo(tip, y);
            p.lineTo(tip + back, y + s * 0.08f);
            out.ops.add(new Op.Path(p, null, Stroke.solid(s * RULE, st.color())));
            out.ascent = -y + s * 0.1f;
            return out;
        }
        String spacing = switch (cp) {
            case 0x0302 -> "ˆ";
            case 0x0303 -> "˜";
            case 0x0307 -> "˙";
            case 0x0308 -> "¨";
            case 0x0301 -> "´";
            case 0x0300 -> "`";
            case 0x030C -> "ˇ";
            case 0x0306 -> "˘";
            case 0x030A -> "˚";
            default -> new String(Character.toChars(cp));
        };
        MathBox acc = glyph(spacing, st);
        float y = top - s * 0.62f;
        out.place(acc, (base.width - acc.width) / 2 + base.italicCorrection, -y);
        out.ascent = Math.max(base.ascent, y + s * 0.95f);
        return out;
    }

    private MathBox bar(XEl k, Style st) {
        MathBox base = arg(k.child("m:e"), st);
        boolean top = "top".equals(attr(k.child("m:barPr"), "m:pos", "bot"));
        float s = st.size();
        MathBox out = new MathBox();
        out.place(base, 0, 0);
        out.width = base.width;
        out.ascent = base.ascent;
        out.descent = base.descent;
        float t = s * RULE;
        if (top) {
            float y = -(base.ascent + s * 0.06f) - t;
            out.ops.add(new Op.Rect(0, y, base.width, t, Fill.solid(st.color()), null));
            out.ascent = -y;
        } else {
            float y = base.descent + s * 0.06f;
            out.ops.add(new Op.Rect(0, y, base.width, t, Fill.solid(st.color()), null));
            out.descent = y + t;
        }
        return out;
    }

    private MathBox borderBox(XEl k, Style st) {
        MathBox base = arg(k.child("m:e"), st);
        float s = st.size();
        float pad = s * 0.12f;
        MathBox out = new MathBox();
        out.place(base, pad, 0);
        out.width = base.width + 2 * pad;
        out.ascent = base.ascent + pad;
        out.descent = base.descent + pad;
        out.ops.add(new Op.Rect(s * RULE / 2, -out.ascent + s * RULE / 2, out.width - s * RULE,
                out.ascent + out.descent - s * RULE, null, Stroke.solid(s * RULE, st.color())));
        return out;
    }

    private MathBox function(XEl k, Style st) {
        MathBox name = arg(k.child("m:fName"), st.plain());
        MathBox body = arg(k.child("m:e"), st);
        float gap = st.size() * 3 / 18f;
        MathBox out = new MathBox();
        out.place(name, 0, 0);
        out.place(body, name.width + gap, 0);
        out.width = name.width + gap + body.width;
        out.ascent = Math.max(name.ascent, body.ascent);
        out.descent = Math.max(name.descent, body.descent);
        out.kind = MathBox.Kind.OP;
        return out;
    }

    private MathBox limit(XEl k, Style st) {
        boolean lower = k.is("m:limLow");
        MathBox base = arg(k.child("m:e"), st);
        MathBox lim = arg(k.child("m:lim"), st.script());
        float s = st.size();
        float w = Math.max(base.width, lim.width);
        MathBox out = new MathBox();
        out.place(base, (w - base.width) / 2, 0);
        out.width = w;
        out.ascent = base.ascent;
        out.descent = base.descent;
        float gap = s * 0.08f;
        if (lower) {
            float y = base.descent + gap + lim.ascent;
            out.place(lim, (w - lim.width) / 2, y);
            out.descent = y + lim.descent;
        } else {
            float y = -(base.ascent + gap + lim.descent);
            out.place(lim, (w - lim.width) / 2, y);
            out.ascent = -y + lim.ascent;
        }
        out.kind = base.kind == MathBox.Kind.OP ? MathBox.Kind.OP : MathBox.Kind.ORD;
        return out;
    }

    // A brace or other character stretched along the contents, under them unless its position says top
    private MathBox groupChar(XEl k, Style st) {
        XEl pr = k.child("m:groupChrPr");
        String chr = attr(pr, "m:chr", "⏟");
        boolean top = "top".equals(attr(pr, "m:pos", "bot"));
        MathBox base = arg(k.child("m:e"), st);
        float s = st.size();
        MathBox out = new MathBox();
        out.place(base, 0, 0);
        out.width = base.width;
        out.ascent = base.ascent;
        out.descent = base.descent;
        float w = Math.max(base.width, s * 0.5f);
        float depthY = s * 0.18f;
        Path2D.Float p = new Path2D.Float();
        boolean brace = chr.equals("⏟") || chr.equals("⏞");
        float y0 = top ? -(base.ascent + s * 0.06f) : base.descent + s * 0.06f;
        float dir = top ? -1 : 1;
        if (brace) {
            p.moveTo(0, y0);
            p.quadTo(0, y0 + dir * depthY / 2, w * 0.1f, y0 + dir * depthY / 2);
            p.lineTo(w * 0.4f, y0 + dir * depthY / 2);
            p.quadTo(w / 2, y0 + dir * depthY / 2, w / 2, y0 + dir * depthY);
            p.quadTo(w / 2, y0 + dir * depthY / 2, w * 0.6f, y0 + dir * depthY / 2);
            p.lineTo(w * 0.9f, y0 + dir * depthY / 2);
            p.quadTo(w, y0 + dir * depthY / 2, w, y0);
        } else {
            p.moveTo(0, y0 + dir * depthY / 2);
            p.lineTo(w, y0 + dir * depthY / 2);
        }
        out.ops.add(new Op.Path(p, null, Stroke.solid(s * RULE, st.color())));
        if (top) {
            out.ascent = base.ascent + s * 0.06f + depthY;
        } else {
            out.descent = base.descent + s * 0.06f + depthY;
        }
        return out;
    }

    private MathBox phantom(XEl k, Style st) {
        XEl pr = k.child("m:phantPr");
        boolean show = pr == null || pr.child("m:show") == null || flag(pr, "m:show");
        MathBox base = arg(k.child("m:e"), st);
        if (show) {
            return base;
        }
        MathBox out = new MathBox(flag(pr, "m:zeroWid") ? 0 : base.width, flag(pr, "m:zeroAsc") ? 0 : base.ascent,
                flag(pr, "m:zeroDesc") ? 0 : base.descent);
        return out;
    }

    static String attr(XEl pr, String name, String fallback) {
        XEl c = pr == null ? null : pr.child(name);
        if (c == null) {
            return fallback;
        }
        String v = c.attr("m:val", c.attr("val"));
        return v == null ? fallback : v;
    }

    static boolean flag(XEl pr, String name) {
        XEl c = pr == null ? null : pr.child(name);
        return c != null && Ooxml.flag(c.attr("m:val", c.attr("val")), true);
    }
}
