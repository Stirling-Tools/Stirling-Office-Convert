package stirling.software.officeconvert.slides;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import stirling.software.officeconvert.build.PlacedContent;
import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.layout.Box;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.PageLayout;
import stirling.software.officeconvert.layout.ParaDraft;
import stirling.software.officeconvert.layout.Word;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph.Align;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.RunStyle;

final class TextFrames {

    private static final float MAX_OVERLAP = 1f;

    private static final float BLOCK_GAP = 2.5f;

    private static final float LOOK_CHANGE = 1.35f;

    private final PlacedContent content;
    private final float slideWidth;

    TextFrames(PlacedContent content, float slideWidth) {
        this.content = content;
        this.slideWidth = slideWidth;
    }

    private record Draft(ParaDraft d, Bullet bullet, Align align, float left, float right, float firstX, float restX,
            float size, float lineHeight, String font, int order, boolean hidden) {

        Draft aligned(Align a) {
            return new Draft(d, bullet, a, left, right, firstX, restX, size, lineHeight, font, order, hidden);
        }

        Draft withLineHeight(float h) {
            return new Draft(d, bullet, align, left, right, firstX, restX, size, h, font, order, hidden);
        }

        float firstBaseline() {
            return d.first().baseline;
        }

        float lastBaseline() {
            return d.last().baseline;
        }

        float boxTop() {
            return firstBaseline() - LineBoxes.baseline(lineHeight, size, font);
        }

        float boxBottom() {
            return lastBaseline() + lineHeight - LineBoxes.baseline(lineHeight, size, font);
        }
    }

    record Framed(TextShape shape, int order, List<Integer> drawn) {

        Framed(TextShape shape, int order) {
            this(shape, order, List.of());
        }
    }

    List<Framed> frames(List<ParaDraft> paras, List<SlideShape> icons, PaintOrder paint) throws IOException {
        List<Draft> drafts = new ArrayList<>();
        for (ParaDraft d : paras) {
            if (!d.lines.isEmpty() && d.chars() > 0) {
                drafts.add(draft(d, paint));
            }
        }
        fitLoneLines(drafts);
        List<Framed> out = new ArrayList<>();
        List<Draft> group = new ArrayList<>();
        for (Draft next : drafts) {
            if (!group.isEmpty() && startsBlock(group, next)) {
                out.add(new Framed(shape(group, icons), order(group)));
                group.clear();
            }
            group.add(next);
        }
        if (!group.isEmpty()) {
            out.add(new Framed(shape(group, icons), order(group)));
        }
        return out;
    }

    Framed boxed(PageLayout.TextBoxItem tb, List<SlideShape> icons, PaintOrder paint) throws IOException {
        List<Draft> drafts = new ArrayList<>();
        for (ParaDraft d : tb.paras()) {
            if (!d.lines.isEmpty() && d.chars() > 0) {
                drafts.add(draft(d, tb.turn() == null ? paint : null));
            }
        }
        Box box = tb.box();
        if (drafts.isEmpty()) {
            return null;
        }
        float textLeft = Math.min(tb.textLeft(), minLeft(drafts));
        float textRight = Math.max(tb.textRight(), maxRight(drafts));
        float textTop = drafts.getFirst().boxTop();
        float top = Math.min(box.top(), textTop);
        float bottom = Math.max(box.bottom(), drafts.getLast().boxBottom());
        float left = Math.min(box.x(), textLeft);
        float right = Math.max(box.right(), textRight);
        List<TextPara> paras = paras(drafts, textLeft, textRight, textTop, icons, tb.turn() == null);
        float insetLeft = textLeft - left;
        float insetRight = right - textRight;
        float insetTop = textTop - top;
        Frame frame;
        if (tb.turn() == null) {
            frame = new Frame(left, top, right - left, bottom - top);
        } else {
            Box on = tb.turn().onPage();
            float w = right - left;
            float h = bottom - top;
            frame = new Frame(on.centreX() - w / 2f, on.centreY() - h / 2f, w, h, rotation(tb.turn().direction()));
        }
        int fill = tb.fillRgb();
        int line = tb.lineRgb();
        float radius = 0;
        List<Integer> drawn = new ArrayList<>();
        if (fill >= 0 || line >= 0) {
            PaintOrder.Mark m = paint.drewMark(tb.box(), 2f, PaintOrder.Kind.FILL, PaintOrder.Kind.STROKE);
            if (m != null && m.outline() == PaintOrder.Outline.OTHER) {
                fill = -1;
                line = -1;
            } else {
                radius = m != null && m.outline() == PaintOrder.Outline.ROUNDED ? m.radius() : tb.rounded() ? 6f : 0;
                paint.drewAll(tb.box(), 2f, drawn);
            }
        }
        TextShape shape = new TextShape(frame, paras, insetLeft, insetTop, insetRight, fill, line, tb.lineWidth(), radius,
                wraps(drafts), false);
        int order = tb.turn() == null ? order(drafts) : paint.textOver(SlideBuilder.box(frame));
        return new Framed(shape, order, drawn);
    }

    private static boolean wraps(List<Draft> drafts) {
        for (Draft g : drafts) {
            for (int i = 1; i < g.d.lines.size(); i++) {
                if (!g.d.hardBreaks.get(i)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int order(List<Draft> drafts) {
        int order = -1;
        for (Draft g : drafts) {
            order = Math.max(order, g.order);
        }
        return order;
    }

    private static int rotation(int direction) {
        return switch (direction) {
            case 90 -> 270;
            case 270 -> 90;
            case 180 -> 180;
            default -> 0;
        };
    }

    private boolean startsBlock(List<Draft> group, Draft next) {
        Draft prev = group.getLast();
        if (prev.hidden != next.hidden) {
            return true;
        }
        float gap = next.boxTop() - prev.boxBottom();
        if (gap < -MAX_OVERLAP) {
            return true;
        }
        float size = Math.max(prev.size, next.size);
        if (gap > BLOCK_GAP * size) {
            return true;
        }
        float ratio = Math.max(prev.size, next.size) / Math.max(0.1f, Math.min(prev.size, next.size));
        if (ratio > LOOK_CHANGE && (prev.bullet == null || next.bullet == null)) {
            return true;
        }
        float left = minLeft(group);
        float right = maxRight(group);
        return next.left >= right || next.right <= left;
    }

    private Draft draft(ParaDraft d, PaintOrder paint) {
        Bullet bullet = Bullets.of(d);
        Line first = d.first();
        float left = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        for (Line l : d.lines) {
            left = Math.min(left, l.x);
            right = Math.max(right, l.right);
        }
        float firstX = bullet != null ? Bullets.textStart(d) : first.x;
        float restX = firstX;
        if (d.lines.size() >= 2) {
            restX = Float.MAX_VALUE;
            for (int i = 1; i < d.lines.size(); i++) {
                restX = Math.min(restX, d.lines.get(i).x);
            }
        }
        float size = firstLineSize(first);
        float pitch = d.pitch > 0 ? d.pitch : 1.2f * size;
        float lineHeight = LineBoxes.settle(Math.clamp(pitch, 0.7f * size, 3f * size), size);
        Align align = bullet != null && d.lines.size() == 1 ? Align.LEFT : d.align;
        int order = -1;
        boolean hidden = false;
        if (paint != null) {
            for (Line l : d.lines) {
                order = Math.max(order, paint.textOver(new Box(l.x, l.top, l.right, l.bottom)));
            }
            hidden = order >= 0 && paint.covered(new Box(left, d.top(), right, d.bottom()), order);
        }
        return new Draft(d, bullet, align, left, right, firstX, restX, size, lineHeight, fontOf(first), order, hidden);
    }

    private static float firstLineSize(Line l) {
        float max = 0;
        for (Word w : l.words) {
            for (Glyph g : w.glyphs) {
                if (g.vertAlign == 0 && !g.isSpace()) {
                    max = Math.max(max, g.size);
                }
            }
        }
        return max > 0 ? Math.round(max * 2f) / 2f : l.size;
    }

    private static String fontOf(Line l) {
        for (Word w : l.words) {
            RunStyle s = PlacedContent.style(w.first(), l.size);
            if (s.font() != null) {
                return s.font();
            }
        }
        return null;
    }

    private TextShape shape(List<Draft> drafts, List<SlideShape> icons) throws IOException {
        List<Draft> group = settleAlignment(drafts);
        float left = minLeft(group);
        float right = maxRight(group);
        float widest = right - left;
        float slack = Math.max(2f, 0.03f * widest);
        float room = wrapRoom(group) - right;
        if (room < Float.MAX_VALUE / 4 && group.size() == 1) {
            slack = Math.min(slack, Math.max(0.5f, 0.6f * room));
        }
        boolean allCentred = group.stream().allMatch(g -> g.align == Align.CENTER);
        boolean allRight = group.stream().allMatch(g -> g.align == Align.RIGHT);
        float boxLeft = allCentred ? left - slack / 2f : allRight ? left - slack : left;
        float boxRight = allCentred ? right + slack / 2f : allRight ? right : right + slack;
        if (!allCentred && !allRight && boxRight > slideWidth && right <= slideWidth) {
            boxRight = Math.max(right + 0.5f, slideWidth);
        }
        float top = group.getFirst().boxTop();
        List<TextPara> paras = paras(group, boxLeft, boxRight, top, icons, true);
        float bottom = group.getLast().boxBottom();
        return new TextShape(new Frame(boxLeft, top, boxRight - boxLeft, Math.max(1f, bottom - top)), paras, 0, 0, 0,
                -1, -1, 0, 0, wraps(group), false);
    }

    private List<TextPara> paras(List<Draft> group, float boxLeft, float boxRight, float top, List<SlideShape> icons,
            boolean placeIcons) throws IOException {
        List<TextPara> out = new ArrayList<>();
        Draft prev = null;
        float drift = 0;
        for (Draft g : group) {
            Paragraph runs = content.runs(g.d, g.bullet != null ? 1 : 0, boxLeft, boxRight);
            OfficeFonts.restore(runs, g.d);
            iconsOut(runs, g.d, placeIcons ? icons : null);
            float spaceBefore = 0;
            if (prev != null) {
                float want = g.boxTop() - prev.boxBottom() - drift;
                spaceBefore = Math.max(0, Math.round(want));
                drift = spaceBefore - want;
            }
            float marginLeft = 0;
            float marginRight = 0;
            float indent = 0;
            Align align = g.align;
            switch (align) {
                case CENTER -> {
                    float centre = 0;
                    for (Line l : g.d.lines) {
                        centre += l.centre();
                    }
                    centre /= g.d.lines.size();
                    float shift = 2 * centre - boxLeft - boxRight;
                    float spare = Math.max(0, boxRight - boxLeft - (g.right - g.left) - 1f);
                    shift = Math.clamp(shift, -spare, spare);
                    marginLeft = Math.max(0, shift);
                    marginRight = Math.max(0, -shift);
                }
                case RIGHT -> marginRight = Math.max(0, boxRight - g.right);
                default -> {
                    marginLeft = Math.max(0, g.restX - boxLeft);
                    float firstStart = g.bullet != null ? g.d.first().x : g.firstX;
                    indent = firstStart - boxLeft - marginLeft;
                    if (align == Align.JUSTIFY) {
                        marginRight = Math.max(0, boxRight - g.right);
                    } else {
                        marginRight = wrapMargin(g, boxRight);
                    }
                }
            }
            if (g.bullet != null && align != Align.LEFT && align != Align.JUSTIFY) {
                runs = content.runs(g.d, 0, boxLeft, boxRight);
                OfficeFonts.restore(runs, g.d);
                iconsOut(runs, g.d, null);
            }
            Bullet bullet = align == Align.LEFT || align == Align.JUSTIFY ? g.bullet : null;
            out.add(new TextPara(runs, align, marginLeft, indent, marginRight, g.lineHeight, spaceBefore,
                    g.firstBaseline() - top, g.lastBaseline() - top, g.size, bullet, linkLines(g.d, runs)));
            prev = g;
        }
        return out;
    }

    private static Set<Integer> linkLines(ParaDraft d, Paragraph runs) {
        Set<Integer> out = new HashSet<>();
        for (int i = 1; i < d.lines.size(); i++) {
            String link = d.lines.get(i).words.getFirst().first().link;
            String before = d.lines.get(i - 1).words.getLast().last().link;
            if (link == null || link.equals(before)) {
                continue;
            }
            for (int k = 0; k < runs.inlines.size(); k++) {
                if (runs.inlines.get(k) instanceof Inline.Text t && linksTo(t, link)) {
                    out.add(k);
                    break;
                }
            }
        }
        return out;
    }

    private static boolean linksTo(Inline.Text t, String link) {
        return link.startsWith("#page") ? link.equals("#page" + t.anchorPage()) : link.equals(t.link());
    }

    private static void iconsOut(Paragraph runs, ParaDraft d, List<SlideShape> icons) {
        List<Glyph> iconGlyphs = new ArrayList<>();
        for (Line l : d.lines) {
            for (Word w : l.words) {
                for (Glyph g : w.glyphs) {
                    if (g.icon != null) {
                        iconGlyphs.add(g);
                    }
                }
            }
        }
        int next = 0;
        List<Inline> kept = new ArrayList<>();
        for (int i = 0; i < runs.inlines.size(); i++) {
            if (!(runs.inlines.get(i) instanceof Inline.Image image)) {
                kept.add(runs.inlines.get(i));
                continue;
            }
            Picture pic = image.picture();
            Glyph g = next < iconGlyphs.size() ? iconGlyphs.get(next++) : null;
            if (g != null && icons != null) {
                float y = g.baseline - pic.height - pic.baselineShift;
                icons.add(new PictureShape(new Frame(g.x, y, pic.width, pic.height), pic));
            }
            RunStyle style = neighbourStyle(runs.inlines, i);
            if (style != null) {
                int spaces = Math.max(1, Math.round(pic.width / Math.max(0.5f, 0.25f * style.size())));
                kept.add(new Inline.Text("\u00a0".repeat(spaces), style, null, -1));
            }
        }
        runs.inlines.clear();
        runs.inlines.addAll(kept);
    }

    private static RunStyle neighbourStyle(List<Inline> inlines, int at) {
        for (int k = 1; k < inlines.size(); k++) {
            for (int j : new int[] {at + k, at - k}) {
                if (j >= 0 && j < inlines.size() && inlines.get(j) instanceof Inline.Text t) {
                    return t.style();
                }
            }
        }
        return null;
    }

    private static void fitLoneLines(List<Draft> drafts) {
        for (int i = 0; i < drafts.size(); i++) {
            Draft g = drafts.get(i);
            if (g.d.lines.size() != 1) {
                continue;
            }
            float best = g.lineHeight;
            if (i > 0) {
                best = closer(best, g.firstBaseline() - drafts.get(i - 1).lastBaseline(), g.size);
            }
            if (i + 1 < drafts.size()) {
                best = closer(best, drafts.get(i + 1).firstBaseline() - g.lastBaseline(), g.size);
            }
            if (best != g.lineHeight) {
                drafts.set(i, g.withLineHeight(LineBoxes.settle(best, g.size)));
            }
        }
    }

    private static float closer(float current, float pitch, float size) {
        return pitch >= 0.85f * size && pitch < current ? pitch : current;
    }

    private static List<Draft> settleAlignment(List<Draft> group) {
        List<Draft> out = new ArrayList<>(group.size());
        for (Draft g : group) {
            Draft settled = g;
            if (g.d.lines.size() == 1 && (g.align == Align.CENTER || g.align == Align.RIGHT)) {
                for (Draft o : group) {
                    boolean leftSet = o.align == Align.LEFT || o.align == Align.JUSTIFY || o.d.lines.size() > 1;
                    if (o != g && leftSet && (Math.abs(o.left - g.left) < 2f || Math.abs(o.restX - g.left) < 2f)) {
                        settled = g.aligned(Align.LEFT);
                        break;
                    }
                }
            }
            out.add(settled);
        }
        return out;
    }

    private static float wrapRoom(List<Draft> group) {
        float room = Float.MAX_VALUE;
        for (Draft g : group) {
            List<Line> lines = g.d.lines;
            for (int i = 0; i + 1 < lines.size(); i++) {
                if (g.d.hardBreaks.get(i + 1)) {
                    continue;
                }
                Line l = lines.get(i);
                Word nextWord = lines.get(i + 1).words.getFirst();
                room = Math.min(room, l.right + spaceWidth(l) + breakablePart(nextWord));
            }
        }
        return room;
    }

    private static float wrapMargin(Draft g, float boxRight) {
        float room = wrapRoom(List.of(g));
        if (room >= boxRight) {
            return 0;
        }
        float edge = Math.min(g.right + Math.max(1f, 0.5f * (room - g.right)), room - 0.5f);
        return Math.max(0, boxRight - Math.max(edge, g.right + 0.25f));
    }

    private static float breakablePart(Word w) {
        for (int i = 0; i + 1 < w.glyphs.size(); i++) {
            String t = w.glyphs.get(i).text;
            if (t.equals("-") || t.equals("\u2010") || t.equals("\u2013") || t.equals("\u2014")) {
                return w.glyphs.get(i).right() - w.x;
            }
        }
        return w.width();
    }

    private static float spaceWidth(Line l) {
        if (!Float.isNaN(l.drawnSpace)) {
            return l.drawnSpace;
        }
        Glyph g = l.words.getLast().last();
        return g.spaceWidth > 0 ? g.spaceWidth : 0.25f * l.size;
    }

    private static float minLeft(List<Draft> drafts) {
        float left = Float.MAX_VALUE;
        for (Draft g : drafts) {
            left = Math.min(left, g.left);
        }
        return left;
    }

    private static float maxRight(List<Draft> drafts) {
        float right = -Float.MAX_VALUE;
        for (Draft g : drafts) {
            right = Math.max(right, g.right);
        }
        return right;
    }
}
