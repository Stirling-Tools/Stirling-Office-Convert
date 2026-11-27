package stirling.software.officeconvert.topdf.markdown;

import java.util.Map;
import java.util.Set;

final class TexHtml {

    private static final int MAX_DEPTH = 40;

    private static final int MAX_LENGTH = 20_000;

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

    private final String s;

    private int i;

    private TexHtml(String s) {
        this.s = s;
    }

    static String html(String tex) {
        if (tex.length() > MAX_LENGTH) {
            return escape(tex);
        }
        TexHtml t = new TexHtml(tex);
        StringBuilder out = new StringBuilder();
        t.sequence(out, 0, false);
        return out.toString();
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
                out.append("&lt;");
            }
            case '>' -> {
                i++;
                out.append("&gt;");
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
                out.append(index).append('√').append(wrap(argument(depth)));
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
            case "begin" -> {
                String env = rawArgument();
                if (env.equals("array") || env.equals("tabular")) {
                    rawArgument();
                }
                out.append(env.contains("matrix") && !env.equals("matrix") && !env.equals("smallmatrix")
                        ? env.startsWith("b") ? "[" : env.startsWith("v") ? "|" : env.startsWith("B") ? "{" : "(" : "");
            }
            case "end" -> {
                String env = rawArgument();
                out.append(env.contains("matrix") && !env.equals("matrix") && !env.equals("smallmatrix")
                        ? env.startsWith("b") ? "]" : env.startsWith("v") ? "|" : env.startsWith("B") ? "}" : ")" : "");
            }
            case "\\", "newline", "cr" -> out.append("<br>");
            case "not" -> out.append('̸');
            default -> out.append(escape("\\" + name));
        }
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
