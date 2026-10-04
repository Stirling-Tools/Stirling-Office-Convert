package stirling.software.officeconvert.slides;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.IconShape;
import stirling.software.officeconvert.extract.PageData;
import stirling.software.officeconvert.extract.PageGraphics.Fill;
import stirling.software.officeconvert.extract.PageGraphics.ImageDraw;
import stirling.software.officeconvert.extract.PageGraphics.Rule;
import stirling.software.officeconvert.extract.PageGraphics.VectorMark;
import stirling.software.officeconvert.extract.PageGraphics;

record PageFit(float scale, float dx, float dy) {

    static final PageFit NONE = new PageFit(1, 0, 0);

    static PageFit of(PageData page, float width, float height) {
        if (Math.abs(page.width() - width) <= 1 && Math.abs(page.height() - height) <= 1) {
            return NONE;
        }
        float k = Math.min(width / page.width(), height / page.height());
        return new PageFit(k, (width - page.width() * k) / 2f, (height - page.height() * k) / 2f);
    }

    boolean identity() {
        return this == NONE;
    }

    AffineTransform toSlide(AffineTransform toDisplay) {
        AffineTransform t = AffineTransform.getTranslateInstance(dx, dy);
        t.scale(scale, scale);
        t.concatenate(toDisplay);
        return t;
    }

    PageData apply(PageData p, float width, float height) {
        if (identity()) {
            return p;
        }
        PageGraphics g = p.graphics();
        Map<Object, Integer> order = new IdentityHashMap<>();
        Set<Object> seeThrough = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Rule> rules = new ArrayList<>();
        for (Rule r : g.rules()) {
            float pos = r.horizontal() ? y(r.pos()) : x(r.pos());
            float start = r.horizontal() ? x(r.start()) : y(r.start());
            float end = r.horizontal() ? x(r.end()) : y(r.end());
            rules.add(carry(g, r, new Rule(r.horizontal(), pos, start, end, r.thickness() * scale, r.rgb()), order, seeThrough));
        }
        List<Fill> fills = new ArrayList<>();
        for (Fill f : g.fills()) {
            fills.add(carry(g, f, new Fill(x(f.x()), y(f.top()), x(f.right()), y(f.bottom()), f.rgb()), order, seeThrough));
        }
        List<ImageDraw> images = new ArrayList<>();
        for (ImageDraw d : g.images()) {
            images.add(carry(g, d, new ImageDraw(x(d.x()), y(d.top()), x(d.right()), y(d.bottom()), x(d.clipX()),
                    y(d.clipTop()), x(d.clipRight()), y(d.clipBottom()), d.image(), d.key(), d.quarterTurns(), d.flipH(),
                    d.flipV(), d.skewed(), d.stencilRgb(), d.alpha()), order, seeThrough));
        }
        List<VectorMark> marks = new ArrayList<>();
        for (VectorMark m : g.marks()) {
            marks.add(carry(g, m, new VectorMark(x(m.x()), y(m.top()), x(m.right()), y(m.bottom()), m.segments(),
                    m.curved(), m.diagonal(), m.filled(), m.shading(), m.rgb(), m.stroked(), m.strokeRgb(),
                    m.lineWidth() * scale, m.round(), m.boxy(), m.sparse()), order, seeThrough));
        }
        List<PageGraphics.Area> areas = areas(g.pastBudget());
        List<PageData.Link> links = new ArrayList<>();
        for (PageData.Link l : p.links()) {
            links.add(new PageData.Link(x(l.x()), y(l.top()), x(l.right()), y(l.bottom()), l.uri(), l.targetPage()));
        }
        return new PageData(p.index(), width, height, p.direction(), glyphs(p.glyphs()), glyphs(p.hidden()),
                glyphs(p.rotated()), new PageGraphics(rules, fills, images, marks, areas, order, seeThrough, Map.of(), areas(g.masked())),
                links);
    }

    private List<PageGraphics.Area> areas(List<PageGraphics.Area> from) {
        List<PageGraphics.Area> out = new ArrayList<>();
        for (PageGraphics.Area a : from) {
            out.add(new PageGraphics.Area(x(a.x()), y(a.top()), x(a.right()), y(a.bottom())));
        }
        return out;
    }

    private static <T> T carry(PageGraphics g, Object from, T to, Map<Object, Integer> order, Set<Object> seeThrough) {
        int at = g.order(from);
        if (at != Integer.MAX_VALUE) {
            order.put(to, at);
        }
        if (g.seeThrough(from)) {
            seeThrough.add(to);
        }
        return to;
    }

    private float x(float v) {
        return v * scale + dx;
    }

    private float y(float v) {
        return v * scale + dy;
    }

    private List<Glyph> glyphs(List<Glyph> in) {
        List<Glyph> out = new ArrayList<>(in.size());
        for (Glyph g : in) {
            Glyph s = new Glyph(g.text, x(g.x), g.width * scale, y(g.baseline), g.size * scale, g.ascent * scale,
                    g.descent * scale, g.font, g.rgb, g.seq, g.spaceWidth * scale, g.bold, g.italic);
            s.underline = g.underline;
            s.strike = g.strike;
            s.highlightRgb = g.highlightRgb;
            s.link = g.link;
            s.footnote = g.footnote;
            s.vertAlign = g.vertAlign;
            s.hscale = g.hscale;
            s.invisible = g.invisible;
            if (g.icon != null) {
                GeneralPath outline = new GeneralPath(g.icon.outline());
                outline.transform(AffineTransform.getScaleInstance(scale, scale));
                s.icon = new IconShape(outline, g.icon.rgb(), g.icon.key() + "|" + scale);
            }
            out.add(s);
        }
        return out;
    }
}
