package stirling.software.officeconvert.topdf.docx;

import java.text.Bidi;
import java.util.ArrayList;
import java.util.List;

final class BidiLine {

    private BidiLine() {}

    static void reorder(Line line, boolean rtlParagraph) {
        List<Line.Slice> slices = line.slices;
        if (slices.size() < 2 || !rtlParagraph && !hasRtl(slices)) {
            return;
        }
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
            return;
        }
        int[] levels = new int[slices.size()];
        int max = 0;
        int minOdd = Integer.MAX_VALUE;
        for (int i = 0; i < slices.size(); i++) {
            levels[i] = bidi.getLevelAt(firstStrong(text, starts[i], i + 1 < starts.length ? starts[i + 1]
                    : text.length()));
            max = Math.max(max, levels[i]);
            if ((levels[i] & 1) == 1) {
                minOdd = Math.min(minOdd, levels[i]);
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
        for (Line.Slice s : order) {
            s.x = x;
            x += s.w;
        }
        slices.clear();
        slices.addAll(order);
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
