package stirling.software.officeconvert.topdf.html;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jsoup.nodes.Element;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;

final class Positioned {

    record Item(Element element, Map<String, String> declared, Look look) {}

    record Placed(List<Inline> drawings, boolean fromBottom, float width, float height) {}

    private enum Visit {
        TAKE,
        SKIP,
        DESCEND
    }

    private Positioned() {}

    static String position(Map<String, String> d) {
        return d.getOrDefault("position", "static").strip().toLowerCase(Locale.ROOT);
    }

    static boolean outOfFlow(Map<String, String> d) {
        String p = position(d);
        return p.equals("absolute") || p.equals("fixed");
    }

    static boolean contains(Map<String, String> d) {
        String p = position(d);
        if (p.equals("relative") || p.equals("absolute") || p.equals("fixed") || p.equals("sticky")) {
            return true;
        }
        String t = d.get("transform");
        return t != null && !t.strip().equalsIgnoreCase("none") && !t.isBlank();
    }

    static boolean placedVertically(Map<String, String> d) {
        return set(d.get("top")) || set(d.get("bottom"));
    }

    private static boolean set(String v) {
        return v != null && !v.isBlank() && !v.strip().equalsIgnoreCase("auto");
    }

    static List<Item> absolutes(HtmlContext ctx, Element e, Look look, float width) {
        return walk(ctx, e, look, width, d -> {
            String p = position(d);
            if (p.equals("absolute")) {
                return placedVertically(d) ? Visit.TAKE : Visit.SKIP;
            }
            return p.equals("fixed") || contains(d) ? Visit.SKIP : Visit.DESCEND;
        });
    }

    static List<Item> fixed(HtmlContext ctx, Element root, Look look, float width) {
        return walk(ctx, root, look, width, d -> position(d).equals("fixed") ? Visit.TAKE : Visit.DESCEND);
    }

    private interface Rule {
        Visit visit(Map<String, String> d);
    }

    private record Step(Element element, Look parent) {}

    private static List<Item> walk(HtmlContext ctx, Element root, Look look, float width, Rule rule) {
        List<Item> out = new ArrayList<>();
        Deque<Step> stack = new ArrayDeque<>();
        push(stack, root, look);
        while (!stack.isEmpty()) {
            Step s = stack.pop();
            Element c = s.element();
            if (!ctx.visit() || Flow.SKIPPED.contains(c.normalName()) || c.hasAttr("hidden")) {
                continue;
            }
            Map<String, String> d = ctx.declared(c, s.parent());
            if (d.getOrDefault("display", "").strip().equalsIgnoreCase("none")) {
                continue;
            }
            Look l = s.parent().child(d, width, false);
            if (l.hidden || Hidden.visually(d, l.size)) {
                continue;
            }
            switch (rule.visit(d)) {
                case TAKE -> out.add(new Item(c, d, l));
                case DESCEND -> push(stack, c, l);
                default -> {
                }
            }
        }
        return out;
    }

    private static void push(Deque<Step> stack, Element parent, Look look) {
        List<Element> kids = parent.children();
        for (int i = kids.size() - 1; i >= 0; i--) {
            stack.push(new Step(kids.get(i), look));
        }
    }

    static Placed lay(HtmlContext ctx, Item item, float cbWidth, float cbHeight, boolean inFlow, int depth)
            throws IOException {
        ctx.count();
        Element e = item.element();
        Map<String, String> d = item.declared();
        Look look = item.look();
        float em = look.size;
        boolean knownHeight = !Float.isNaN(cbHeight);
        Float left = inFlow ? null : offset(d.get("left"), em, cbWidth);
        Float right = inFlow ? null : offset(d.get("right"), em, cbWidth);
        Float top = inFlow ? null : offset(d.get("top"), em, knownHeight ? cbHeight : 0);
        Float bottom = inFlow ? null : offset(d.get("bottom"), em, knownHeight ? cbHeight : 0);
        if (top != null && !knownHeight && d.get("top").contains("%")) {
            top = 0f;
        }
        float mL = margin(d.get("margin-left"), em, cbWidth);
        float mR = margin(d.get("margin-right"), em, cbWidth);
        float mT = inFlow ? 0 : margin(d.get("margin-top"), em, cbWidth);
        float mB = inFlow ? 0 : margin(d.get("margin-bottom"), em, cbWidth);
        float bL = Flow.border(d, "left");
        float bR = Flow.border(d, "right");
        float bT = Flow.border(d, "top");
        float bB = Flow.border(d, "bottom");
        float pL = pad(d.get("padding-left"), em, cbWidth);
        float pR = pad(d.get("padding-right"), em, cbWidth);
        float pT = pad(d.get("padding-top"), em, cbWidth);
        float pB = pad(d.get("padding-bottom"), em, cbWidth);
        boolean borderBox = "border-box".equalsIgnoreCase(d.getOrDefault("box-sizing", "").strip());
        float sidesX = pL + pR + bL + bR;
        float sidesY = pT + pB + bT + bB;
        Float cssW = size(d.get("width"), em, cbWidth);
        Float cssH = size(d.get("height"), em, knownHeight ? cbHeight : -1);
        if (cssW != null && borderBox) {
            cssW = Math.max(0, cssW - sidesX);
        }
        if (cssH != null && borderBox) {
            cssH = Math.max(0, cssH - sidesY);
        }
        String tag = e.normalName();
        List<Inline> drawings = new ArrayList<>();
        if (tag.equals("img") || tag.equals("svg")) {
            return picture(ctx, e, d, look, cbWidth, cbHeight, left, right, top, bottom, mL, mR, mT, mB, inFlow);
        }
        float room;
        if (cssW != null) {
            room = cssW;
        } else if (inFlow || left != null && right != null) {
            room = cbWidth - (left == null ? 0 : left) - (right == null ? 0 : right) - mL - mR - sidesX;
        } else {
            room = cbWidth - (left != null ? left : right != null ? right : 0) - sidesX;
        }
        room = Math.max(12, room);
        List<Item> nested = ctx.positions() ? absolutes(ctx, e, look, room) : List.of();
        for (Item n : nested) {
            ctx.place(n.element());
        }
        List<Paragraph> paragraphs = new ArrayList<>();
        Flow inner = new Flow(ctx, b -> paragraphs.add((Paragraph) b), true);
        inner.children(e, look, new Flow.Frame(0, 0, room, -1), depth);
        float contentW = cssW != null || inFlow || left != null && right != null ? room
                : Math.min(room, Measure.unwrapped(paragraphs) * 1.05f + 2);
        float contentH;
        if (cssH != null) {
            contentH = cssH;
        } else if (top != null && bottom != null && knownHeight) {
            contentH = Math.max(0, cbHeight - top - bottom - mT - mB - sidesY);
        } else {
            contentH = Measure.height(paragraphs, contentW);
        }
        float w = contentW + sidesX;
        float h = contentH + sidesY;
        float x = left != null ? left + mL : right != null ? cbWidth - right - mR - w : mL;
        boolean fromBottom = false;
        float y;
        if (top != null) {
            y = top + mT;
        } else if (bottom != null && knownHeight) {
            y = cbHeight - bottom - mB - h;
        } else if (bottom != null) {
            y = -(bottom + mB + h);
            fromBottom = true;
        } else {
            y = mT;
        }
        Transform t = Transform.of(d.get("transform"), em, w, h);
        float sw = w * t.scaleX();
        float sh = h * t.scaleY();
        x += (w - sw) / 2 + t.dx();
        y += (h - sh) / 2 + t.dy();
        float k = (t.scaleX() + t.scaleY()) / 2;
        if (k != 1) {
            scaleText(paragraphs, k);
        }
        Integer fillColor = Css.color(d.get("background-color"));
        int fill = fillColor == null ? -1 : fillColor;
        boolean rounded = rounded(d.get("border-radius"), em);
        boolean even = bT > 0 && bT == bB && bT == bL && bT == bR;
        int line = even ? borderColor(d, "top", look) : -1;
        if (t.rotation() == 0) {
            drawings.addAll(Backgrounds.paint(d, look, ctx.images(), x, y, sw, sh, true));
            if (!even) {
                sides(drawings, d, look, x, y, sw, sh, bT * k, bB * k, bL * k, bR * k);
            }
        }
        boolean empty = paragraphs.isEmpty() && nested.isEmpty();
        if (empty && t.rotation() == 0) {
            if (fill >= 0 || even) {
                drawings.add(0, new Inline.Shape(x, y, sw, sh, fill, line, even ? bT * k : 0, rounded, ellipse(d),
                        true, 0));
            }
            return new Placed(drawings, fromBottom, sw, sh);
        }
        if (!paragraphs.isEmpty()) {
            paragraphs.get(0).spaceBefore += (pT + bT) * k;
        }
        if (!nested.isEmpty()) {
            Hung hung = hang(ctx, nested, -pL, 0, contentW + pL + pR, contentH + pT + pB, depth);
            if (paragraphs.isEmpty()) {
                paragraphs.add(Flow.anchorParagraph(List.of(), 0, look.run()));
            }
            paragraphs.get(0).inlines.addAll(0, hung.top());
            if (!hung.bottom().isEmpty()) {
                paragraphs.add(Flow.anchorParagraph(moved(hung.bottom(), 0, pB, true), 0, look.run()));
            }
        }
        boolean grow = cssH == null && t.rotation() == 0 && !(top != null && bottom != null && knownHeight);
        drawings.add(new Inline.TextBox(x, y, sw, sh, fill, (pL + bL) * k, (pR + bR) * k, 0, paragraphs, line,
                even ? bT * k : 0, rounded, true, 0, -1, false, t.rotation(), true, grow));
        return new Placed(drawings, fromBottom, sw, sh);
    }

    private static Placed picture(HtmlContext ctx, Element e, Map<String, String> d, Look look, float cbWidth,
            float cbHeight, Float left, Float right, Float top, Float bottom, float mL, float mR, float mT, float mB,
            boolean inFlow) throws IOException {
        Picture pic = e.normalName().equals("svg") ? ctx.images().inlineSvg(e, d, look, cbWidth, ctx.pageRoom())
                : ctx.images().picture(e, d, look, cbWidth, ctx.pageRoom());
        if (pic == null) {
            return new Placed(List.of(), false, 0, 0);
        }
        boolean knownHeight = !Float.isNaN(cbHeight);
        float w = pic.width;
        float h = pic.height;
        float x = left != null ? left + mL : right != null ? cbWidth - right - mR - w : mL;
        boolean fromBottom = false;
        float y;
        if (top != null) {
            y = top + mT;
        } else if (bottom != null && knownHeight) {
            y = cbHeight - bottom - mB - h;
        } else if (bottom != null) {
            y = -(bottom + mB + h);
            fromBottom = true;
        } else {
            y = inFlow ? 0 : mT;
        }
        Transform t = Transform.of(d.get("transform"), look.size, w, h);
        float sw = w * t.scaleX();
        float sh = h * t.scaleY();
        x += (w - sw) / 2 + t.dx();
        y += (h - sh) / 2 + t.dy();
        pic.width = sw;
        pic.height = sh;
        pic.rotation = Math.round(t.rotation());
        pic.wrap = Picture.Wrap.NONE;
        pic.fromParagraph = true;
        boolean quarter = Math.floorMod(pic.rotation, 180) == 90;
        pic.x = quarter ? x - (sh - sw) / 2f : x;
        pic.y = quarter ? y - (sw - sh) / 2f : y;
        return new Placed(List.of(new Inline.Image(pic)), fromBottom, sw, sh);
    }

    record Hung(List<Inline> top, List<Inline> bottom) {}

    static Hung hang(HtmlContext ctx, List<Item> items, float x0, float y0, float cbWidth, float cbHeight, int depth)
            throws IOException {
        List<Inline> top = new ArrayList<>();
        List<Inline> bottom = new ArrayList<>();
        for (Item item : items) {
            if (ctx.stopped()) {
                break;
            }
            Placed p = lay(ctx, item, cbWidth, cbHeight, false, depth + 1);
            if (p.fromBottom()) {
                bottom.addAll(moved(p.drawings(), x0, 0, true));
            } else {
                top.addAll(moved(p.drawings(), x0, y0, true));
            }
        }
        return new Hung(top, bottom);
    }

    static List<Inline> moved(List<Inline> drawings, float dx, float dy, boolean fromParagraph) {
        List<Inline> out = new ArrayList<>(drawings.size());
        for (Inline in : drawings) {
            switch (in) {
                case Inline.TextBox b -> out.add(new Inline.TextBox(b.x() + dx, b.y() + dy, b.width(), b.height(),
                        b.fillRgb(), b.insetLeft(), b.insetRight(), b.wrapGap(), b.paragraphs(), b.lineRgb(),
                        b.lineWidth(), b.rounded(), b.overlay(), b.direction(), b.groundRgb(), b.upright(), b.rotation(),
                        fromParagraph, b.grow()));
                case Inline.Shape s -> out.add(new Inline.Shape(s.x() + dx, s.y() + dy, s.width(), s.height(), s.rgb(),
                        s.lineRgb(), s.lineWidth(), s.rounded(), s.ellipse(), fromParagraph, s.z()));
                case Inline.Image img -> {
                    Picture p = img.picture();
                    p.x += dx;
                    p.y += dy;
                    p.fromParagraph = fromParagraph;
                    out.add(img);
                }
                default -> out.add(in);
            }
        }
        return out;
    }

    private static void sides(List<Inline> into, Map<String, String> d, Look look, float x, float y, float w, float h,
            float top, float bottom, float left, float right) {
        if (top > 0) {
            into.add(new Inline.Shape(x, y, w, top, borderColor(d, "top", look), -1, 0, false, false, true, 0));
        }
        if (bottom > 0) {
            into.add(new Inline.Shape(x, y + h - bottom, w, bottom, borderColor(d, "bottom", look), -1, 0, false, false,
                    true, 0));
        }
        if (left > 0) {
            into.add(new Inline.Shape(x, y, left, h, borderColor(d, "left", look), -1, 0, false, false, true, 0));
        }
        if (right > 0) {
            into.add(new Inline.Shape(x + w - right, y, right, h, borderColor(d, "right", look), -1, 0, false, false,
                    true, 0));
        }
    }

    private static int borderColor(Map<String, String> d, String side, Look look) {
        String v = d.get("border-" + side + "-color");
        if (v == null) {
            String all = d.get("border-" + side);
            if (all != null) {
                for (String token : Css.tokens(all)) {
                    if (Css.color(token) != null) {
                        v = token;
                    }
                }
            }
        }
        Integer c = Css.color(v);
        return c == null || c < 0 ? look.color : c;
    }

    private static boolean rounded(String v, float em) {
        if (v == null) {
            return false;
        }
        List<String> t = Css.tokens(v);
        Float r = t.isEmpty() ? null : Css.length(t.get(0), em, 100);
        return r != null && r >= 2;
    }

    private static boolean ellipse(Map<String, String> d) {
        String v = d.getOrDefault("border-radius", "").strip();
        return v.equals("50%") || v.equals("100%");
    }

    private static void scaleText(List<Paragraph> paragraphs, float k) {
        for (Paragraph p : paragraphs) {
            for (int i = 0; i < p.inlines.size(); i++) {
                if (p.inlines.get(i) instanceof Inline.Text t) {
                    p.inlines.set(i, new Inline.Text(t.text(), t.style().withSize(Math.max(1, t.style().size() * k)),
                            t.link(), t.anchorPage()));
                }
            }
            if (p.lineRule != Paragraph.LineRule.AUTO) {
                p.lineHeight *= k;
            }
            p.spaceBefore *= k;
            p.indentLeft *= k;
            p.indentRight *= k;
        }
    }

    private static Float offset(String v, float em, float base) {
        if (!set(v)) {
            return null;
        }
        return Css.length(v, em, base);
    }

    private static float margin(String v, float em, float base) {
        if (!set(v)) {
            return 0;
        }
        Float pt = Css.length(v, em, base);
        return pt == null ? 0 : pt;
    }

    private static float pad(String v, float em, float base) {
        Float pt = v == null ? null : Css.length(v, em, base);
        return pt == null ? 0 : Math.max(0, pt);
    }

    private static Float size(String v, float em, float base) {
        if (!set(v) || v.contains("%") && base < 0) {
            return null;
        }
        Float pt = Css.length(v, em, base);
        return pt == null || pt < 0 ? null : pt;
    }
}
