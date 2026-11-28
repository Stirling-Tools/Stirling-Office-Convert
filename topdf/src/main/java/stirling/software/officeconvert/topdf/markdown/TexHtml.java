package stirling.software.officeconvert.topdf.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TexHtml {

    private static final int MAX_DEPTH = 40;

    private static final int MAX_LENGTH = 20_000;

    static final int MAX_CELLS = 10_000;

    private static final Map<String, String> SYMBOLS = Map.ofEntries(
            Map.entry("alpha", "α"), Map.entry("beta", "β"), Map.entry("gamma", "γ"), Map.entry("delta", "δ"),
            Map.entry("epsilon", "ϵ"), Map.entry("varepsilon", "ε"), Map.entry("zeta", "ζ"), Map.entry("eta", "η"),
            Map.entry("theta", "θ"), Map.entry("vartheta", "ϑ"), Map.entry("iota", "ι"), Map.entry("kappa", "κ"),
            Map.entry("lambda", "λ"), Map.entry("mu", "μ"), Map.entry("nu", "ν"), Map.entry("xi", "ξ"),
            Map.entry("pi", "π"), Map.entry("varpi", "ϖ"), Map.entry("rho", "ρ"), Map.entry("varrho", "ϱ"),
            Map.entry("sigma", "σ"), Map.entry("varsigma", "ς"), Map.entry("tau", "τ"), Map.entry("upsilon", "υ"),
            Map.entry("phi", "ϕ"), Map.entry("varphi", "φ"), Map.entry("chi", "χ"), Map.entry("psi", "ψ"),
            Map.entry("omega", "ω"), Map.entry("Gamma", "Γ"), Map.entry("Delta", "Δ"), Map.entry("Theta", "Θ"),
            Map.entry("Lambda", "Λ"), Map.entry("Xi", "Ξ"), Map.entry("Pi", "Π"), Map.entry("Sigma", "Σ"),
            Map.entry("Upsilon", "Υ"), Map.entry("Phi", "Φ"), Map.entry("Psi", "Ψ"), Map.entry("Omega", "Ω"),
            Map.entry("times", "×"), Map.entry("cdot", "⋅"), Map.entry("div", "÷"), Map.entry("pm", "±"),
            Map.entry("mp", "∓"), Map.entry("ast", "∗"), Map.entry("star", "⋆"), Map.entry("circ", "∘"),
            Map.entry("bullet", "∙"), Map.entry("oplus", "⊕"), Map.entry("otimes", "⊗"), Map.entry("wedge", "∧"),
            Map.entry("land", "∧"), Map.entry("vee", "∨"), Map.entry("lor", "∨"), Map.entry("neg", "¬"),
            Map.entry("lnot", "¬"), Map.entry("leq", "≤"), Map.entry("le", "≤"), Map.entry("geq", "≥"),
            Map.entry("ge", "≥"), Map.entry("neq", "≠"), Map.entry("ne", "≠"), Map.entry("approx", "≈"),
            Map.entry("equiv", "≡"), Map.entry("sim", "∼"), Map.entry("simeq", "≃"), Map.entry("cong", "≅"),
            Map.entry("propto", "∝"), Map.entry("ll", "≪"), Map.entry("gg", "≫"), Map.entry("prec", "≺"),
            Map.entry("succ", "≻"), Map.entry("in", "∈"), Map.entry("notin", "∉"), Map.entry("ni", "∋"),
            Map.entry("subset", "⊂"), Map.entry("supset", "⊃"), Map.entry("subseteq", "⊆"), Map.entry("supseteq", "⊇"),
            Map.entry("cup", "∪"), Map.entry("cap", "∩"), Map.entry("setminus", "∖"), Map.entry("emptyset", "∅"),
            Map.entry("varnothing", "∅"), Map.entry("forall", "∀"), Map.entry("exists", "∃"), Map.entry("nexists", "∄"),
            Map.entry("partial", "∂"), Map.entry("nabla", "∇"), Map.entry("infty", "∞"), Map.entry("aleph", "ℵ"),
            Map.entry("hbar", "ℏ"), Map.entry("ell", "ℓ"), Map.entry("Re", "ℜ"), Map.entry("Im", "ℑ"),
            Map.entry("angle", "∠"), Map.entry("perp", "⊥"), Map.entry("parallel", "∥"), Map.entry("mid", "∣"),
            Map.entry("to", "→"), Map.entry("rightarrow", "→"), Map.entry("leftarrow", "←"), Map.entry("gets", "←"),
            Map.entry("leftrightarrow", "↔"), Map.entry("Rightarrow", "⇒"), Map.entry("Leftarrow", "⇐"),
            Map.entry("Leftrightarrow", "⇔"), Map.entry("implies", "⟹"), Map.entry("iff", "⟺"), Map.entry("mapsto", "↦"),
            Map.entry("uparrow", "↑"), Map.entry("downarrow", "↓"), Map.entry("longrightarrow", "⟶"),
            Map.entry("longleftarrow", "⟵"), Map.entry("sum", "∑"), Map.entry("prod", "∏"), Map.entry("coprod", "∐"),
            Map.entry("int", "∫"), Map.entry("iint", "∬"), Map.entry("iiint", "∭"), Map.entry("oint", "∮"),
            Map.entry("bigcup", "⋃"), Map.entry("bigcap", "⋂"), Map.entry("bigoplus", "⨁"), Map.entry("bigotimes", "⨂"),
            Map.entry("ldots", "…"), Map.entry("dots", "…"), Map.entry("cdots", "⋯"), Map.entry("vdots", "⋮"),
            Map.entry("ddots", "⋱"), Map.entry("prime", "′"), Map.entry("degree", "°"), Map.entry("therefore", "∴"),
            Map.entry("because", "∵"), Map.entry("langle", "⟨"), Map.entry("rangle", "⟩"), Map.entry("lceil", "⌈"),
            Map.entry("rceil", "⌉"), Map.entry("lfloor", "⌊"), Map.entry("rfloor", "⌋"), Map.entry("vert", "|"),
            Map.entry("Vert", "‖"), Map.entry("lvert", "|"), Map.entry("rvert", "|"), Map.entry("lVert", "‖"),
            Map.entry("rVert", "‖"), Map.entry("backslash", "∖"), Map.entry("dagger", "†"), Map.entry("top", "⊤"),
            Map.entry("bot", "⊥"), Map.entry("vdash", "⊢"), Map.entry("models", "⊨"), Map.entry("checkmark", "✓"),
            Map.entry("{", "{"), Map.entry("}", "}"), Map.entry("$", "$"), Map.entry("%", "%"), Map.entry("&", "&amp;"),
            Map.entry("#", "#"), Map.entry("_", "_"), Map.entry("|", "‖"), Map.entry(",", " "),
            Map.entry(":", " "), Map.entry(";", " "), Map.entry("!", ""), Map.entry(" ", " "),
            Map.entry("quad", " "), Map.entry("qquad", "  "), Map.entry("enspace", " "),
            Map.entry("lbrace", "{"), Map.entry("rbrace", "}"), Map.entry("colon", ":"), Map.entry("lparen", "("),
            Map.entry("rparen", ")"), Map.entry("lbrack", "["), Map.entry("rbrack", "]"), Map.entry("lt", "&lt;"),
            Map.entry("gt", "&gt;"), Map.entry("ulcorner", "┌"), Map.entry("urcorner", "┐"), Map.entry("llcorner", "└"),
            Map.entry("lrcorner", "┘"), Map.entry("lgroup", "⟮"), Map.entry("rgroup", "⟯"), Map.entry("lmoustache", "⎰"),
            Map.entry("rmoustache", "⎱"), Map.entry("llbracket", "⟦"), Map.entry("rrbracket", "⟧"),
            Map.entry("lBrace", "⦃"), Map.entry("rBrace", "⦄"), Map.entry("Uparrow", "⇑"), Map.entry("Downarrow", "⇓"),
            Map.entry("updownarrow", "↕"), Map.entry("Updownarrow", "⇕"), Map.entry("cdotp", "·"),
            Map.entry("longmapsto", "⟼"), Map.entry("hookrightarrow", "↪"), Map.entry("nearrow", "↗"),
            Map.entry("searrow", "↘"), Map.entry("leadsto", "⇝"), Map.entry("square", "□"), Map.entry("triangle", "△"),
            Map.entry("diamond", "⋄"), Map.entry("sharp", "♯"), Map.entry("flat", "♭"), Map.entry("natural", "♮"),
            Map.entry("leqslant", "⩽"), Map.entry("geqslant", "⩾"), Map.entry("lesssim", "≲"), Map.entry("gtrsim", "≳"),
            Map.entry("nleq", "≰"), Map.entry("ngeq", "≱"), Map.entry("subsetneq", "⊊"), Map.entry("supsetneq", "⊋"),
            Map.entry("sqcup", "⊔"), Map.entry("sqcap", "⊓"), Map.entry("uplus", "⊎"), Map.entry("odot", "⊙"),
            Map.entry("ominus", "⊖"), Map.entry("oslash", "⊘"), Map.entry("amalg", "⨿"), Map.entry("wp", "℘"),
            Map.entry("mho", "℧"), Map.entry("Finv", "Ⅎ"), Map.entry("beth", "ℶ"), Map.entry("gimel", "ℷ"),
            Map.entry("digamma", "ϝ"), Map.entry("varkappa", "ϰ"), Map.entry("varpropto", "∝"), Map.entry("asymp", "≍"),
            Map.entry("doteq", "≐"), Map.entry("bowtie", "⋈"), Map.entry("smile", "⌣"), Map.entry("frown", "⌢"),
            Map.entry("dashv", "⊣"), Map.entry("Vdash", "⊩"), Map.entry("nmid", "∤"), Map.entry("nparallel", "∦"),
            Map.entry("complement", "∁"), Map.entry("surd", "√"), Map.entry("clubsuit", "♣"),
            Map.entry("diamondsuit", "♢"), Map.entry("heartsuit", "♡"), Map.entry("spadesuit", "♠"),
            Map.entry("blacksquare", "■"), Map.entry("lozenge", "◊"), Map.entry("bigstar", "★"),
            Map.entry("idotsint", "∫⋯∫"), Map.entry("intop", "∫"), Map.entry("smallint", "∫"));

    private static final Map<String, String> ACCENTS = Map.of("hat", "̂", "widehat", "̂", "bar", "̄",
            "overline", "̅", "vec", "⃗", "dot", "̇", "ddot", "̈", "tilde", "̃",
            "widetilde", "̃", "underline", "̲");

    private static final Map<Character, String> BLACKBOARD = Map.of('R', "ℝ", 'N', "ℕ", 'Z', "ℤ", 'Q', "ℚ", 'C', "ℂ",
            'P', "ℙ", 'H', "ℍ", 'E', "𝔼");

    private static final Set<String> FUNCTIONS = Set.of("sin", "cos", "tan", "cot", "sec", "csc",
            "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh", "log", "ln", "lg", "exp", "lim", "liminf", "limsup",
            "max", "min", "sup", "inf", "det", "dim", "ker", "deg", "gcd", "arg", "Pr", "hom", "mod", "bmod");

    private static final int MAX_FENCE_ROWS = 4;

    private final String s;

    private final boolean block;

    private int i;

    private long[] cells = {0};

    private TexHtml(String s, boolean block) {
        this.s = s;
        this.block = block;
    }

    static String html(String tex) {
        return html(tex, false);
    }

    static String html(String tex, boolean block) {
        if (tex.length() > MAX_LENGTH) {
            return escape(tex);
        }
        TexHtml t = new TexHtml(tex, block);
        StringBuilder out = new StringBuilder();
        t.sequence(out, 0, false);
        return block ? inline(out.toString()) : out.toString();
    }

    private static String inline(String html) {
        String open = "<table class=\"tex\">";
        if (!html.contains(open)) {
            return html;
        }
        List<String> parts = new ArrayList<>();
        int from = 0;
        int at = html.indexOf(open);
        while (at >= 0) {
            parts.add(html.substring(from, at));
            int level = 0;
            int k = at;
            while (k < html.length()) {
                if (html.startsWith("<table", k)) {
                    level++;
                } else if (html.startsWith("</table>", k) && --level == 0) {
                    k += 8;
                    break;
                }
                k++;
            }
            parts.add(html.substring(at, k));
            from = k;
            at = html.indexOf(open, from);
        }
        parts.add(html.substring(from));
        StringBuilder b = new StringBuilder("<table class=\"tex\"><tr>");
        for (String part : parts) {
            if (!part.isBlank()) {
                b.append("<td>").append(part.strip()).append("</td>");
            }
        }
        return b.append("</tr></table>").toString();
    }

    private void sequence(StringBuilder out, int depth, boolean group) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '}') {
                if (group) {
                    i++;
                    return;
                }
                i++;
                continue;
            }
            atom(out, depth);
        }
    }

    private void atom(StringBuilder out, int depth) {
        char c = s.charAt(i);
        if (depth > MAX_DEPTH) {
            out.append(escape(s.substring(i)));
            i = s.length();
            return;
        }
        switch (c) {
            case '{' -> {
                i++;
                sequence(out, depth + 1, true);
            }
            case '^', '_' -> {
                i++;
                String tag = c == '^' ? "sup" : "sub";
                out.append('<').append(tag).append('>').append(argument(depth)).append("</").append(tag).append('>');
            }
            case '\\' -> command(out, depth);
            case '&' -> {
                i++;
                out.append(" ");
            }
            case '~' -> {
                i++;
                out.append(' ');
            }
            case '\'' -> {
                i++;
                out.append('′');
            }
            case '-' -> {
                i++;
                out.append('−');
            }
            case '*' -> {
                i++;
                out.append('∗');
            }
            case '<' -> {
                i++;
                out.append(" &lt; ");
            }
            case '>' -> {
                i++;
                out.append(" &gt; ");
            }
            case ' ', '\n', '\r', '\t' -> i++;
            default -> {
                int cp = s.codePointAt(i);
                i += Character.charCount(cp);
                if (Character.isLetter(cp)) {
                    out.append("<i>").appendCodePoint(cp).append("</i>");
                } else if (cp == '=' || cp == '+') {
                    out.append(' ').appendCodePoint(cp).append(' ');
                } else {
                    out.append(escape(new String(Character.toChars(cp))));
                }
            }
        }
    }

    private String argument(int depth) {
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        StringBuilder b = new StringBuilder();
        if (i >= s.length()) {
            return "";
        }
        if (s.charAt(i) == '{') {
            i++;
            sequence(b, depth + 1, true);
        } else {
            atom(b, depth + 1);
        }
        return b.toString();
    }

    private String rawArgument() {
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        if (i >= s.length()) {
            return "";
        }
        if (s.charAt(i) != '{') {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            return new String(Character.toChars(cp));
        }
        int level = 0;
        int start = i + 1;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '{') {
                level++;
            } else if (c == '}' && --level == 0) {
                return s.substring(start, i++);
            }
        }
        return s.substring(start);
    }

    private void command(StringBuilder out, int depth) {
        i++;
        if (i >= s.length()) {
            return;
        }
        int start = i;
        if (Character.isLetter(s.charAt(i))) {
            while (i < s.length() && Character.isLetter(s.charAt(i))) {
                i++;
            }
        } else {
            i++;
        }
        String name = s.substring(start, i);
        String symbol = SYMBOLS.get(name);
        if (symbol != null) {
            boolean relation = name.length() > 1 && "≤≥≠≈≡∼≃≅∝∈∉⊂⊃⊆⊇→←↔⇒⇐⇔⟹⟺↦×⋅±∓÷∧∨".contains(symbol);
            out.append(relation ? " " + symbol + " " : symbol);
            return;
        }
        if (FUNCTIONS.contains(name)) {
            out.append(name).append(name.equals("mod") || name.equals("bmod") ? " " : " ");
            return;
        }
        String accent = ACCENTS.get(name);
        if (accent != null) {
            String body = argument(depth);
            out.append(body).append(accent);
            return;
        }
        switch (name) {
            case "frac", "dfrac", "tfrac", "cfrac" -> {
                String num = argument(depth);
                String den = argument(depth);
                out.append(wrap(num)).append('/').append(wrap(den));
            }
            case "binom" -> {
                String n = argument(depth);
                String k = argument(depth);
                out.append('(').append(n).append(" choose ").append(k).append(')');
            }
            case "sqrt" -> {
                String index = "";
                if (i < s.length() && s.charAt(i) == '[') {
                    int close = s.indexOf(']', i);
                    if (close > 0) {
                        index = "<sup>" + escape(s.substring(i + 1, close)) + "</sup>";
                        i = close + 1;
                    }
                }
                String root = index.equals("<sup>3</sup>") ? "∛" : index.equals("<sup>4</sup>") ? "∜" : index + "√";
                out.append(root).append(wrap(argument(depth)));
            }
            case "text", "textrm", "textnormal", "mathrm", "operatorname", "mbox", "textup", "mathsf", "textsf" ->
                out.append("<span class=\"up\">").append(escape(rawArgument())).append("</span>");
            case "mathbf", "textbf", "boldsymbol", "bm" -> out.append("<b>").append(argument(depth)).append("</b>");
            case "mathit", "textit", "emph" -> out.append("<i>").append(argument(depth)).append("</i>");
            case "mathtt", "texttt" -> out.append("<code>").append(escape(rawArgument())).append("</code>");
            case "mathbb" -> {
                String arg = rawArgument();
                StringBuilder b = new StringBuilder();
                for (char ch : arg.toCharArray()) {
                    String mapped = BLACKBOARD.get(ch);
                    b.append(mapped != null ? mapped : escape(String.valueOf(ch)));
                }
                out.append(b);
            }
            case "mathcal", "mathscr", "mathfrak" -> out.append(argument(depth));
            case "left", "right", "bigl", "bigr", "Bigl", "Bigr", "big", "Big", "bigg", "Bigg", "middle" -> {
                if (i < s.length() && s.charAt(i) == '.') {
                    i++;
                }
            }
            case "displaystyle", "textstyle", "scriptstyle", "limits", "nolimits", "nonumber", "notag", "label",
                    "tag" -> {
                if (name.equals("label") || name.equals("tag")) {
                    rawArgument();
                }
            }
            case "begin" -> environment(out, rawArgument(), depth);
            case "end" -> rawArgument();
            case "hline", "hdashline" -> { }
            case "\\", "newline", "cr" -> {
                skipSpacing();
                out.append(block ? "<br>" : "; ");
            }
            case "not" -> out.append('̸');
            default -> out.append(escape("\\" + name));
        }
    }

    private void skipSpacing() {
        int at = i;
        while (at < s.length() && s.charAt(at) == ' ') {
            at++;
        }
        if (at < s.length() && s.charAt(at) == '[') {
            int close = s.indexOf(']', at);
            if (close > 0 && close - at < 16) {
                i = close + 1;
            }
        }
    }

    private void environment(StringBuilder out, String env, int depth) {
        String name = env.endsWith("*") ? env.substring(0, env.length() - 1) : env;
        if (name.equals("array") || name.equals("tabular") || name.equals("alignat") || name.equals("subarray")) {
            rawArgument();
        }
        String body = body(env);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        int level = 0;
        int env0 = 0;
        int from = 0;
        for (int k = 0; k <= body.length(); k++) {
            char c = k < body.length() ? body.charAt(k) : '\0';
            boolean end = k == body.length();
            boolean cell = !end && c == '&' && level == 0 && env0 == 0;
            boolean line = !end && c == '\\' && k + 1 < body.length() && body.charAt(k + 1) == '\\' && level == 0
                    && env0 == 0;
            if (cell || line || end) {
                row.add(cell(body.substring(from, k), depth));
                from = k + 1;
                if (line || end) {
                    rows.add(row);
                    row = new ArrayList<>();
                }
                if (line) {
                    k++;
                    from = k + 1;
                    int at = from;
                    while (at < body.length() && body.charAt(at) == ' ') {
                        at++;
                    }
                    if (at < body.length() && body.charAt(at) == '[') {
                        int close = body.indexOf(']', at);
                        if (close > 0 && close - at < 16) {
                            k = close;
                            from = close + 1;
                        }
                    }
                }
                continue;
            }
            if (c == '\\') {
                if (body.startsWith("begin{", k + 1)) {
                    env0++;
                } else if (body.startsWith("end{", k + 1)) {
                    env0 = Math.max(0, env0 - 1);
                }
                k++;
            } else if (c == '{') {
                level++;
            } else if (c == '}') {
                level = Math.max(0, level - 1);
            }
        }
        while (rows.size() > 1 && rows.getLast().size() == 1 && rows.getLast().getFirst().isBlank()) {
            rows.removeLast();
        }
        String open = switch (name) {
            case "pmatrix", "psmallmatrix" -> "(";
            case "bmatrix", "bsmallmatrix" -> "[";
            case "Bmatrix", "Bsmallmatrix", "cases", "dcases" -> "{";
            case "vmatrix", "vsmallmatrix" -> "|";
            case "Vmatrix", "Vsmallmatrix" -> "‖";
            default -> "";
        };
        String close = switch (name) {
            case "pmatrix", "psmallmatrix" -> ")";
            case "bmatrix", "bsmallmatrix" -> "]";
            case "Bmatrix", "Bsmallmatrix", "rcases" -> "}";
            case "vmatrix", "vsmallmatrix" -> "|";
            case "Vmatrix", "Vsmallmatrix" -> "‖";
            default -> "";
        };
        boolean aligned = name.startsWith("align") || name.equals("split") || name.equals("eqnarray");
        boolean left = name.endsWith("cases");
        if (!block) {
            out.append(open);
            for (int r = 0; r < rows.size(); r++) {
                if (r > 0) {
                    out.append("; ");
                }
                out.append(String.join(aligned ? "" : " ", rows.get(r)));
            }
            out.append(close);
            return;
        }
        int columns = 0;
        long written = 0;
        for (List<String> r : rows) {
            columns = Math.max(columns, r.size());
            written += r.size();
        }
        long padded = (long) rows.size() * columns;
        cells[0] += padded;
        if (cells[0] > MAX_CELLS || padded > 4 * written + 64) {
            out.append(escape("\\begin{" + env + "}" + body + "\\end{" + env + "}"));
            return;
        }
        String fence = "<td class=\"fence\" rowspan=\"" + rows.size() + "\" style=\"font-size: "
                + Math.min(MAX_FENCE_ROWS, Math.max(1, rows.size())) * 1.2f + "em\">";
        out.append("<table class=\"tex\">");
        for (int r = 0; r < rows.size(); r++) {
            out.append("<tr>");
            if (r == 0 && !open.isEmpty()) {
                out.append(fence).append(open).append("</td>");
            }
            List<String> cells = rows.get(r);
            for (int c = 0; c < columns; c++) {
                String align = aligned ? c % 2 == 0 ? "right" : "left" : left ? "left" : "center";
                out.append("<td style=\"text-align: ").append(align).append("\">")
                        .append(c < cells.size() ? cells.get(c) : "").append("</td>");
            }
            if (r == 0 && !close.isEmpty()) {
                out.append(fence).append(close).append("</td>");
            }
            out.append("</tr>");
        }
        out.append("</table>");
    }

    private String cell(String tex, int depth) {
        TexHtml t = new TexHtml(tex, block);
        t.cells = cells;
        StringBuilder b = new StringBuilder();
        t.sequence(b, depth + 1, false);
        return b.toString();
    }

    private String body(String env) {
        int start = i;
        int level = 0;
        for (; i < s.length(); i++) {
            if (s.charAt(i) != '\\') {
                continue;
            }
            if (s.startsWith("begin{", i + 1)) {
                level++;
            } else if (s.startsWith("end{", i + 1)) {
                if (level == 0 && s.startsWith("end{" + env + "}", i + 1)) {
                    String inner = s.substring(start, i);
                    i += env.length() + 6;
                    return inner;
                }
                level = Math.max(0, level - 1);
            }
            i++;
        }
        return s.substring(start);
    }

    private static String wrap(String html) {
        String plain = html.replaceAll("<[^>]*>", "");
        return plain.codePointCount(0, plain.length()) <= 1 || plain.chars().allMatch(Character::isLetterOrDigit)
                ? html : "(" + html + ")";
    }

    static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
