package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.IntUnaryOperator;

import stirling.software.officeconvert.fonts.CffCodec;

final class CharStringExpansion {

    static final int MAX_TOKENS = 1 << 17;

    static final int MAX_DEPTH = 64;

    static final long MAX_FONT_TOKENS = 1L << 27;

    record Result(int tokens, int[] components) {}

    private record Command(int b0, int b1) {}

    private static final class Malformed extends Exception {
        Malformed() {
            super(null, null, false, false);
        }
    }

    private final List<Object> sequence = new ArrayList<>();

    private int tokens;

    private int hstems;

    private int vstems;

    private CharStringExpansion() {}

    static Result type2(byte[] cs, byte[][] globalSubrs, byte[][] localSubrs) throws IOException {
        CharStringExpansion e = new CharStringExpansion();
        try {
            e.type2(cs, globalSubrs, localSubrs, 0);
        } catch (Malformed | RuntimeException ignored) {
        }
        return e.result(14 << 8);
    }

    static Result type1(byte[] cs, List<byte[]> subrs) throws IOException {
        CharStringExpansion e = new CharStringExpansion();
        try {
            e.type1(cs, subrs, 0);
        } catch (Malformed | RuntimeException ignored) {
        }
        return e.result(12 << 8 | 6);
    }

    private Result result(int accentCommand) {
        List<Integer> components = new ArrayList<>();
        for (int i = 2; i < sequence.size(); i++) {
            if (sequence.get(i) instanceof Command c && (c.b0() << 8 | c.b1()) == accentCommand
                    && sequence.get(i - 2) instanceof Number base && sequence.get(i - 1) instanceof Number accent) {
                components.add(base.intValue());
                components.add(accent.intValue());
            }
        }
        return new Result(tokens, components.stream().mapToInt(Integer::intValue).toArray());
    }

    static void check(Result[] glyphs, IntUnaryOperator componentGlyph) throws IOException {
        long[] cost = new long[glyphs.length];
        byte[] state = new byte[glyphs.length];
        long total = 0;
        for (int gid = 0; gid < glyphs.length; gid++) {
            total += cost(gid, glyphs, componentGlyph, cost, state);
            if (total > MAX_FONT_TOKENS) {
                throw new IOException("The font's charstrings expand beyond " + MAX_FONT_TOKENS + " tokens");
            }
        }
    }

    private static long cost(int gid, Result[] glyphs, IntUnaryOperator componentGlyph, long[] cost, byte[] state)
            throws IOException {
        if (state[gid] == 2) {
            return cost[gid];
        }
        if (state[gid] == 1) {
            return glyphs[gid].tokens();
        }
        state[gid] = 1;
        long c = glyphs[gid].tokens();
        for (int code : glyphs[gid].components()) {
            int component = componentGlyph.applyAsInt(code);
            if (component >= 0 && component < glyphs.length) {
                c += cost(component, glyphs, componentGlyph, cost, state);
            }
            if (c > MAX_TOKENS) {
                throw new IOException("A glyph's accented components expand beyond " + MAX_TOKENS + " tokens");
            }
        }
        cost[gid] = c;
        state[gid] = 2;
        return c;
    }

    private void step(int depth) throws IOException {
        if (++tokens > MAX_TOKENS || depth > MAX_DEPTH) {
            throw new IOException("A glyph's charstring expands beyond " + MAX_TOKENS + " tokens or "
                    + MAX_DEPTH + " nested subroutines");
        }
    }

    private void type2(byte[] cs, byte[][] global, byte[][] local, int depth) throws IOException, Malformed {
        if (cs == null) {
            throw new Malformed();
        }
        int i = 0;
        while (i < cs.length) {
            step(depth);
            int b = cs[i++] & 0xFF;
            if (b == 10) {
                if (local != null && local.length > 0) {
                    type2Subr(subr(local), global, local, depth);
                }
            } else if (b == 29) {
                if (global != null && global.length > 0) {
                    type2Subr(subr(global), global, local, depth);
                }
            } else if (b == 19 || b == 20) {
                vstems += trailingNumbers() / 2;
                int total = hstems + vstems;
                i += total / 8 + (total % 8 > 0 ? 1 : 0);
                if (i > cs.length) {
                    throw new Malformed();
                }
                sequence.add(new Command(b, 0));
            } else if (b <= 18 || b >= 21 && b <= 27 || b >= 30 && b <= 31) {
                if (b == 1 || b == 18) {
                    hstems += trailingNumbers() / 2;
                } else if (b == 3 || b == 23) {
                    vstems += trailingNumbers() / 2;
                }
                int b1 = 0;
                if (b == 12) {
                    i = need(cs, i, 1);
                    b1 = cs[i - 1] & 0xFF;
                }
                sequence.add(new Command(b, b1));
            } else if (b == 28) {
                i = need(cs, i, 2);
                sequence.add((int) (short) ((cs[i - 2] & 0xFF) << 8 | cs[i - 1] & 0xFF));
            } else if (b == 255) {
                i = need(cs, i, 4);
                sequence.add((short) ((cs[i - 4] & 0xFF) << 8 | cs[i - 3] & 0xFF)
                        + ((cs[i - 2] & 0xFF) << 8 | cs[i - 1] & 0xFF) / 65_535.0);
            } else {
                i = number(cs, i, b);
            }
        }
    }

    private byte[] subr(byte[][] subrs) throws Malformed {
        if (sequence.isEmpty() || !(sequence.remove(sequence.size() - 1) instanceof Integer n)) {
            throw new Malformed();
        }
        int count = subrs.length;
        int index = n + CffCodec.subrBias(count);
        if (index < 0) {
            throw new Malformed();
        }
        return index < count ? subrs[index] : null;
    }

    private void type2Subr(byte[] subr, byte[][] global, byte[][] local, int depth) throws IOException, Malformed {
        type2(subr, global, local, depth + 1);
        dropReturn();
    }

    private void dropReturn() throws Malformed {
        if (sequence.isEmpty()) {
            throw new Malformed();
        }
        if (sequence.get(sequence.size() - 1) instanceof Command c && c.b0() == 11 && c.b1() == 0) {
            sequence.remove(sequence.size() - 1);
        }
    }

    private int trailingNumbers() {
        int n = 0;
        for (int i = sequence.size() - 1; i >= 0 && sequence.get(i) instanceof Number; i--) {
            n++;
        }
        return n;
    }

    private void type1(byte[] cs, List<byte[]> subrs, int depth) throws IOException, Malformed {
        if (cs == null) {
            throw new Malformed();
        }
        int i = 0;
        while (i < cs.length) {
            step(depth);
            int b = cs[i++] & 0xFF;
            if (b == 10) {
                type1Subr(subrs, depth);
            } else if (b == 12 && peek(cs, i) == 16) {
                i = otherSubr(cs, i + 1);
            } else if (b <= 31) {
                int b1 = 0;
                if (b == 12) {
                    i = need(cs, i, 1);
                    b1 = cs[i - 1] & 0xFF;
                }
                sequence.add(new Command(b, b1));
            } else if (b == 255) {
                i = need(cs, i, 4);
                sequence.add((cs[i - 4] & 0xFF) << 24 | (cs[i - 3] & 0xFF) << 16 | (cs[i - 2] & 0xFF) << 8
                        | cs[i - 1] & 0xFF);
            } else {
                i = number(cs, i, b);
            }
        }
    }

    private void type1Subr(List<byte[]> subrs, int depth) throws IOException, Malformed {
        if (sequence.isEmpty()) {
            throw new Malformed();
        }
        if (!(sequence.remove(sequence.size() - 1) instanceof Integer n)) {
            return;
        }
        if (n >= 0 && n < subrs.size()) {
            type1(subrs.get(n), subrs, depth + 1);
            dropReturn();
            return;
        }
        while (!sequence.isEmpty() && sequence.get(sequence.size() - 1) instanceof Integer) {
            sequence.remove(sequence.size() - 1);
        }
    }

    private int otherSubr(byte[] cs, int i) throws Malformed {
        int number = removeInteger(false);
        int args = removeInteger(false);
        Deque<Integer> results = new ArrayDeque<>();
        switch (number) {
            case 0 -> {
                results.push(removeInteger(true));
                results.push(removeInteger(true));
                if (sequence.isEmpty()) {
                    throw new Malformed();
                }
                sequence.remove(sequence.size() - 1);
                sequence.add(0);
                sequence.add(new Command(12, 16));
            }
            case 1 -> {
                sequence.add(1);
                sequence.add(new Command(12, 16));
            }
            case 3 -> results.push(removeInteger(true));
            default -> {
                for (int k = 0; k < args; k++) {
                    results.push(removeInteger(true));
                }
            }
        }
        while (peek(cs, i) == 12 && peek(cs, i + 1) == 17) {
            i += 2;
            if (results.isEmpty()) {
                throw new Malformed();
            }
            sequence.add(results.pop());
        }
        return i;
    }

    private int removeInteger(boolean divides) throws Malformed {
        if (sequence.isEmpty()) {
            throw new Malformed();
        }
        Object last = sequence.remove(sequence.size() - 1);
        if (last instanceof Integer n) {
            return n;
        }
        if (divides && last instanceof Command c && c.b0() == 12 && c.b1() == 12) {
            int b = removeInteger(false);
            int a = removeInteger(false);
            if (b == 0) {
                throw new Malformed();
            }
            return a / b;
        }
        throw new Malformed();
    }

    private static int peek(byte[] cs, int at) throws Malformed {
        if (at >= cs.length) {
            throw new Malformed();
        }
        return cs[at] & 0xFF;
    }

    private static int need(byte[] cs, int at, int bytes) throws Malformed {
        if (at + bytes > cs.length) {
            throw new Malformed();
        }
        return at + bytes;
    }

    private int number(byte[] cs, int i, int b) throws Malformed {
        if (b >= 32 && b <= 246) {
            sequence.add(b - 139);
            return i;
        }
        i = need(cs, i, 1);
        int w = cs[i - 1] & 0xFF;
        sequence.add(CffCodec.twoByteInt(b, w));
        return i;
    }
}
