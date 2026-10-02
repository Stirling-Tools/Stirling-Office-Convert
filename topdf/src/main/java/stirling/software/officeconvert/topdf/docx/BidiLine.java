package stirling.software.officeconvert.topdf.docx;

import java.text.Bidi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class BidiLine {

    private BidiLine() {}

    // A right-to-left line laid out from its start edge: runs that read left to right are reversed, then the
    // whole line is mirrored across the width, which puts everything else in right-to-left order
    static void mirror(Line line, float width) {
        List<Line.Slice> slices = line.slices;
        int[] levels = resolved(slices, true);
        if (levels != null) {
            for (int i = 0; i < levels.length; i++) {
                levels[i] = Math.max(0, levels[i] - 1);
            }
            order(slices, levels);
        }
        float end = Float.NEGATIVE_INFINITY;
        for (Line.Slice s : slices) {
            s.x = width - s.x - s.w;
            end = Math.max(end, s.x + s.w);
        }
        Collections.reverse(slices);
        float left = line.left;
        line.left = width - line.right;
        line.right = width - left;
        line.end = slices.isEmpty() ? line.right : end;
    }

    private static void order(List<Line.Slice> slices, int[] levels) {
        int max = 0;
        for (int level : levels) {
            max = Math.max(max, level);
        }
        if (max == 0) {
            return;
        }
        List<Line.Slice> order = new ArrayList<>(slices);
        int[] lv = levels.clone();
        for (int level = max; level >= 1; level--) {
            int i = 0;
            while (i < order.size()) {
                if (lv[i] < level) {
                    i++;
                    continue;
                }
                int j = i;
                while (j < order.size() && lv[j] >= level) {
                    j++;
                }
                reverse(order, lv, i, j - 1);
                i = j;
            }
        }
        float x = Float.MAX_VALUE;
        for (Line.Slice s : slices) {
            x = Math.min(x, s.x);
        }
        for (Line.Slice s : order) {
            s.x = x;
            x += s.w;
        }
        slices.clear();
        slices.addAll(order);
    }

    static void reorder(Line line, boolean rtlParagraph) {
        List<Line.Slice> slices = line.slices;
        if (slices.size() < 2 || !rtlParagraph && !hasRtl(slices)) {
            return;
        }
        int[] levels = resolved(slices, rtlParagraph);
        if (levels == null) {
            levels = fromText(slices, rtlParagraph);
        }
        if (levels == null) {
            return;
        }
        int max = 0;
        int minOdd = Integer.MAX_VALUE;
        for (int level : levels) {
            max = Math.max(max, level);
            if ((level & 1) == 1) {
                minOdd = Math.min(minOdd, level);
            }
        }
        if (minOdd == Integer.MAX_VALUE) {
            return;
        }
        List<Line.Slice> order = new ArrayList<>(slices);
        int[] lv = levels.clone();
        for (int level = max; level >= minOdd; level--) {
            int i = 0;
            while (i < order.size()) {
                if (lv[i] < level) {
                    i++;
                    continue;
                }
                int j = i;
                while (j < order.size() && lv[j] >= level) {
                    j++;
                }
                reverse(order, lv, i, j - 1);
                i = j;
            }
        }
        float x = Float.MAX_VALUE;
        for (Line.Slice s : slices) {
            x = Math.min(x, s.x);
        }
        // White space ending a right-to-left line hangs past its left end, where the reversal puts it
        for (int i = slices.size() - 1; rtlParagraph && i >= 0 && blank(slices.get(i)) && (levels[i] & 1) == 1; i--) {
            x -= slices.get(i).w;
        }
        for (Line.Slice s : order) {
            s.x = x;
            x += s.w;
        }
        slices.clear();
        slices.addAll(order);
    }

    // The levels the paragraph's bidi pass gave each slice; white space ending the line takes the paragraph's
    private static int[] resolved(List<Line.Slice> slices, boolean rtlParagraph) {
        int[] levels = new int[slices.size()];
        for (int i = 0; i < slices.size(); i++) {
            int level = slices.get(i).item.level;
            if (level < 0) {
                return null;
            }
            levels[i] = level;
        }
        int base = rtlParagraph ? 1 : 0;
        for (int i = slices.size() - 1; i >= 0 && blank(slices.get(i)); i--) {
            levels[i] = base;
        }
        return levels;
    }

    private static boolean blank(Line.Slice s) {
        return s.item.kind == Item.Kind.TAB || s.item.kind == Item.Kind.TEXT && s.text().isBlank();
    }

    private static int[] fromText(List<Line.Slice> slices, boolean rtlParagraph) {
        StringBuilder text = new StringBuilder();
        int[] starts = new int[slices.size()];
        for (int i = 0; i < slices.size(); i++) {
            Line.Slice s = slices.get(i);
            starts[i] = text.length();
            if (s.item.kind == Item.Kind.TEXT && s.to > s.from) {
                text.append(s.item.text, s.from, s.to);
            } else if (s.item.kind == Item.Kind.TAB) {
                text.append('	');
            } else {
                text.append('\uFFFC');
            }
        }
        Bidi bidi = new Bidi(text.toString(), rtlParagraph ? Bidi.DIRECTION_RIGHT_TO_LEFT
                : Bidi.DIRECTION_LEFT_TO_RIGHT);
        if (bidi.isLeftToRight()) {
            return null;
        }
        int[] levels = new int[slices.size()];
        for (int i = 0; i < slices.size(); i++) {
            levels[i] = bidi.getLevelAt(firstStrong(text, starts[i], i + 1 < starts.length ? starts[i + 1]
                    : text.length()));
        }
        return levels;
    }

    private static int firstStrong(CharSequence text, int from, int to) {
        for (int i = from; i < to; i++) {
            byte d = Character.getDirectionality(text.charAt(i));
            if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                    || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                return i;
            }
        }
        return from;
    }

    private static void reverse(List<Line.Slice> order, int[] lv, int a, int b) {
        while (a < b) {
            Line.Slice t = order.get(a);
            order.set(a, order.get(b));
            order.set(b, t);
            int l = lv[a];
            lv[a] = lv[b];
            lv[b] = l;
            a++;
            b--;
        }
    }

    private static boolean hasRtl(List<Line.Slice> slices) {
        for (Line.Slice s : slices) {
            if (s.item.kind == Item.Kind.TEXT && s.item.rtl) {
                return true;
            }
        }
        return false;
    }
}
