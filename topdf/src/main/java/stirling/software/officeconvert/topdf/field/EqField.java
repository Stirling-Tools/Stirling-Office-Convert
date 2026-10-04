package stirling.software.officeconvert.topdf.field;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class EqField {

    public static final String M_NS = "http://schemas.openxmlformats.org/officeDocument/2006/math";

    public static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private static final int MAX_DEPTH = 24;

    private static final int MAX_LENGTH = 8192;

    private static final Set<String> ELEMENTS = Set.of("a", "b", "d", "f", "i", "l", "o", "r", "s", "x");

    private static final Set<String> CHAR_OPTIONS = Set.of("lc", "rc", "bc", "fc", "vc");

    private static final Set<String> NUMBER_OPTIONS = Set.of("up", "do", "ai", "di", "co", "vs", "hs", "fo", "ba");

    private static final Set<String> FLAG_OPTIONS = Set.of("al", "ac", "ar", "ad", "in", "su", "pr", "to", "bo", "le",
            "ri", "li");

    private record Element(String name, List<String[]> options, List<List<Object>> args) {

        String option(String key) {
            for (String[] o : options) {
                if (o[0].equals(key)) {
                    return o[1];
                }
            }
            return null;
        }
    }

    private final String s;

    private final String rPr;

    private int i;

    private int depth;

    private EqField(String s, String rPr) {
        this.s = s;
        this.rPr = rPr == null ? "" : rPr;
    }

    public static boolean isEq(String instruction) {
        String t = instruction == null ? "" : instruction.stripLeading();
        return t.length() >= 2 && t.substring(0, 2).equalsIgnoreCase("EQ")
                && (t.length() == 2 || Character.isWhitespace(t.charAt(2)) || t.charAt(2) == '\\');
    }

    public static String omml(String instruction, String rPr) {
        if (!isEq(instruction) || instruction.length() > MAX_LENGTH) {
            return null;
        }
        String body = instruction.stripLeading().substring(2).strip();
        EqField p = new EqField(body, rPr);
        List<Object> nodes = p.sequence(false);
        StringBuilder b = new StringBuilder();
        p.write(nodes, b);
        if (b.isEmpty()) {
            return null;
        }
        return "<m:oMath xmlns:m=\"" + M_NS + "\" xmlns:w=\"" + W_NS + "\">" + b + "</m:oMath>";
    }

    private List<Object> sequence(boolean inArgs) {
        List<Object> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int literal = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (inArgs && literal == 0 && (c == ',' || c == ';' || c == ')')) {
                break;
            }
            if (inArgs && (c == '(' || c == ')')) {
                literal += c == '(' ? 1 : -1;
                text.append(c);
                i++;
                continue;
            }
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (Character.isLetter(n)) {
                    int end = i + 1;
                    while (end < s.length() && Character.isLetter(s.charAt(end))) {
                        end++;
                    }
                    String word = s.substring(i + 1, end).toLowerCase(Locale.ROOT);
                    if (ELEMENTS.contains(word)) {
                        flushText(text, out);
                        i = end;
                        out.add(element(word));
                        continue;
                    }
                    i = end;
                    continue;
                }
                if (n == '*') {
                    i = skipFormatSwitch(i + 2);
                    continue;
                }
                text.append(n);
                i += 2;
                continue;
            }
            text.append(c);
            i++;
        }
        flushText(text, out);
        return out;
    }

    private int skipFormatSwitch(int at) {
        int j = at;
        while (j < s.length() && Character.isWhitespace(s.charAt(j))) {
            j++;
        }
        if (j < s.length() && s.charAt(j) == '"') {
            int end = s.indexOf('"', j + 1);
            return end < 0 ? s.length() : end + 1;
        }
        while (j < s.length() && !Character.isWhitespace(s.charAt(j)) && s.charAt(j) != '\\') {
            j++;
        }
        return j;
    }

    private static void flushText(StringBuilder text, List<Object> out) {
        if (!text.isEmpty()) {
            out.add(text.toString());
            text.setLength(0);
        }
    }

    private Element element(String name) {
        List<String[]> options = new ArrayList<>();
        while (i + 1 < s.length() && s.charAt(i) == '\\' && Character.isLetter(s.charAt(i + 1))) {
            int end = i + 1;
            while (end < s.length() && Character.isLetter(s.charAt(end)) && end - i <= 2) {
                end++;
            }
            String word = s.substring(i + 1, end).toLowerCase(Locale.ROOT);
            if (CHAR_OPTIONS.contains(word)) {
                i = end;
                String ch = "";
                if (i + 1 < s.length() && s.charAt(i) == '\\') {
                    ch = String.valueOf(s.charAt(i + 1));
                    i += 2;
                } else if (i < s.length()) {
                    ch = String.valueOf(s.charAt(i));
                    i++;
                }
                options.add(new String[] {word, ch});
            } else if (NUMBER_OPTIONS.contains(word)) {
                i = end;
                while (i < s.length() && s.charAt(i) == ' ') {
                    i++;
                }
                int start = i;
                while (i < s.length() && Character.isDigit(s.charAt(i)) && i - start < 4) {
                    i++;
                }
                options.add(new String[] {word, s.substring(start, i)});
            } else if (FLAG_OPTIONS.contains(word)) {
                i = end;
                options.add(new String[] {word, ""});
            } else {
                break;
            }
        }
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        List<List<Object>> args = new ArrayList<>();
        if (i < s.length() && s.charAt(i) == '(') {
            i++;
            if (++depth > MAX_DEPTH) {
                depth--;
                int close = s.indexOf(')', i);
                i = close < 0 ? s.length() : close + 1;
                return new Element(name, options, args);
            }
            try {
                while (i < s.length()) {
                    args.add(sequence(true));
                    if (i >= s.length()) {
                        break;
                    }
                    char c = s.charAt(i++);
                    if (c == ')') {
                        break;
                    }
                }
            } finally {
                depth--;
            }
        }
        return new Element(name, options, args);
    }

    private void write(List<Object> nodes, StringBuilder b) {
        for (Object n : nodes) {
            if (n instanceof String t) {
                run(t, b);
            } else if (n instanceof Element e) {
                element(e, b);
            }
        }
    }

    private void run(String t, StringBuilder b) {
        if (t.isEmpty()) {
            return;
        }
        b.append("<w:r>").append(rPr).append("<w:t xml:space=\"preserve\">");
        escape(t, b);
        b.append("</w:t></w:r>");
    }

    private List<Object> arg(Element e, int n) {
        return n < e.args().size() ? e.args().get(n) : List.of();
    }

    private void wrap(String tag, List<Object> content, StringBuilder b) {
        b.append("<m:").append(tag).append('>');
        write(content, b);
        b.append("</m:").append(tag).append('>');
    }

    private void element(Element e, StringBuilder b) {
        switch (e.name()) {
            case "f" -> {
                b.append("<m:f>");
                wrap("num", arg(e, 0), b);
                wrap("den", arg(e, 1), b);
                b.append("</m:f>");
            }
            case "r" -> {
                boolean degree = e.args().size() > 1;
                b.append("<m:rad>").append(degree ? "" : "<m:radPr><m:degHide m:val=\"on\"/></m:radPr>");
                wrap("deg", degree ? arg(e, 0) : List.of(), b);
                wrap("e", arg(e, degree ? 1 : 0), b);
                b.append("</m:rad>");
            }
            case "s" -> script(e, b);
            case "o" -> overstrike(e, b);
            case "b" -> bracket(e, b);
            case "l" -> list(e, b);
            case "a" -> array(e, b);
            case "i" -> integral(e, b);
            case "x" -> box(e, b);
            default -> {
            }
        }
    }

    private void script(Element e, StringBuilder b) {
        String tag = e.option("up") != null ? "sSup" : e.option("do") != null ? "sSub" : null;
        if (tag != null) {
            String part = tag.equals("sSup") ? "sup" : "sub";
            b.append("<m:").append(tag).append("><m:e/>");
            b.append("<m:").append(part).append('>');
            for (int n = 0; n < e.args().size(); n++) {
                write(arg(e, n), b);
            }
            b.append("</m:").append(part).append("></m:").append(tag).append('>');
            return;
        }
        if (e.args().size() > 1) {
            b.append("<m:eqArr>");
            for (List<Object> a : e.args()) {
                wrap("e", a, b);
            }
            b.append("</m:eqArr>");
            return;
        }
        write(arg(e, 0), b);
    }

    private static Element raised(List<Object> arg) {
        if (arg.size() == 1 && arg.get(0) instanceof Element e && e.name().equals("s") && e.option("up") != null) {
            return e;
        }
        return null;
    }

    private void overstrike(Element e, StringBuilder b) {
        List<List<Object>> args = e.args();
        if (args.size() == 2 && (raised(args.get(0)) != null || raised(args.get(1)) != null)) {
            Element top = raised(args.get(0)) != null ? raised(args.get(0)) : raised(args.get(1));
            List<Object> base = raised(args.get(0)) != null ? args.get(1) : args.get(0);
            b.append("<m:limUpp>");
            wrap("e", base, b);
            b.append("<m:lim>");
            for (List<Object> a : top.args()) {
                write(a, b);
            }
            b.append("</m:lim></m:limUpp>");
            return;
        }
        for (int n = 0; n < args.size(); n++) {
            if (n + 1 < args.size()) {
                b.append("<m:phant><m:phantPr><m:zeroWid m:val=\"on\"/></m:phantPr>");
                wrap("e", args.get(n), b);
                b.append("</m:phant>");
            } else {
                write(args.get(n), b);
            }
        }
    }

    private void bracket(Element e, StringBuilder b) {
        String both = e.option("bc");
        String left = e.option("lc") != null ? e.option("lc") : both != null ? both : "(";
        String right = e.option("rc") != null ? e.option("rc") : both != null ? mirror(both) : ")";
        b.append("<m:d><m:dPr><m:begChr m:val=\"");
        escape(left, b);
        b.append("\"/><m:endChr m:val=\"");
        escape(right, b);
        b.append("\"/></m:dPr>");
        wrap("e", arg(e, 0), b);
        b.append("</m:d>");
    }

    private static String mirror(String c) {
        return switch (c) {
            case "(" -> ")";
            case "[" -> "]";
            case "{" -> "}";
            case "<" -> ">";
            default -> c;
        };
    }

    private void list(Element e, StringBuilder b) {
        for (int n = 0; n < e.args().size(); n++) {
            if (n > 0) {
                run(",", b);
            }
            write(arg(e, n), b);
        }
    }

    private void array(Element e, StringBuilder b) {
        int columns = 1;
        try {
            String co = e.option("co");
            columns = co == null || co.isEmpty() ? 1 : Math.max(1, Math.min(64, Integer.parseInt(co)));
        } catch (NumberFormatException ignored) {
            columns = 1;
        }
        String jc = e.option("al") != null ? "left" : e.option("ar") != null ? "right" : "center";
        b.append("<m:m><m:mPr><m:mcs><m:mc><m:mcPr><m:count m:val=\"").append(columns)
                .append("\"/><m:mcJc m:val=\"").append(jc).append("\"/></m:mcPr></m:mc></m:mcs></m:mPr>");
        List<List<Object>> args = e.args();
        for (int start = 0; start < args.size(); start += columns) {
            b.append("<m:mr>");
            for (int c = 0; c < columns; c++) {
                wrap("e", start + c < args.size() ? args.get(start + c) : List.of(), b);
            }
            b.append("</m:mr>");
        }
        b.append("</m:m>");
    }

    private void integral(Element e, StringBuilder b) {
        String chr = e.option("su") != null ? "∑" : e.option("pr") != null ? "∏" : "∫";
        String custom = e.option("fc") != null ? e.option("fc") : e.option("vc");
        if (custom != null && !custom.isEmpty()) {
            chr = custom;
        }
        b.append("<m:nary><m:naryPr><m:chr m:val=\"");
        escape(chr, b);
        b.append("\"/><m:limLoc m:val=\"").append(e.option("in") != null ? "subSup" : "undOvr")
                .append("\"/></m:naryPr>");
        wrap("sub", arg(e, 0), b);
        wrap("sup", arg(e, 1), b);
        wrap("e", arg(e, 2), b);
        b.append("</m:nary>");
    }

    private void box(Element e, StringBuilder b) {
        boolean sides = e.option("to") != null || e.option("bo") != null || e.option("le") != null
                || e.option("ri") != null;
        b.append("<m:borderBox>");
        if (sides) {
            b.append("<m:borderBoxPr>");
            hide(b, "hideTop", e.option("to") == null);
            hide(b, "hideBot", e.option("bo") == null);
            hide(b, "hideLeft", e.option("le") == null);
            hide(b, "hideRight", e.option("ri") == null);
            b.append("</m:borderBoxPr>");
        }
        wrap("e", arg(e, 0), b);
        b.append("</m:borderBox>");
    }

    private static void hide(StringBuilder b, String tag, boolean hidden) {
        if (hidden) {
            b.append("<m:").append(tag).append(" m:val=\"on\"/>");
        }
    }

    private static void escape(String t, StringBuilder b) {
        for (int k = 0; k < t.length(); k++) {
            char c = t.charAt(k);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> {
                    boolean legal = c == '\t' || c >= 0x20 && c != 0xFFFE && c != 0xFFFF;
                    if (legal && (!Character.isSurrogate(c) || pairedAt(t, k))) {
                        b.append(c);
                    }
                }
            }
        }
    }

    private static boolean pairedAt(String t, int k) {
        char c = t.charAt(k);
        if (Character.isHighSurrogate(c)) {
            return k + 1 < t.length() && Character.isLowSurrogate(t.charAt(k + 1));
        }
        return k > 0 && Character.isHighSurrogate(t.charAt(k - 1));
    }
}
