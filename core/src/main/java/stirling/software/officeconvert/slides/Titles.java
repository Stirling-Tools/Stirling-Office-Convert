package stirling.software.officeconvert.slides;

import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.model.Inline;

final class Titles {

    private static final float TOP_SHARE = 0.55f;

    private static final float LARGER = 1.15f;

    private static final int MAX_CHARS = 200;

    private Titles() {}

    static void mark(List<SlideShape> texts, float slideHeight) {
        List<Float> sizes = new ArrayList<>();
        for (SlideShape s : texts) {
            if (s instanceof TextShape t) {
                for (TextPara p : t.paras()) {
                    int n = chars(p);
                    for (int i = 0; i < Math.min(n, 400); i += 10) {
                        sizes.add(p.size());
                    }
                }
            }
        }
        if (sizes.isEmpty()) {
            return;
        }
        sizes.sort(Float::compare);
        float typical = sizes.get(sizes.size() / 2);
        int best = -1;
        float bestSize = 0;
        for (int i = 0; i < texts.size(); i++) {
            if (!(texts.get(i) instanceof TextShape t) || t.frame().rotation() != 0 || t.frame().y() > TOP_SHARE * slideHeight) {
                continue;
            }
            float size = 0;
            int chars = 0;
            boolean listed = false;
            for (TextPara p : t.paras()) {
                size = Math.max(size, p.size());
                chars += chars(p);
                listed |= p.bullet() != null;
            }
            boolean larger = size >= LARGER * typical || texts.size() == 1;
            if (larger && !listed && chars > 0 && chars <= MAX_CHARS && t.paras().size() <= 3
                    && (size > bestSize || size == bestSize && best >= 0 && t.frame().y() < frameY(texts.get(best)))) {
                best = i;
                bestSize = size;
            }
        }
        if (best >= 0) {
            texts.set(best, ((TextShape) texts.get(best)).asTitle());
        }
    }

    private static float frameY(SlideShape s) {
        return ((TextShape) s).frame().y();
    }

    private static int chars(TextPara p) {
        int n = 0;
        for (Inline in : p.content().inlines) {
            if (in instanceof Inline.Text t) {
                n += t.text().strip().length();
            }
        }
        return n;
    }
}
