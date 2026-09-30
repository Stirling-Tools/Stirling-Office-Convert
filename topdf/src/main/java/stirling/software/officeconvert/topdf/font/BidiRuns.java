package stirling.software.officeconvert.topdf.font;

import java.text.Bidi;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class BidiRuns {

    public record Run(int start, int end, int level) {

        public boolean rightToLeft() {
            return (level & 1) != 0;
        }

        public String of(String text) {
            return text.substring(start, end);
        }
    }

    private BidiRuns() {}

    public static boolean needed(CharSequence text) {
        Objects.requireNonNull(text, "text");
        int n = text.length();
        int i = 0;
        // No character before Hebrew has a right-to-left or Arabic-number direction
        while (i < n && text.charAt(i) < 0x0590) {
            i++;
        }
        if (i == n) {
            return false;
        }
        char[] chars = text.toString().toCharArray();
        return Bidi.requiresBidi(chars, 0, chars.length);
    }

    public static List<Run> logical(String text, Boolean baseRightToLeft) {
        Objects.requireNonNull(text, "text");
        if (text.isEmpty()) {
            return List.of();
        }
        int flags = baseRightToLeft == null ? Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT
                : baseRightToLeft ? Bidi.DIRECTION_RIGHT_TO_LEFT : Bidi.DIRECTION_LEFT_TO_RIGHT;
        Bidi bidi = new Bidi(text, flags);
        List<Run> runs = new ArrayList<>();
        for (int i = 0; i < bidi.getRunCount(); i++) {
            runs.add(new Run(bidi.getRunStart(i), bidi.getRunLimit(i), bidi.getRunLevel(i)));
        }
        return runs;
    }

    public static boolean baseRightToLeft(String text) {
        return !text.isEmpty() && !new Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight();
    }

    public static List<Run> visual(List<Run> logical) {
        Objects.requireNonNull(logical, "logical");
        int n = logical.size();
        byte[] levels = new byte[n];
        Object[] runs = logical.toArray();
        for (int i = 0; i < n; i++) {
            levels[i] = (byte) logical.get(i).level();
        }
        Bidi.reorderVisually(levels, 0, runs, 0, n);
        List<Run> out = new ArrayList<>(n);
        for (Object r : runs) {
            out.add((Run) r);
        }
        return out;
    }
}
