package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.FontNames;
import stirling.software.officeconvert.extract.Glyph;

final class Pictograms {

    private Pictograms() {}

    static List<Box> find(List<Line> segments, DocStats stats) {
        List<Float> sizes = new ArrayList<>();
        Map<String, Boolean> alone = new HashMap<>();
        for (Line l : segments) {
            for (Word w : l.words) {
                for (Glyph g : w.glyphs) {
                    sizes.add(g.size);
                    alone.merge(g.font.postScriptName(), w.text.length() <= 2, Boolean::logicalAnd);
                }
            }
        }
        List<Box> out = new ArrayList<>();
        if (sizes.isEmpty()) {
            return out;
        }
        sizes.sort(Float::compare);
        float median = sizes.get(sizes.size() / 2);
        for (Line l : segments) {
            for (Word w : l.words) {
                Glyph g = w.first();
                FontInfo f = g.font;
                if (w.text.length() > 2 || w.size() < 1.8f * median || !f.substituted() || f.symbolic()
                        || FontNames.isTexFont(f.postScriptName()) || !alone.get(f.postScriptName())
                        || !stats.usedOnlyAlone(f)) {
                    continue;
                }
                float top = g.baseline - Math.max(g.ascent, 0.7f * g.size);
                out.add(new Box(w.x - 1, top - 1, w.right + 1, g.baseline + Math.max(g.descent, 0.05f * g.size) + 1));
            }
        }
        return out;
    }
}
