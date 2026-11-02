package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import stirling.software.officeconvert.layout.MathToken.Kind;

final class OmmlWriter {

    private static final String BINDING = "+-−=<>≤≥≠∈→,";

    private static final Set<String> WORDS = Set.of("and", "or", "for", "if", "else", "with", "where", "otherwise",
            "then", "when", "independent", "dependent");

    private static final float SIZE = 0.9f;

    private sealed interface Node permits Text, Frac, Rad, Nary, Lim, Stack, Script {}

    private record Text(String text, boolean upright, int level) implements Node {}

    private record Frac(List<Node> num, List<Node> den, int level) implements Node {}

    private record Rad(List<Node> degree, List<Node> body, int level) implements Node {}

    private record Nary(String operator, List<Node> sub, List<Node> sup, List<Node> body, int level) implements Node {}

    private record Lim(List<Node> base, List<Node> limit, boolean below, int level) implements Node {}

    private record Stack(List<List<Node>> rows, int level) implements Node {}

    private record Script(Node base, List<Node> sub, List<Node> sup, int level) implements Node {}

    private final List<MathToken> toks;
    private final String runProps;
    private int at;

    private OmmlWriter(List<MathToken> toks, float size) {
        this.toks = toks;
        this.runProps = "<w:rPr><w:rFonts w:ascii=\"Cambria Math\" w:hAnsi=\"Cambria Math\"/><w:sz w:val=\""
                + Math.max(2, Math.round(size * 2)) + "\"/><w:szCs w:val=\"" + Math.max(2, Math.round(size * 2))
                + "\"/></w:rPr>";
    }

    static String of(List<MathToken> toks) {
        float size = 0;
        for (MathToken t : toks) {
            if (!t.marker() && t.level() == 0 && t.source() != null) {
                size = Math.max(size, t.source().size);
            }
        }
        OmmlWriter w = new OmmlWriter(toks, (size > 0 ? size : 10f) * SIZE);
        List<Node> nodes = w.sequence(EnumSet.noneOf(Kind.class));
        StringBuilder sb = new StringBuilder(256);
        w.write(sb, nodes);
        return sb.toString();
    }

    private List<Node> sequence(Set<Kind> stop) {
        List<Node> nodes = new ArrayList<>();
        while (at < toks.size()) {
            MathToken t = toks.get(at);
            if (t.marker() && stop.contains(t.kind())) {
                break;
            }
            at++;
            switch (t.kind()) {
                case FRAC_OPEN -> {
                    List<Node> num = sequence(EnumSet.of(Kind.FRAC_MID));
                    at++;
                    List<Node> den = sequence(EnumSet.of(Kind.FRAC_CLOSE));
                    at++;
                    nodes.add(new Frac(num, den, t.level()));
                }
                case ROOT_OPEN -> {
                    List<Node> degree = sequence(EnumSet.of(Kind.ROOT_MID));
                    at++;
                    List<Node> body = sequence(EnumSet.of(Kind.ROOT_CLOSE));
                    at++;
                    nodes.add(new Rad(degree, body, t.level()));
                }
                case NARY_OPEN -> {
                    String operator = at < toks.size() ? toks.get(at++).text() : "∑";
                    at++;
                    List<Node> sub = sequence(EnumSet.of(Kind.NARY_SUP));
                    at++;
                    List<Node> sup = sequence(EnumSet.of(Kind.NARY_CLOSE));
                    at++;
                    nodes.add(new Nary(operator, sub, sup, new ArrayList<>(), t.level()));
                }
                case LIM_OPEN -> {
                    List<Node> base = sequence(EnumSet.of(Kind.LIM_MID));
                    at++;
                    int limitLevel = firstLevel();
                    List<Node> limit = sequence(EnumSet.of(Kind.LIM_CLOSE));
                    at++;
                    nodes.add(new Lim(base, limit, limitLevel <= t.level(), t.level()));
                }
                case STACK_OPEN -> {
                    List<List<Node>> rows = new ArrayList<>();
                    rows.add(sequence(EnumSet.of(Kind.STACK_ROW, Kind.STACK_CLOSE)));
                    while (at < toks.size() && toks.get(at).kind() == Kind.STACK_ROW) {
                        at++;
                        rows.add(sequence(EnumSet.of(Kind.STACK_ROW, Kind.STACK_CLOSE)));
                    }
                    at++;
                    nodes.add(new Stack(rows, t.level()));
                }
                case TEXT -> {
                    if (!t.linear()) {
                        nodes.add(new Text(t.text(), upright(t), t.level()));
                    }
                }
                default -> {
                }
            }
        }
        return attach(nodes);
    }

    private int firstLevel() {
        for (int k = at; k < toks.size(); k++) {
            MathToken t = toks.get(k);
            if (!t.marker() && !t.linear() && !t.space()) {
                return t.level();
            }
        }
        return 0;
    }

    private static boolean upright(MathToken t) {
        return t.source() == null || !t.source().italic;
    }

    private static int level(Node n) {
        return switch (n) {
            case Text t -> t.level();
            case Frac f -> f.level();
            case Rad r -> r.level();
            case Nary y -> y.level();
            case Lim l -> l.level();
            case Stack s -> s.level();
            case Script s -> s.level();
        };
    }

    private static List<Node> words(List<Node> nodes) {
        List<Node> out = new ArrayList<>();
        for (Node n : nodes) {
            if (n instanceof Text t && letters(t) && !out.isEmpty() && out.getLast() instanceof Text u && letters(u)
                    && u.level() == t.level()) {
                out.set(out.size() - 1, new Text(u.text() + t.text(), true, t.level()));
                continue;
            }
            out.add(n);
        }
        return out;
    }

    private static boolean letters(Text t) {
        return t.upright() && !t.text().isEmpty() && t.text().chars().allMatch(Character::isLetter);
    }

    private static List<Node> attach(List<Node> raw) {
        List<Node> nodes = words(raw);
        int base = Integer.MAX_VALUE;
        for (Node n : nodes) {
            if (!(n instanceof Text t && t.text().isBlank())) {
                base = Math.abs(level(n)) < Math.abs(base) ? level(n) : base;
            }
        }
        if (base == Integer.MAX_VALUE) {
            return nodes;
        }
        List<Node> out = new ArrayList<>();
        int i = 0;
        while (i < nodes.size()) {
            Node n = nodes.get(i);
            if (level(n) == base || out.isEmpty() || out.getLast() instanceof Text t && t.text().isBlank()) {
                out.add(n);
                i++;
                continue;
            }
            List<Node> sub = new ArrayList<>();
            List<Node> sup = new ArrayList<>();
            while (i < nodes.size() && level(nodes.get(i)) != base) {
                Node s = nodes.get(i++);
                (scriptSide(level(s), base) < 0 ? sub : sup).add(s);
            }
            Node host = out.removeLast();
            out.add(new Script(host, attach(sub), attach(sup), base));
        }
        return gatherOperands(out);
    }

    private static int scriptSide(int level, int base) {
        if (base == 0) {
            return Integer.signum(level);
        }
        if (level == 3 * base) {
            return -Integer.signum(base);
        }
        return level < base || level == base && level < 0 ? -1 : 1;
    }

    private static List<Node> gatherOperands(List<Node> nodes) {
        List<Node> out = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            if (n instanceof Nary y) {
                int k = i + 1;
                while (k < nodes.size() && nodes.get(k) instanceof Text t && t.text().isBlank()) {
                    k++;
                }
                List<Node> body = new ArrayList<>();
                int depth = 0;
                while (k < nodes.size() && (depth > 0 || !binding(nodes.get(k)))) {
                    Node b = nodes.get(k++);
                    depth = Math.max(0, depth + brackets(b));
                    body.add(b);
                }
                y.body().addAll(gatherOperands(body));
                out.add(y);
                i = k - 1;
                continue;
            }
            out.add(n);
        }
        return out;
    }

    private static int brackets(Node n) {
        String text = n instanceof Text t ? t.text() : n instanceof Script s && s.base() instanceof Text t ? t.text() : "";
        int d = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            d += c == '(' || c == '[' ? 1 : c == ')' || c == ']' ? -1 : 0;
        }
        return d;
    }

    private static boolean binding(Node n) {
        if (!(n instanceof Text t) || t.text().isEmpty()) {
            return false;
        }
        String s = t.text().strip();
        return !s.isEmpty() && BINDING.indexOf(s.charAt(0)) >= 0 || WORDS.contains(s)
                || t.upright() && s.length() > 1 && s.chars().allMatch(Character::isLetter);
    }

    private void write(StringBuilder sb, List<Node> nodes) {
        int i = 0;
        while (i < nodes.size()) {
            Node n = nodes.get(i);
            if (n instanceof Text t) {
                StringBuilder run = new StringBuilder();
                boolean upright = uprightWord(t);
                while (i < nodes.size() && nodes.get(i) instanceof Text u && uprightWord(u) == upright) {
                    run.append(u.text());
                    i++;
                }
                words(sb, run.toString(), upright);
                continue;
            }
            node(sb, n);
            i++;
        }
    }

    private void words(StringBuilder sb, String text, boolean upright) {
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            if (!Character.isLetter(text.charAt(i))) {
                i++;
                continue;
            }
            int end = i;
            while (end < text.length() && Character.isLetter(text.charAt(end))) {
                end++;
            }
            if (WORDS.contains(text.substring(i, end))) {
                run(sb, text.substring(start, i), upright);
                plainRun(sb, text.substring(i, end));
                start = end;
            }
            i = end;
        }
        run(sb, text.substring(start), upright);
    }

    private void plainRun(StringBuilder sb, String text) {
        sb.append("<m:r><m:rPr><m:nor/></m:rPr>").append(runProps).append("<m:t xml:space=\"preserve\">");
        escape(sb, text);
        sb.append("</m:t></m:r>");
    }

    private static boolean uprightWord(Text t) {
        return t.upright() && t.text().chars().anyMatch(Character::isLetter);
    }

    private void run(StringBuilder sb, String text, boolean upright) {
        if (text.isEmpty()) {
            return;
        }
        sb.append("<m:r>");
        if (upright) {
            boolean word = text.strip().length() > 1 && text.strip().chars().allMatch(Character::isLetter);
            sb.append(word ? "<m:rPr><m:nor/></m:rPr>" : "<m:rPr><m:sty m:val=\"p\"/></m:rPr>");
        }
        sb.append(runProps).append("<m:t xml:space=\"preserve\">");
        escape(sb, text);
        sb.append("</m:t></m:r>");
    }

    private void node(StringBuilder sb, Node n) {
        switch (n) {
            case Frac f -> {
                sb.append("<m:f><m:num>");
                write(sb, f.num());
                sb.append("</m:num><m:den>");
                write(sb, f.den());
                sb.append("</m:den></m:f>");
            }
            case Rad r -> {
                sb.append("<m:rad>");
                if (r.degree().isEmpty()) {
                    sb.append("<m:radPr><m:degHide m:val=\"1\"/></m:radPr><m:deg/>");
                } else {
                    sb.append("<m:deg>");
                    write(sb, r.degree());
                    sb.append("</m:deg>");
                }
                sb.append("<m:e>");
                write(sb, r.body());
                sb.append("</m:e></m:rad>");
            }
            case Nary y -> {
                sb.append("<m:nary><m:naryPr><m:chr m:val=\"");
                escape(sb, y.operator());
                sb.append("\"/><m:limLoc m:val=\"undOvr\"/>");
                if (y.sub().isEmpty()) {
                    sb.append("<m:subHide m:val=\"1\"/>");
                }
                if (y.sup().isEmpty()) {
                    sb.append("<m:supHide m:val=\"1\"/>");
                }
                sb.append("</m:naryPr><m:sub>");
                write(sb, y.sub());
                sb.append("</m:sub><m:sup>");
                write(sb, y.sup());
                sb.append("</m:sup><m:e>");
                write(sb, y.body());
                sb.append("</m:e></m:nary>");
            }
            case Lim l -> {
                String tag = l.below() ? "m:limLow" : "m:limUpp";
                sb.append('<').append(tag).append("><m:e>");
                write(sb, l.base());
                sb.append("</m:e><m:lim>");
                write(sb, l.limit());
                sb.append("</m:lim></").append(tag).append('>');
            }
            case Stack s -> {
                sb.append("<m:eqArr>");
                for (List<Node> row : s.rows()) {
                    sb.append("<m:e>");
                    write(sb, row);
                    sb.append("</m:e>");
                }
                sb.append("</m:eqArr>");
            }
            case Script s -> {
                String tag = s.sub().isEmpty() ? "m:sSup" : s.sup().isEmpty() ? "m:sSub" : "m:sSubSup";
                sb.append('<').append(tag).append("><m:e>");
                write(sb, List.of(s.base()));
                sb.append("</m:e>");
                if (!s.sub().isEmpty()) {
                    sb.append("<m:sub>");
                    write(sb, s.sub());
                    sb.append("</m:sub>");
                }
                if (!s.sup().isEmpty()) {
                    sb.append("<m:sup>");
                    write(sb, s.sup());
                    sb.append("</m:sup>");
                }
                sb.append("</").append(tag).append('>');
            }
            case Text t -> run(sb, t.text(), uprightWord(t));
        }
    }

    private static void escape(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> {
                    if (Character.isHighSurrogate(c)) {
                        if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                            sb.append(c).append(s.charAt(++i));
                        }
                    } else if ((c >= 0x20 || c == '\t') && !Character.isLowSurrogate(c) && c != 0xFFFE && c != 0xFFFF) {
                        sb.append(c);
                    }
                }
            }
        }
    }
}
