package stirling.software.officeconvert.topdf.html;

import java.util.ArrayList;
import java.util.List;

/** calc(), min(), max() and clamp() over lengths, in points. Null for anything it cannot work out. */
final class Calc {

    private static final int MAX_CHARS = 400;

    private static final int MAX_DEPTH = 16;

    private static final String[] FUNCTIONS = {"calc(", "min(", "max(", "clamp("};

    private final String s;
    private final float em;
    private final float percent;
    private int i;

    private Calc(String s, float em, float percent) {
        this.s = s;
        this.em = em;
        this.percent = percent;
    }

    static Float eval(String expr, float em, float percent) {
        if (expr.length() > MAX_CHARS) {
            return null;
        }
        try {
            Calc c = new Calc(expr, em, percent);
            Float v = c.sum(0);
            c.space();
            return v != null && c.i == expr.length() && Float.isFinite(v) ? v : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Float sum(int depth) {
        Float v = product(depth);
        while (v != null) {
            space();
            if (!peek('+') && !peek('-')) {
                return v;
            }
            char op = s.charAt(i++);
            Float r = product(depth);
            if (r == null) {
                return null;
            }
            v = op == '+' ? v + r : v - r;
        }
        return null;
    }

    private Float product(int depth) {
        Float v = term(depth);
        while (v != null) {
            space();
            if (!peek('*') && !peek('/')) {
                return v;
            }
            char op = s.charAt(i++);
            Float r = term(depth);
            if (r == null || op == '/' && r == 0) {
                return null;
            }
            v = op == '*' ? v * r : v / r;
        }
        return null;
    }

    private Float term(int depth) {
        if (depth > MAX_DEPTH) {
            return null;
        }
        space();
        for (String fn : FUNCTIONS) {
            if (s.startsWith(fn, i)) {
                i += fn.length();
                return function(fn, depth);
            }
        }
        if (peek('(')) {
            i++;
            Float v = sum(depth + 1);
            space();
            if (v == null || !peek(')')) {
                return null;
            }
            i++;
            return v;
        }
        int start = i;
        if (peek('-') || peek('+')) {
            i++;
        }
        while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == '%')) {
            i++;
        }
        String token = s.substring(start, i);
        if (token.isEmpty()) {
            return null;
        }
        if (token.matches("[+-]?[0-9]*\\.?[0-9]+")) {
            return Float.parseFloat(token);
        }
        return Css.length(token, em, percent);
    }

    private Float function(String fn, int depth) {
        List<Float> args = new ArrayList<>();
        while (true) {
            Float a = sum(depth + 1);
            if (a == null) {
                return null;
            }
            args.add(a);
            space();
            if (!peek(',')) {
                break;
            }
            i++;
        }
        if (!peek(')')) {
            return null;
        }
        i++;
        return switch (fn) {
            case "calc(" -> args.size() == 1 ? args.get(0) : null;
            case "min(" -> args.stream().min(Float::compare).orElse(null);
            case "max(" -> args.stream().max(Float::compare).orElse(null);
            default -> args.size() == 3 ? Math.max(args.get(0), Math.min(args.get(1), args.get(2))) : null;
        };
    }

    private boolean peek(char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    private void space() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }
}
