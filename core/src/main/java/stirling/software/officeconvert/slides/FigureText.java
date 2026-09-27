package stirling.software.officeconvert.slides;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.PageData;
import stirling.software.officeconvert.layout.Box;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.LineBuilder;
import stirling.software.officeconvert.layout.OcrText;
import stirling.software.officeconvert.layout.PageLayout;
import stirling.software.officeconvert.layout.ParaDraft;
import stirling.software.officeconvert.layout.ParagraphBuilder;
import stirling.software.officeconvert.layout.Word;
import stirling.software.officeconvert.slides.PaintOrder.Kind;

final class FigureText {

    private static final int MAX_GLYPHS = 400;
    private static final int MAX_LINES = 40;

    private final ParagraphBuilder paragraphs;

    FigureText(ParagraphBuilder paragraphs) {
        this.paragraphs = paragraphs;
    }

    static Set<Glyph> placed(PageLayout layout) {
        Set<Glyph> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PageLayout.Band band : layout.bands()) {
            for (PageLayout.Column col : band.columns()) {
                for (PageLayout.Item item : col.items()) {
                    add(item, out);
                }
            }
        }
        for (PageLayout.TextBoxItem tb : layout.furniture()) {
            add(tb, out);
        }
        for (PageLayout.Note n : layout.notes()) {
            addAll(n.paras(), out);
        }
        addAll(layout.noteContinuation(), out);
        return out;
    }

    private static void add(PageLayout.Item item, Set<Glyph> out) {
        switch (item) {
            case PageLayout.ParaItem pi -> addAll(List.of(pi.para()), out);
            case PageLayout.TextBoxItem tb -> addAll(tb.paras(), out);
            case PageLayout.TableItem ti -> ti.cellParas().forEach(paras -> addAll(paras, out));
            case PageLayout.FloatItem fi -> add(fi.picture(), out);
            default -> {
            }
        }
    }

    private static void addAll(List<ParaDraft> paras, Set<Glyph> out) {
        for (ParaDraft d : paras) {
            for (Line l : d.lines) {
                for (Word w : l.words) {
                    out.addAll(w.glyphs);
                }
            }
        }
    }

    List<ParaDraft> live(PageData page, Box box, Set<Glyph> placed, PaintOrder paint) {
        Box area = box.grow(1f);
        for (Glyph g : page.rotated()) {
            if (area.contains(g.x, g.baseline)) {
                return null;
            }
        }
        List<Glyph> inside = new ArrayList<>();
        for (Glyph g : OcrText.pageGlyphs(page)) {
            if (!placed.contains(g) && !g.isSpace() && area.contains(g.centreX(), g.baseline - 0.3f * g.size)) {
                inside.add(g);
            }
        }
        if (inside.isEmpty()) {
            return List.of();
        }
        if (inside.size() > MAX_GLYPHS || paint.lastInside(box, Kind.FILL, Kind.STROKE, Kind.IMAGE, Kind.SHADING) < 0) {
            return null;
        }
        List<Line> lines = new ArrayList<>(LineBuilder.build(inside));
        if (lines.size() > MAX_LINES) {
            return null;
        }
        lines.sort(Comparator.comparingDouble((Line l) -> l.baseline).thenComparingDouble(l -> l.x));
        return paragraphs.build(lines, box.x(), box.right());
    }
}
