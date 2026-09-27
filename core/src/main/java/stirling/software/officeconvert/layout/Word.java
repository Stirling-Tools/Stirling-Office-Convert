package stirling.software.officeconvert.layout;

import java.util.List;

import stirling.software.officeconvert.extract.Glyph;

public final class Word {

    public final List<Glyph> glyphs;
    public final float x;
    public final float right;
    public final String text;

    public Word(List<Glyph> glyphs) {
        this.glyphs = glyphs;
        float lo = Float.MAX_VALUE;
        float hi = -Float.MAX_VALUE;
        StringBuilder sb = new StringBuilder();
        for (Glyph g : glyphs) {
            lo = Math.min(lo, g.x);
            hi = Math.max(hi, g.right());
            sb.append(g.text);
        }
        this.x = lo;
        this.right = hi;
        this.text = sb.toString();
    }

    public float width() {
        return right - x;
    }

    public Glyph first() {
        return glyphs.getFirst();
    }

    public Glyph last() {
        return glyphs.getLast();
    }

    public float size() {
        float best = 0;
        for (Glyph g : glyphs) {
            if (g.vertAlign == 0) {
                best = Math.max(best, g.size);
            }
        }
        return best > 0 ? best : glyphs.getFirst().size;
    }

    @Override
    public String toString() {
        return text;
    }
}
