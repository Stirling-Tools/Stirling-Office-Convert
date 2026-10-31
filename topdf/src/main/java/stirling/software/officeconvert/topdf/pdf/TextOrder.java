package stirling.software.officeconvert.topdf.pdf;

import java.util.ArrayList;
import java.util.List;

/** Puts the pieces of a line, drawn left to right, in reading order as Word writes them, so RTL text extracts. */
final class TextOrder {

    record Piece(String ops, float x0, float x1, float baseline, float size, String text, boolean upright,
            boolean visual) {}

    static final int NEUTRAL = 0;

    static final int LEFT = 1;

    static final int RIGHT = 2;

    static final int NUMBER = 3;

    private TextOrder() {}

    static List<Piece> reading(List<Piece> pieces) {
        List<Piece> out = new ArrayList<>(pieces.size());
        int start = 0;
        for (int i = 1; i <= pieces.size(); i++) {
            if (i == pieces.size() || !sameLine(pieces.get(i - 1), pieces.get(i))) {
                List<Piece> line = pieces.subList(start, i);
                int[] kinds = new int[line.size()];
                int left = 0;
                int right = 0;
                for (int k = 0; k < kinds.length; k++) {
                    String t = line.get(k).text();
                    int l = strong(t, false);
                    int r = strong(t, true);
                    left += l;
                    right += r;
                    kinds[k] = kind(t, l, r);
                }
                int[] order = right == 0 ? identity(kinds.length) : order(kinds, rightToLeft(kinds, left, right));
                boolean[] linked = new boolean[order.length];
                visualRuns(line, kinds, order, linked);
                for (int p = 0; p < order.length; p++) {
                    Piece joined = linked[p] ? joined(out.get(out.size() - 1), line.get(order[p])) : null;
                    if (joined != null) {
                        out.set(out.size() - 1, joined);
                    } else {
                        out.add(line.get(order[p]));
                    }
                }
                start = i;
            }
        }
        return out;
    }

    // A paragraph starts at its reading side: strong text of one direction at both ends decides, else the majority
    static boolean rightToLeft(int[] kinds, int left, int right) {
        int first = NEUTRAL;
        int last = NEUTRAL;
        for (int k : kinds) {
            if (k == LEFT || k == RIGHT) {
                first = first == NEUTRAL ? k : first;
                last = k;
            }
        }
        return first == last && first != NEUTRAL ? first == RIGHT : right >= left;
    }

    // Pieces of one line follow each other left to right on about the same baseline
    static boolean sameLine(Piece a, Piece b) {
        float size = Math.max(a.size(), b.size());
        return a.upright() && b.upright() && Math.abs(a.baseline() - b.baseline()) <= size / 2
                && b.x0() >= a.x0() - 0.5f && b.x0() <= a.x1() + 4 * size;
    }

    /** The visual pieces' indices in logical order, from their kinds and the line's base direction. */
    static int[] order(int[] kinds, boolean rightToLeft) {
        int n = kinds.length;
        int[] levels = new int[n];
        int max = 0;
        for (int i = 0; i < n; i++) {
            int kind = kinds[i];
            if (kind == NEUTRAL) {
                int before = side(kinds, i, -1, rightToLeft);
                int after = side(kinds, i, 1, rightToLeft);
                kind = before == after ? before : rightToLeft ? RIGHT : LEFT;
            }
            levels[i] = switch (kind) {
                case RIGHT -> 1;
                case NUMBER -> rightToLeft || nextTo(kinds, i, RIGHT) ? 2 : 0;
                default -> rightToLeft ? 2 : 0;
            };
            max = Math.max(max, levels[i]);
        }
        int[] order = identity(n);
        for (int level = 1; level <= max; level++) {
            int i = 0;
            while (i < n) {
                if (levels[i] < level) {
                    i++;
                    continue;
                }
                int j = i;
                while (j < n && levels[j] >= level) {
                    j++;
                }
                for (int a = i, b = j - 1; a < b; a++, b--) {
                    int t = order[a];
                    order[a] = order[b];
                    order[b] = t;
                    t = levels[a];
                    levels[a] = levels[b];
                    levels[b] = t;
                }
                i = j;
            }
        }
        return order;
    }

    static void visualRuns(List<Piece> line, int[] kinds, int[] order, boolean[] linked) {
        int i = 0;
        while (i < order.length) {
            int j = i + 1;
            while (j < order.length && order[j] == order[j - 1] - 1 && visualRight(line, kinds, order[j])
                    && visualRight(line, kinds, order[j - 1])) {
                j++;
            }
            int first = i;
            int last = j - 1;
            while (first < last && blank(line.get(order[first]))) {
                first++;
            }
            while (last > first && blank(line.get(order[last]))) {
                last--;
            }
            boolean drawnForward = false;
            for (int k = first; k <= last; k++) {
                drawnForward |= kinds[order[k]] == RIGHT && line.get(order[k]).visual();
            }
            if (drawnForward && first < last) {
                for (int a = first, b = last; a < b; a++, b--) {
                    int t = order[a];
                    order[a] = order[b];
                    order[b] = t;
                }
                for (int k = first + 1; k <= last; k++) {
                    linked[k] = true;
                }
            }
            i = j;
        }
    }

    private static boolean blank(Piece piece) {
        return !piece.text().isEmpty() && piece.text().isBlank();
    }

    static Piece joined(Piece left, Piece right) {
        String a = left.ops();
        String b = right.ops();
        int open = b.indexOf("BT\n");
        if (!single(a) || !single(b) || a.contains(" gs\n") || b.contains(" gs\n")) {
            return null;
        }
        String ops = a.substring(0, a.length() - "ET\nQ\n".length()) + b.substring("q\n".length(), open)
                + "0 Tc 0 Tw 100 Tz 0 Tr 0 Ts\n" + b.substring(open + "BT\n".length());
        return new Piece(ops, Math.min(left.x0(), right.x0()), Math.max(left.x1(), right.x1()), left.baseline(),
                left.size(), left.text() + right.text(), left.upright(), left.visual() || right.visual());
    }

    private static boolean single(String ops) {
        int open = ops.indexOf("BT\n");
        return ops.startsWith("q\n") && ops.endsWith("ET\nQ\n") && open > 0 && ops.indexOf("BT\n", open + 1) < 0
                && ops.indexOf("ET\n") == ops.length() - "ET\nQ\n".length();
    }

    private static boolean visualRight(List<Piece> line, int[] kinds, int i) {
        String text = line.get(i).text();
        return kinds[i] == RIGHT && line.get(i).visual() || marks(text) || blank(line.get(i));
    }

    static boolean marks(String text) {
        return !text.isEmpty() && text.codePoints().allMatch(cp -> {
            int type = Character.getType(cp);
            return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK;
        });
    }

    // The direction a neutral piece takes from its nearest non-neutral neighbour on one side; numbers count as RTL
    private static int side(int[] kinds, int i, int step, boolean rightToLeft) {
        for (int k = i + step; k >= 0 && k < kinds.length; k += step) {
            if (kinds[k] != NEUTRAL) {
                return kinds[k] == LEFT ? LEFT : RIGHT;
            }
        }
        return rightToLeft ? RIGHT : LEFT;
    }

    private static boolean nextTo(int[] kinds, int i, int kind) {
        for (int step = -1; step <= 1; step += 2) {
            for (int k = i + step; k >= 0 && k < kinds.length; k += step) {
                if (kinds[k] == LEFT || kinds[k] == RIGHT) {
                    if (kinds[k] == kind) {
                        return true;
                    }
                    break;
                }
            }
        }
        return false;
    }

    static int kind(String text, int left, int right) {
        if (right > 0 && left == 0) {
            return RIGHT;
        }
        if (left > 0 && right == 0) {
            return LEFT;
        }
        if (left > 0) {
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                if (isRight(cp)) {
                    return RIGHT;
                }
                if (Character.getDirectionality(cp) == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
                    return LEFT;
                }
                i += Character.charCount(cp);
            }
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.isDigit(cp)) {
                return NUMBER;
            }
            i += Character.charCount(cp);
        }
        return NEUTRAL;
    }

    private static int strong(String text, boolean right) {
        int n = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (right ? isRight(cp) : Character.getDirectionality(cp) == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
                n++;
            }
            i += Character.charCount(cp);
        }
        return n;
    }

    private static boolean isRight(int cp) {
        byte d = Character.getDirectionality(cp);
        return d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC;
    }

    private static int[] identity(int n) {
        int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        return order;
    }
}
