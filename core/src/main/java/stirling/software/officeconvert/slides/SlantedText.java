package stirling.software.officeconvert.slides;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.PageData;
import stirling.software.officeconvert.layout.Box;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.LineBuilder;
import stirling.software.officeconvert.layout.ParaDraft;
import stirling.software.officeconvert.layout.ParagraphBuilder;

final class SlantedText {

    private static final float SAME_SLANT = 1f;

    private static final float MATCH = 0.75f;

    record Group(float angle, float pivotX, float pivotY, List<Glyph> onPage, List<Glyph> upright, int order) {}

    record Split(PageData page, List<Group> groups) {}

    private final ParagraphBuilder paragraphs;
    private final TextFrames frames;

    SlantedText(ParagraphBuilder paragraphs, TextFrames frames) {
        this.paragraphs = paragraphs;
        this.frames = frames;
    }

    static Split split(PageData page, PaintOrder paint) {
        if (paint.slants().isEmpty()) {
            return new Split(page, List.of());
        }
        Map<Long, List<PaintOrder.Slant>> grid = new HashMap<>();
        for (PaintOrder.Slant s : paint.slants()) {
            grid.computeIfAbsent(cell(s.x(), s.y()), k -> new ArrayList<>()).add(s);
        }
        List<Glyph> glyphs = new ArrayList<>();
        List<PaintOrder.Slant> found = new ArrayList<>();
        for (Glyph g : page.glyphs()) {
            PaintOrder.Slant s = at(grid, g);
            if (s != null) {
                glyphs.add(g);
                found.add(s);
            }
        }
        List<Group> groups = new ArrayList<>();
        Set<Glyph> taken = Collections.newSetFromMap(new IdentityHashMap<>());
        for (List<Integer> members : bySlant(found)) {
            if (members.stream().anyMatch(i -> !glyphs.get(i).isSpace())) {
                groups.add(group(members, glyphs, found));
            }
            for (int i : members) {
                taken.add(glyphs.get(i));
            }
        }
        if (taken.isEmpty()) {
            return new Split(page, List.of());
        }
        List<Glyph> rest = new ArrayList<>(page.glyphs());
        rest.removeIf(taken::contains);
        return new Split(new PageData(page.index(), page.width(), page.height(), page.direction(), rest, page.hidden(),
                page.rotated(), page.graphics(), page.links(), page.widgets()), groups);
    }

    List<TextFrames.Framed> frames(List<Group> groups, List<Box> shown) throws IOException {
        List<TextFrames.Framed> out = new ArrayList<>();
        for (Group g : groups) {
            List<Glyph> upright = new ArrayList<>();
            for (int i = 0; i < g.onPage().size(); i++) {
                Glyph on = g.onPage().get(i);
                if (shown.stream().noneMatch(b -> b.contains(on.x, on.baseline))) {
                    upright.add(g.upright().get(i));
                }
            }
            if (upright.isEmpty()) {
                continue;
            }
            List<Line> lines = new ArrayList<>(LineBuilder.build(upright));
            lines.sort(Comparator.comparingDouble((Line l) -> l.baseline).thenComparingDouble(l -> l.x));
            float left = Float.MAX_VALUE;
            float right = -Float.MAX_VALUE;
            for (Line l : lines) {
                left = Math.min(left, l.x);
                right = Math.max(right, l.right);
            }
            List<ParaDraft> paras = paragraphs.build(lines, left, right);
            for (TextFrames.Framed f : frames.frames(paras, new ArrayList<>(), null)) {
                out.add(new TextFrames.Framed(turned(f.shape(), g), g.order()));
            }
        }
        return out;
    }

    private static TextShape turned(TextShape s, Group g) {
        Frame f = s.frame();
        double rad = Math.toRadians(g.angle());
        double dx = f.x() + f.width() / 2 - g.pivotX();
        double dy = f.y() + f.height() / 2 - g.pivotY();
        double cx = g.pivotX() + dx * Math.cos(rad) - dy * Math.sin(rad);
        double cy = g.pivotY() + dx * Math.sin(rad) + dy * Math.cos(rad);
        Frame on = new Frame((float) cx - f.width() / 2, (float) cy - f.height() / 2, f.width(), f.height(),
                Math.floorMod(Math.round(g.angle()), 360));
        return new TextShape(on, s.paras(), s.insetLeft(), s.insetTop(), s.insetRight(), s.fillRgb(), s.lineRgb(),
                s.lineWidth(), s.radius(), s.wrap(), false);
    }

    private static Group group(List<Integer> members, List<Glyph> glyphs, List<PaintOrder.Slant> found) {
        float angle = 0;
        float px = 0;
        float py = 0;
        int order = -1;
        for (int i : members) {
            PaintOrder.Slant s = found.get(i);
            angle += s.angle();
            px += s.x();
            py += s.y();
            order = Math.max(order, s.order());
        }
        angle /= members.size();
        px /= members.size();
        py /= members.size();
        double rad = Math.toRadians(angle);
        List<Glyph> onPage = new ArrayList<>();
        List<Glyph> upright = new ArrayList<>();
        for (int i : members) {
            Glyph g = glyphs.get(i);
            double dx = g.x - px;
            double dy = g.baseline - py;
            float x = (float) (px + dx * Math.cos(rad) + dy * Math.sin(rad));
            float y = (float) (py - dx * Math.sin(rad) + dy * Math.cos(rad));
            onPage.add(g);
            upright.add(upright(g, x, y, found.get(i).advance()));
        }
        return new Group(angle, px, py, onPage, upright, order);
    }

    private static Glyph upright(Glyph g, float x, float baseline, float advance) {
        Glyph u = new Glyph(g.text, x, advance, baseline, g.size, g.ascent, g.descent, g.font, g.rgb, g.seq, g.spaceWidth,
                g.bold, g.italic);
        u.underline = g.underline;
        u.strike = g.strike;
        u.highlightRgb = g.highlightRgb;
        u.link = g.link;
        u.hscale = g.hscale;
        return u;
    }

    private static List<List<Integer>> bySlant(List<PaintOrder.Slant> found) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble(i -> found.get(i).angle()));
        List<List<Integer>> out = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        float first = 0;
        for (int i : order) {
            float a = found.get(i).angle();
            if (!current.isEmpty() && a - first > SAME_SLANT) {
                out.add(current);
                current = new ArrayList<>();
            }
            if (current.isEmpty()) {
                first = a;
            }
            current.add(i);
        }
        if (!current.isEmpty()) {
            out.add(current);
        }
        return out;
    }

    private static PaintOrder.Slant at(Map<Long, List<PaintOrder.Slant>> grid, Glyph g) {
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                List<PaintOrder.Slant> near = grid.get(cell(g.x + i * 2f, g.baseline + j * 2f));
                if (near == null) {
                    continue;
                }
                for (PaintOrder.Slant s : near) {
                    if (Math.abs(s.x() - g.x) <= MATCH && Math.abs(s.y() - g.baseline) <= MATCH) {
                        return s;
                    }
                }
            }
        }
        return null;
    }

    private static long cell(float x, float y) {
        return (((long) Math.floor(x / 2f) << 32) ^ ((long) Math.floor(y / 2f) & 0xFFFFFFFFL)) * 0x9E3779B97F4A7C15L;
    }
}
