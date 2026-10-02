package stirling.software.officeconvert.pdfa;

import java.util.ArrayList;
import java.util.List;

final class Type2Scan {

    private static final int MAX_DEPTH = 10;

    private final List<byte[]> local;

    private final List<byte[]> global;

    private final List<Integer> stack = new ArrayList<>();

    private int stems;

    private int[] seac;

    private boolean done;

    Type2Scan(List<byte[]> local, List<byte[]> global) {
        this.local = local;
        this.global = global;
    }

    int[] seac(byte[] charstring) {
        run(charstring, 0);
        return seac;
    }

    private static int bias(int count) {
        return count < 1240 ? 107 : count < 33900 ? 1131 : 32768;
    }

    private void run(byte[] cs, int depth) {
        if (depth > MAX_DEPTH) {
            done = true;
            return;
        }
        int i = 0;
        while (i < cs.length && !done) {
            int b = cs[i] & 0xFF;
            if (b == 28 && i + 2 < cs.length) {
                stack.add((int) (short) ((cs[i + 1] & 0xFF) << 8 | cs[i + 2] & 0xFF));
                i += 3;
            } else if (b >= 32 && b <= 246) {
                stack.add(b - 139);
                i++;
            } else if (b >= 247 && b <= 250 && i + 1 < cs.length) {
                stack.add((b - 247) * 256 + (cs[i + 1] & 0xFF) + 108);
                i += 2;
            } else if (b >= 251 && b <= 254 && i + 1 < cs.length) {
                stack.add(-(b - 251) * 256 - (cs[i + 1] & 0xFF) - 108);
                i += 2;
            } else if (b == 255 && i + 4 < cs.length) {
                stack.add(((cs[i + 1] & 0xFF) << 8 | cs[i + 2] & 0xFF));
                i += 5;
            } else {
                i = operator(cs, i, b, depth);
            }
        }
    }

    private int operator(byte[] cs, int i, int b, int depth) {
        switch (b) {
            case 1, 3, 18, 23 -> {
                stems += stack.size() / 2;
                stack.clear();
                return i + 1;
            }
            case 19, 20 -> {
                stems += stack.size() / 2;
                stack.clear();
                return i + 1 + (stems + 7) / 8;
            }
            case 10, 29 -> {
                List<byte[]> subrs = b == 10 ? local : global;
                if (stack.isEmpty()) {
                    done = true;
                    return cs.length;
                }
                int n = stack.remove(stack.size() - 1) + bias(subrs.size());
                if (n < 0 || n >= subrs.size()) {
                    done = true;
                    return cs.length;
                }
                run(subrs.get(n), depth + 1);
                return i + 1;
            }
            case 11 -> {
                return cs.length;
            }
            case 14 -> {
                if (stack.size() >= 4) {
                    int n = stack.size();
                    seac = new int[] {stack.get(n - 2), stack.get(n - 1)};
                }
                done = true;
                return cs.length;
            }
            case 12 -> {
                stack.clear();
                return i + 2;
            }
            default -> {
                stack.clear();
                return i + 1;
            }
        }
    }
}
