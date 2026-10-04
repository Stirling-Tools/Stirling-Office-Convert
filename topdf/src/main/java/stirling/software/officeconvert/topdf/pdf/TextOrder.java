package stirling.software.officeconvert.topdf.pdf;

import java.util.ArrayList;
import java.util.List;

/** Puts the pieces of a line, drawn left to right, in reading order as Word writes them, so RTL text extracts. */
final class TextOrder {

    record Piece(String ops, float x0, float x1, float baseline, float size, String text, boolean upright) {}

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
                for (int k : right == 0 ? identity(kinds.length) : order(kinds, rightToLeft(kinds, left, right))) {
                    out.add(line.get(k));
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
