package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class SideNotes {

    private static final Pattern NUMBERING = Pattern.compile("^(\\d+\\.)+\\d*\\.?$|^\\(?[a-zA-Z0-9]{1,3}\\)$");

    record Result(List<Line> notes, float textLeft) {

        static final Result NONE = new Result(List.of(), Float.NaN);
    }

    private record Body(float left, float size) {}

    private SideNotes() {}

    static Result extract(List<Line> lines) {
        Body body = mainText(lines);
        if (body == null) {
            return Result.NONE;
        }
        List<Line> side = new ArrayList<>();
        for (Line l : lines) {
            if (l.right < body.left() - 6f && l.chars < 40 && !l.bold && l.size <= 0.92f * body.size()
                    && !isMarker(l) && !closeToText(l, lines)) {
                side.add(l);
            }
        }
        if (side.size() < 3) {
            return Result.NONE;
        }
        lines.removeAll(side);
        return new Result(side, body.left());
    }

    private static boolean isMarker(Line l) {
        if (l.words.size() != 1) {
            return false;
        }
        Word w = l.words.getFirst();
        return Marker.parse(w.text, w.first().font) != null || NUMBERING.matcher(w.text).matches();
    }

    private static boolean closeToText(Line l, List<Line> lines) {
        for (Line o : lines) {
            if (o != l && Math.abs(o.baseline - l.baseline) < 0.3f * l.size && o.x > l.right && o.x - l.right < 2f * l.size) {
                return true;
            }
        }
        return false;
    }

    private static Body mainText(List<Line> lines) {
        Map<Integer, Integer> counts = new HashMap<>();
        List<Float> sizes = new ArrayList<>();
        for (Line l : lines) {
            if (l.chars >= 40) {
                counts.merge(Math.round(l.x / 3f), 1, Integer::sum);
                sizes.add(l.size);
            }
        }
        if (sizes.size() < 4) {
            return null;
        }
        int bestKey = 0;
        int best = 0;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                bestKey = e.getKey();
            }
        }
        if (best * 2 < sizes.size()) {
            return null;
        }
        Float[] sorted = sizes.toArray(new Float[0]);
        Arrays.sort(sorted);
        return new Body(bestKey * 3f - 1.5f, sorted[sorted.length / 2]);
    }
}
