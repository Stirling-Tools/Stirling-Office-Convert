package stirling.software.officeconvert.extract;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

import org.apache.pdfbox.contentstream.operator.OperatorName;

final class TextClips {

    static final int KEEP = -1;

    private static final int MAX_PENDING = 20_000;

    private record Pending(Glyph glyph, int level) {}

    private final List<Pending> pending = new ArrayList<>();
    private final Map<Glyph, Integer> painted = new IdentityHashMap<>();
    private int level;

    void reset() {
        pending.clear();
        painted.clear();
        level = 0;
    }

    void save() {
        level++;
    }

    void restore() {
        level--;
        pending.removeIf(p -> p.level() > level);
    }

    void clip(Glyph glyph) {
        if (pending.size() < MAX_PENDING) {
            pending.add(new Pending(glyph, level));
        }
    }

    void operator(String name, IntSupplier fillRgb) {
        if (!pending.isEmpty() && paints(name)) {
            int colour = fills(name) ? fillRgb.getAsInt() : KEEP;
            for (Pending p : pending) {
                painted.put(p.glyph(), colour);
            }
            pending.clear();
        }
    }

    int mark() {
        return pending.size();
    }

    int level() {
        return level;
    }

    void leave(int mark, int outerLevel) {
        if (pending.size() > mark) {
            pending.subList(mark, pending.size()).clear();
        }
        level = outerLevel;
    }

    boolean painted(Glyph glyph) {
        return !painted.isEmpty() && painted.containsKey(glyph);
    }

    int colour(Glyph glyph) {
        return painted.getOrDefault(glyph, KEEP);
    }

    private static boolean fills(String name) {
        return switch (name) {
            case OperatorName.FILL_NON_ZERO, OperatorName.LEGACY_FILL_NON_ZERO, OperatorName.FILL_EVEN_ODD,
                    OperatorName.FILL_NON_ZERO_AND_STROKE, OperatorName.FILL_EVEN_ODD_AND_STROKE,
                    OperatorName.CLOSE_FILL_NON_ZERO_AND_STROKE, OperatorName.CLOSE_FILL_EVEN_ODD_AND_STROKE -> true;
            default -> false;
        };
    }

    private static boolean paints(String name) {
        return switch (name) {
            case OperatorName.FILL_NON_ZERO, OperatorName.LEGACY_FILL_NON_ZERO, OperatorName.FILL_EVEN_ODD,
                    OperatorName.FILL_NON_ZERO_AND_STROKE, OperatorName.FILL_EVEN_ODD_AND_STROKE,
                    OperatorName.CLOSE_FILL_NON_ZERO_AND_STROKE, OperatorName.CLOSE_FILL_EVEN_ODD_AND_STROKE,
                    OperatorName.STROKE_PATH, OperatorName.CLOSE_AND_STROKE, OperatorName.SHADING_FILL,
                    OperatorName.DRAW_OBJECT, OperatorName.BEGIN_INLINE_IMAGE -> true;
            default -> false;
        };
    }
}
