package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import stirling.software.officeconvert.extract.Glyph;

// A drop cap spans several lines; left alone it reads as part of its last line. It joins the first line's first word instead.
final class DropCaps {

    private static final float CAP = 2.2f;
    private static final int MIN_LINES = 2;
    private static final int MAX_GLYPHS = 20_000;

    private DropCaps() {}

    static List<Glyph> fold(List<Glyph> glyphs) {
        if (glyphs.size() > MAX_GLYPHS) {
            return glyphs;
        }
        float body = medianSize(glyphs);
        List<Glyph> out = null;
        for (int i = 0; i < glyphs.size(); i++) {
            Glyph cap = glyphs.get(i);
            if (cap.text.length() != 1 || !Character.isUpperCase(cap.text.charAt(0)) || cap.size < CAP * body
                    || !Float.isNaN(cap.hang) || cap.math != null || neighbour(glyphs, cap)) {
                continue;
            }
            Glyph first = firstBeside(glyphs, cap);
            if (first == null) {
                continue;
            }
            float k = first.size / cap.size;
            float width = cap.width * k;
            Glyph moved = new Glyph(cap.text, first.x - width, width, first.baseline, first.size, cap.ascent * k,
                    cap.descent * k, cap.font, cap.rgb, first.seq, first.spaceWidth, cap.bold, cap.italic);
            if (out == null) {
                out = new ArrayList<>(glyphs);
            }
            out.set(i, moved);
        }
        return out == null ? glyphs : out;
    }

    private static boolean neighbour(List<Glyph> glyphs, Glyph cap) {
        for (Glyph g : glyphs) {
            boolean level = Math.abs(g.baseline - cap.baseline) < 0.3f * cap.size;
            boolean after = Math.abs(g.x - cap.right()) < 0.5f * cap.size;
            boolean before = g.right() <= cap.x + 0.1f * cap.size && cap.x - g.right() < cap.size;
            if (g != cap && g.size >= 0.6f * cap.size && level && (after || before)) {
                return true;
            }
        }
        return false;
    }

    private static Glyph firstBeside(List<Glyph> glyphs, Glyph cap) {
        TreeSet<Float> lines = new TreeSet<>();
        Glyph first = null;
        for (Glyph g : glyphs) {
            boolean beside = g.x >= cap.right() - 1 && g.x <= cap.right() + 0.8f * cap.size && g.size < 0.6f * cap.size;
            boolean level = g.baseline > cap.top() + 0.2f * g.size && g.baseline <= cap.baseline + 0.3f * g.size;
            if (!beside || !level || g.text.isBlank()) {
                continue;
            }
            lines.add((float) Math.round(g.baseline));
            if (first == null || g.baseline < first.baseline - 0.2f * g.size
                    || Math.abs(g.baseline - first.baseline) <= 0.2f * g.size && g.x < first.x) {
                first = g;
            }
        }
        return lines.size() >= MIN_LINES && first != null && Character.isLowerCase(first.text.codePointAt(0)) ? first : null;
    }

    private static float medianSize(List<Glyph> glyphs) {
        float[] sizes = new float[glyphs.size()];
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = glyphs.get(i).size;
        }
        java.util.Arrays.sort(sizes);
        return sizes.length == 0 ? 0 : sizes[sizes.length / 2];
    }
}
