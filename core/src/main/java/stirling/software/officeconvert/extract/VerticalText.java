package stirling.software.officeconvert.extract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

final class VerticalText {

    private static final int MIN_COLUMN = 3;

    private VerticalText() {}

    static void lift(List<Glyph> glyphs, List<Glyph> rotated) {
        List<List<Glyph>> columns = new ArrayList<>();
        List<Glyph> run = new ArrayList<>();
        for (Glyph g : glyphs) {
            if (!run.isEmpty() && !follows(run.getLast(), g)) {
                columns.add(run);
                run = new ArrayList<>();
            }
            if (ideograph(g)) {
                run.add(g);
            }
        }
        columns.add(run);
        List<Glyph> lifted = new ArrayList<>();
        List<Glyph> previous = null;
        for (List<Glyph> column : columns) {
            boolean kept = column.size() >= MIN_COLUMN || !column.isEmpty() && previous != null && nextColumn(previous, column);
            if (kept) {
                lifted.addAll(column);
                previous = column;
            } else if (!column.isEmpty()) {
                previous = null;
            }
        }
        if (lifted.isEmpty()) {
            return;
        }
        Set<Glyph> gone = Collections.newSetFromMap(new IdentityHashMap<>());
        gone.addAll(lifted);
        glyphs.removeIf(gone::contains);
        for (Glyph g : lifted) {
            rotated.add(turned(g));
        }
    }

    private static boolean follows(Glyph prev, Glyph g) {
        if (!ideograph(g) || g.vertAlign != 0 || Math.abs(g.size - prev.size) > 0.1f * prev.size) {
            return false;
        }
        float step = g.baseline - prev.baseline;
        return Math.abs(g.x - prev.x) < 0.1f * g.size && step > 0.8f * g.size && step < 1.6f * g.size;
    }

    private static boolean nextColumn(List<Glyph> previous, List<Glyph> column) {
        Glyph top = previous.getFirst();
        Glyph first = column.getFirst();
        float shift = Math.abs(first.x - top.x);
        return Math.abs(first.baseline - top.baseline) < 0.6f * top.size && shift > 0.9f * top.size
                && shift < 3f * top.size && Math.abs(first.size - top.size) < 0.1f * top.size;
    }

    private static boolean ideograph(Glyph g) {
        if (g.vertAlign != 0 || g.text.isEmpty() || g.text.codePointCount(0, g.text.length()) != 1) {
            return false;
        }
        int cp = g.text.codePointAt(0);
        if (cp < 0x1100) {
            return false;
        }
        Character.UnicodeScript s = Character.UnicodeScript.of(cp);
        if (s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA
                || s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HANGUL) {
            return true;
        }
        Character.UnicodeBlock b = Character.UnicodeBlock.of(cp);
        return b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION || b == Character.UnicodeBlock.VERTICAL_FORMS
                || b == Character.UnicodeBlock.CJK_COMPATIBILITY_FORMS
                || b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS && cp < 0xFF61;
    }

    private static Glyph turned(Glyph g) {
        float across = Math.max(0.01f, g.ascent + g.descent);
        Glyph r = new Glyph(g.text, g.x + g.width * g.descent / across, g.size, g.baseline - 0.88f * g.size, g.size,
                g.ascent, g.descent, g.font, g.rgb, g.seq, g.spaceWidth, g.bold, g.italic);
        r.vertAlign = 270;
        r.upright = true;
        r.underline = g.underline;
        r.strike = g.strike;
        r.highlightRgb = g.highlightRgb;
        r.link = g.link;
        r.hscale = g.hscale;
        return r;
    }
}
