package stirling.software.officeconvert.optimize;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ContentRewrite {

    private static final int MAX_OPS = 1 << 22;

    private static final int MAX_DECIMALS = 6;

    private static final Set<String> TEXT_STATE = Set.of("Tc", "Tw", "Tz", "TL", "Tf", "Tr", "Ts");

    private static final Set<String> FILL = Set.of("g", "rg", "k");

    private static final Set<String> STROKE = Set.of("G", "RG", "K");

    private static final Set<String> POSITIONING = Set.of("Tm", "Td", "TD", "T*");

    private static final Set<String> SHOWING = Set.of("Tj", "TJ", "'", "\"");

    private static final Map<String, String> DEFAULTS = Map.of("Tc", "0", "Tw", "0", "Tz", "100", "TL", "0",
            "Tr", "0", "Ts", "0");

    private static final class Op {
        final String name;
        final int start;
        final int end;
        final List<String> operands;
        byte[] replacement;
        boolean removed;

        Op(String name, int start, int end, List<String> operands) {
            this.name = name;
            this.start = start;
            this.end = end;
            this.operands = operands;
        }

        boolean is(String n) {
            return !removed && name.equals(n);
        }
    }

    private ContentRewrite() {}

    static byte[] rewrite(byte[] in, boolean pageStart) {
        List<Op> ops = parse(in);
        if (ops == null) {
            return null;
        }
        collapseNestedSaves(ops);
        mergeSiblingGroups(ops, clippingShows(ops, pageStart));
        dropRedundantState(ops, pageStart);
        joinTextObjects(ops, pageStart);
        relativeMoves(ops, pageStart);
        boolean changed = false;
        for (Op op : ops) {
            changed |= op.removed || op.replacement != null;
        }
        if (!changed) {
            return null;
        }
        Bytes out = new Bytes(in.length);
        for (Op op : ops) {
            if (op.removed) {
                continue;
            }
            if (out.size() > 0) {
                out.write('\n');
            }
            if (op.replacement != null) {
                out.write(op.replacement, 0, op.replacement.length);
            } else {
                out.write(in, op.start, op.end - op.start);
            }
        }
        out.write('\n');
        return out.toByteArray();
    }

    private static List<Op> parse(byte[] in) {
        List<Op> ops = new ArrayList<>();
        List<String> operands = new ArrayList<>();
        int start = -1;
        int depth = 0;
        try {
            ContentLexer lex = new ContentLexer(in);
            while (lex.next()) {
                ContentLexer.Kind kind = lex.kind();
                if (start < 0) {
                    start = lex.start();
                }
                switch (kind) {
                    case OPEN_ARRAY, OPEN_DICT -> {
                        depth++;
                        continue;
                    }
                    case CLOSE_ARRAY, CLOSE_DICT -> {
                        if (--depth < 0) {
                            return null;
                        }
                        if (depth == 0) {
                            operands.add(null);
                        }
                        continue;
                    }
                    case OPERATOR -> {
                    }
                    default -> {
                        if (depth == 0) {
                            operands.add(kind == ContentLexer.Kind.NUMBER || kind == ContentLexer.Kind.NAME
                                    ? lex.text() : null);
                        }
                        continue;
                    }
                }
                if (depth != 0) {
                    return null;
                }
                String name = lex.text();
                if ("BI".equals(name) || "ID".equals(name) || "EI".equals(name)) {
                    return null;
                }
                if (ops.size() >= MAX_OPS) {
                    return null;
                }
                ops.add(new Op(name, start, lex.end(), operands.isEmpty() ? List.of() : new ArrayList<>(operands)));
                operands.clear();
                start = -1;
            }
        } catch (ContentLexer.Malformed | RuntimeException e) {
            return null;
        }
        return operands.isEmpty() && depth == 0 ? ops : null;
    }

    private static int[] matches(List<Op> ops) {
        int[] match = new int[ops.size()];
        Arrays.fill(match, -1);
        Deque<Integer> open = new ArrayDeque<>();
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            if (op.is("q")) {
                open.push(i);
            } else if (op.is("Q") && !open.isEmpty()) {
                int q = open.pop();
                match[q] = i;
                match[i] = q;
            }
        }
        return match;
    }

    private static List<Integer> live(List<Op> ops) {
        List<Integer> live = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            if (!ops.get(i).removed) {
                live.add(i);
            }
        }
        return live;
    }

    private static void collapseNestedSaves(List<Op> ops) {
        boolean changed = true;
        while (changed) {
            changed = false;
            int[] match = matches(ops);
            for (int outer = 0; outer < ops.size(); outer++) {
                if (!ops.get(outer).is("q") || match[outer] < 0) {
                    continue;
                }
                int inner = nextLive(ops, outer);
                if (inner < 0 || !ops.get(inner).is("q") || match[inner] < 0) {
                    continue;
                }
                if (nextLive(ops, match[inner]) == match[outer]) {
                    ops.get(inner).removed = true;
                    ops.get(match[inner]).removed = true;
                    changed = true;
                }
            }
        }
    }

    private static int nextLive(List<Op> ops, int i) {
        for (int k = i + 1; k < ops.size(); k++) {
            if (!ops.get(k).removed) {
                return k;
            }
        }
        return -1;
    }

    private static boolean[] clippingShows(List<Op> ops, boolean pageStart) {
        boolean[] clipping = new boolean[ops.size()];
        String mode = pageStart ? "0" : null;
        Deque<String> saved = new ArrayDeque<>();
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            if (op.removed) {
                continue;
            }
            switch (op.name) {
                case "q" -> saved.push(mode == null ? "" : mode);
                case "Q" -> {
                    String m = saved.isEmpty() ? "" : saved.pop();
                    mode = m.isEmpty() ? null : m;
                }
                case "Tr" -> mode = op.operands.size() == 1 ? canonical(op.operands.get(0)) : null;
                default -> clipping[i] = SHOWING.contains(op.name) && !unclipped(mode);
            }
        }
        return clipping;
    }

    private static void mergeSiblingGroups(List<Op> ops, boolean[] clipping) {
        int[] match = matches(ops);
        List<Integer> live = live(ops);
        Map<Integer, Integer> position = new HashMap<>();
        for (int k = 0; k < live.size(); k++) {
            position.put(live.get(k), k);
        }
        for (int k = 0; k < live.size(); k++) {
            int first = live.get(k);
            if (!ops.get(first).is("q") || match[first] < 0) {
                continue;
            }
            Set<String> left = footprint(ops, live, k, position.get(match[first]), match, clipping);
            if (left == null) {
                continue;
            }
            int end = match[first];
            while (left != null) {
                int nextPos = position.get(end) + 1;
                if (nextPos >= live.size()) {
                    break;
                }
                int next = live.get(nextPos);
                if (!ops.get(next).is("q") || match[next] < 0) {
                    break;
                }
                int nextEnd = match[next];
                Set<String> covered = new HashSet<>();
                Set<String> set = footprint(ops, live, nextPos, position.get(nextEnd), match, covered, clipping);
                if (set == null || !covered.containsAll(left)) {
                    break;
                }
                ops.get(end).removed = true;
                ops.get(next).removed = true;
                left = new HashSet<>(left);
                left.addAll(set);
                end = nextEnd;
            }
            k = position.get(end);
        }
    }

    private static Set<String> footprint(List<Op> ops, List<Integer> live, int from, int to, int[] match,
            boolean[] clipping) {
        return footprint(ops, live, from, to, match, new HashSet<>(), clipping);
    }

    private static Set<String> footprint(List<Op> ops, List<Integer> live, int from, int to, int[] match,
            Set<String> coveredBeforeUse, boolean[] clipping) {
        Set<String> set = new HashSet<>();
        boolean used = false;
        for (int k = from + 1; k < to; k++) {
            Op op = ops.get(live.get(k));
            if (op.removed) {
                continue;
            }
            if (clipping[live.get(k)]) {
                return null;
            }
            String key = stateKey(op.name);
            if (key != null) {
                set.add(key);
                if (!used) {
                    coveredBeforeUse.add(key);
                }
            } else if (SHOWING.contains(op.name) || "T*".equals(op.name)) {
                used = true;
                if ("\"".equals(op.name)) {
                    set.add("Tw");
                    set.add("Tc");
                }
            } else if ("TD".equals(op.name)) {
                set.add("TL");
                if (!used) {
                    coveredBeforeUse.add("TL");
                }
            } else if (!POSITIONING.contains(op.name) && !"BT".equals(op.name) && !"ET".equals(op.name)) {
                return null;
            }
        }
        return set;
    }

    private static String stateKey(String op) {
        if (TEXT_STATE.contains(op)) {
            return op;
        }
        if (FILL.contains(op)) {
            return "fill";
        }
        return STROKE.contains(op) ? "stroke" : null;
    }

    private static void dropRedundantState(List<Op> ops, boolean pageStart) {
        Map<String, String> state = new HashMap<>(pageStart ? DEFAULTS : Map.of());
        if (pageStart) {
            state.put("fill", "g 0");
            state.put("stroke", "G 0");
        }
        Deque<Map<String, String>> saved = new ArrayDeque<>();
        for (Op op : ops) {
            if (op.removed) {
                continue;
            }
            switch (op.name) {
                case "q" -> saved.push(new HashMap<>(state));
                case "Q" -> state = saved.isEmpty() ? new HashMap<>() : saved.pop();
                case "gs" -> state.remove("Tf");
                case "cs", "sc", "scn" -> state.remove("fill");
                case "CS", "SC", "SCN" -> state.remove("stroke");
                case "\"" -> {
                    state.put("Tw", op.operands.size() > 0 ? op.operands.get(0) : null);
                    state.put("Tc", op.operands.size() > 1 ? op.operands.get(1) : null);
                }
                case "TD" -> state.put("TL", negated(op.operands.size() > 1 ? op.operands.get(1) : null));
                default -> {
                    String key = stateKey(op.name);
                    if (key == null) {
                        continue;
                    }
                    String value = value(op);
                    if (value != null && value.equals(state.get(key))) {
                        op.removed = true;
                    } else {
                        state.put(key, value);
                    }
                }
            }
        }
    }

    private static String value(Op op) {
        StringBuilder b = new StringBuilder(op.name);
        for (String operand : op.operands) {
            if (operand == null) {
                return null;
            }
            b.append(' ').append(TEXT_STATE.contains(op.name) && !"Tf".equals(op.name) ? canonical(operand)
                    : operand);
        }
        return "fill".equals(stateKey(op.name)) || "stroke".equals(stateKey(op.name)) ? b.toString()
                : b.substring(op.name.length()).strip();
    }

    private static String canonical(String number) {
        BigDecimal d = decimal(number);
        return d == null ? number : d.stripTrailingZeros().toPlainString();
    }

    private static String negated(String number) {
        BigDecimal d = decimal(number);
        return d == null ? null : d.negate().stripTrailingZeros().toPlainString();
    }

    private static void joinTextObjects(List<Op> ops, boolean pageStart) {
        if (!pageStart) {
            return;
        }
        boolean[] clipping = clippingShows(ops, true);
        boolean clipped = false;
        Op lastEnd = null;
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            if (op.removed) {
                continue;
            }
            if ("gs".equals(op.name)) {
                return;
            }
            clipped |= clipping[i];
            if (op.is("ET")) {
                lastEnd = clipped ? null : op;
                clipped = false;
                continue;
            }
            if (op.is("BT") && lastEnd != null && placedBeforeUse(ops, i)) {
                lastEnd.removed = true;
                op.removed = true;
            }
            lastEnd = null;
        }
    }

    private static boolean unclipped(String mode) {
        return "0".equals(mode) || "1".equals(mode) || "2".equals(mode) || "3".equals(mode);
    }

    private static boolean placedBeforeUse(List<Op> ops, int bt) {
        for (int i = bt + 1; i < ops.size(); i++) {
            Op op = ops.get(i);
            if (op.removed) {
                continue;
            }
            if ("Tm".equals(op.name)) {
                return true;
            }
            if (stateKey(op.name) == null) {
                return false;
            }
        }
        return false;
    }

    private static void relativeMoves(List<Op> ops, boolean pageStart) {
        BigDecimal[] matrix = null;
        BigDecimal leading = pageStart ? BigDecimal.ZERO : null;
        Deque<BigDecimal> savedLeading = new ArrayDeque<>();
        for (Op op : ops) {
            if (op.removed) {
                continue;
            }
            switch (op.name) {
                case "BT" -> matrix = identity();
                case "ET" -> matrix = null;
                case "q" -> savedLeading.push(leading == null ? BigDecimal.valueOf(Long.MIN_VALUE) : leading);
                case "Q" -> {
                    BigDecimal l = savedLeading.isEmpty() ? null : savedLeading.pop();
                    leading = l == null || l.equals(BigDecimal.valueOf(Long.MIN_VALUE)) ? null : l;
                }
                case "TL" -> leading = op.operands.size() == 1 ? decimal(op.operands.get(0)) : null;
                case "Tm" -> {
                    BigDecimal[] next = numbers(op.operands, 6);
                    if (next != null && matrix != null) {
                        byte[] move = move(matrix, next);
                        if (move != null) {
                            op.replacement = move;
                        }
                    }
                    matrix = next;
                }
                case "Td", "TD" -> {
                    BigDecimal[] t = numbers(op.operands, 2);
                    matrix = t == null || matrix == null ? null : translate(matrix, t[0], t[1]);
                    if ("TD".equals(op.name)) {
                        leading = t == null ? null : t[1].negate();
                    }
                }
                case "T*", "'", "\"" -> matrix = matrix == null || leading == null ? null
                        : translate(matrix, BigDecimal.ZERO, leading.negate());
                default -> {
                }
            }
        }
    }

    private static byte[] move(BigDecimal[] from, BigDecimal[] to) {
        for (int i = 0; i < 4; i++) {
            if (from[i].compareTo(to[i]) != 0) {
                return null;
            }
        }
        if (from[1].signum() != 0 || from[2].signum() != 0 || from[0].signum() == 0 || from[3].signum() == 0) {
            return null;
        }
        BigDecimal tx = exactQuotient(to[4].subtract(from[4]), from[0]);
        BigDecimal ty = exactQuotient(to[5].subtract(from[5]), from[3]);
        if (tx == null || ty == null) {
            return null;
        }
        return (format(tx) + " " + format(ty) + " Td").getBytes(StandardCharsets.US_ASCII);
    }

    private static BigDecimal exactQuotient(BigDecimal a, BigDecimal b) {
        try {
            BigDecimal q = a.divide(b);
            return q.stripTrailingZeros().scale() > MAX_DECIMALS ? null : q;
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private static BigDecimal[] translate(BigDecimal[] m, BigDecimal tx, BigDecimal ty) {
        BigDecimal[] r = m.clone();
        r[4] = tx.multiply(m[0]).add(ty.multiply(m[2])).add(m[4]);
        r[5] = tx.multiply(m[1]).add(ty.multiply(m[3])).add(m[5]);
        return r;
    }

    private static BigDecimal[] identity() {
        return new BigDecimal[] {BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ZERO};
    }

    private static BigDecimal[] numbers(List<String> operands, int count) {
        if (operands.size() != count) {
            return null;
        }
        BigDecimal[] values = new BigDecimal[count];
        for (int i = 0; i < count; i++) {
            values[i] = decimal(operands.get(i));
            if (values[i] == null) {
                return null;
            }
        }
        return values;
    }

    private static BigDecimal decimal(String number) {
        if (number == null || number.isEmpty() || number.charAt(0) == '/') {
            return null;
        }
        try {
            return new BigDecimal(number.startsWith("+") ? number.substring(1) : number);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String format(BigDecimal value) {
        String s = value.stripTrailingZeros().toPlainString();
        if (s.startsWith("0.")) {
            return s.substring(1);
        }
        if (s.startsWith("-0.")) {
            return "-" + s.substring(2);
        }
        return "-0".equals(s) ? "0" : s;
    }
}
