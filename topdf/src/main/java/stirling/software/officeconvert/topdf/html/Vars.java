package stirling.software.officeconvert.topdf.html;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** CSS custom properties: inherited down the tree and substituted into var() before a value is read. */
final class Vars {

    // A var() that expands into more var()s is cut off, so a hostile sheet cannot blow a value up
    private static final int MAX_DEPTH = 8;
    private static final int MAX_CHARS = 2_000;
    private static final int MAX_SUBSTITUTIONS = 2_000;
    static final long MAX_STEPS = 50_000_000L;

    /** The custom properties an element sees: its own over its parent's, without copying the parent's. Every scope
     * grown from one {@link #root()} draws on one budget of {@link #MAX_STEPS} ancestor steps, so a deep page that
     * looks up many names cannot multiply the walk; once it is spent, every var() is invalid. */
    record Scope(Scope parent, Map<String, String> own, long[] steps) {

        static Scope root() {
            return new Scope(null, Map.of(), new long[] {MAX_STEPS});
        }

        String get(String name) {
            for (Scope s = this; s != null && --steps[0] >= 0; s = s.parent) {
                String v = s.own.get(name);
                if (v != null) {
                    return v;
                }
            }
            return null;
        }

        boolean spent() {
            return steps[0] < 0;
        }
    }

    private Vars() {}

    static Scope scope(Scope parent, Map<String, String> declared) {
        Map<String, String> own = null;
        for (Map.Entry<String, String> e : declared.entrySet()) {
            if (e.getKey().startsWith("--")) {
                if (own == null) {
                    own = new HashMap<>();
                }
                own.put(e.getKey(), e.getValue());
            }
        }
        return own == null ? parent : new Scope(parent, own, parent.steps);
    }

    /** Replaces var() in every declared value, in place. */
    static void resolve(Map<String, String> declared, Scope vars) {
        Iterator<Map.Entry<String, String>> it = declared.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> e = it.next();
            String v = e.getValue();
            if (!e.getKey().startsWith("--") && v.contains("var(")) {
                String r = substitute(v, vars, 0, new int[] {MAX_SUBSTITUTIONS});
                if (r == null) {
                    // invalid at computed-value time: as if never declared
                    it.remove();
                } else {
                    e.setValue(r);
                }
            }
        }
    }

    private static String substitute(String value, Scope vars, int depth, int[] budget) {
        if (depth > MAX_DEPTH || value.length() > MAX_CHARS) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < value.length()) {
            int at = value.indexOf("var(", i);
            if (at < 0) {
                sb.append(value, i, value.length());
                break;
            }
            sb.append(value, i, at);
            int close = close(value, at + 3);
            if (close < 0) {
                return null;
            }
            String inner = value.substring(at + 4, close);
            int comma = topLevelComma(inner);
            String name = (comma < 0 ? inner : inner.substring(0, comma)).strip();
            String replacement = vars.get(name);
            if (vars.spent()) {
                return null;
            }
            if (replacement == null && comma >= 0) {
                replacement = inner.substring(comma + 1).strip();
            }
            // every var() costs, even one that expands to nothing, so fan-out cannot multiply the work
            if (replacement == null || --budget[0] < 0) {
                return null;
            }
            String expanded = substitute(replacement, vars, depth + 1, budget);
            if (expanded == null) {
                return null;
            }
            sb.append(expanded);
            if (sb.length() > MAX_CHARS) {
                return null;
            }
            i = close + 1;
        }
        return sb.toString();
    }

    private static int close(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int topLevelComma(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
