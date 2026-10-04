package stirling.software.officeconvert.topdf.docx;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

final class FrameFlow {

    private final Ctx ctx;

    FrameFlow(Ctx ctx) {
        this.ctx = ctx;
    }

    static boolean framed(Para p) {
        XEl f = p.pp.framePr;
        if (f == null) {
            return false;
        }
        String drop = f.attr("dropCap");
        if (drop != null && !drop.equals("none")) {
            return true;
        }
        boolean placed = f.attr("x") != null || f.attr("y") != null || f.attr("xAlign") != null
                || f.attr("yAlign") != null;
        boolean anchored = !"text".equals(f.attr("hAnchor", "text")) || !"text".equals(f.attr("vAnchor", "text"));
        return placed || anchored;
    }

    // A frame in a header or footer, such as a page number or a logo, takes no room in the stack
    static boolean floats(Para p) {
        return framed(p) && "none".equals(p.pp.framePr.attr("dropCap", "none"));
    }

    int place(List<Block> blocks, int from, StackLayout stack) {
        Para first = (Para) blocks.get(from);
        List<Block> group = new ArrayList<>();
        group.add(first);
        int i = from + 1;
        while (i < blocks.size() && blocks.get(i) instanceof Para p && sameFrame(first, p)) {
            group.add(p);
            i++;
        }
        XEl f = first.pp.framePr;
        SectionProps s = stack.page();
        boolean page = "page".equals(f.attr("hAnchor", "text"));
        float area = page ? s.pageW : stack.width();
        float width = Ooxml.twips(f.attr("w"), 0);
        if (width <= 1) {
            width = Math.min(area, natural(group) + 1);
        }
        StackLayout.Result r = StackLayout.layout(group, width, ctx);
        frameRelative(r.anchors());
        float height = r.height();
        Float h = Ooxml.twips(f.attr("h"));
        if (h != null && h > 1 && ("exact".equals(f.attr("hRule")) || height < h)) {
            height = h;
        }
        float x = (page ? 0 : stack.pageLeft()) + align(f.attr("xAlign"), area - width, f.attr("x"));
        String vAnchor = f.attr("vAnchor", "text");
        if (!vAnchor.equals("text")) {
            boolean onPage = vAnchor.equals("page");
            float top = onPage ? 0 : s.top;
            float areaH = onPage ? s.pageH : s.pageH - s.top - s.bottom;
            float y = top + align(f.attr("yAlign"), areaH - height, f.attr("y"));
            stack.placeOnPage(r.ops(), x, y);
            stack.anchorOnPage(r.anchors(), x, y);
            return i;
        }
        x -= stack.pageLeft();
        float y = stack.y + Ooxml.twips(f.attr("y"), 0);
        boolean wrapped = i < blocks.size() && !(blocks.get(i) instanceof Para next && next.empty());
        Strip strip = new Strip();
        strip.height = height;
        strip.ops.addAll(r.ops());
        for (Inline.NoteRef n : r.notes()) {
            strip.note(n);
        }
        // Objects anchored in the frame's paragraphs are drawn too
        r.anchors().forEach(strip::anchor);
        stack.placeFloating(strip, x, y);
        if (wrapped) {
            // Text after the frame would wrap around it; this stack cannot wrap, so the text goes below
            stack.y = Math.max(stack.y, y + height);
        }
        return i;
    }

    // Word places an object anchored in a frame at page offsets measured from the frame itself
    private static void frameRelative(List<Strip.Anchor> anchors) {
        for (Strip.Anchor a : anchors) {
            Drawing d = a.drawing();
            if (d.inline) {
                continue;
            }
            if ("page".equals(d.hRel) && d.hAlign == null && d.hPct == null) {
                d.hRel = "character";
            }
            if ("page".equals(d.vRel) && d.vAlign == null && d.vPct == null) {
                d.vRel = "paragraph";
            }
        }
    }

    private static float align(String how, float room, String offset) {
        if (how == null || how.equals("inline")) {
            return Ooxml.twips(offset, 0);
        }
        return switch (how) {
            case "center" -> room / 2;
            case "right", "outside", "bottom" -> room;
            default -> 0;
        };
    }

    static boolean sameFrame(Para a, Para b) {
        XEl x = a.pp.framePr;
        XEl y = b.pp.framePr;
        if (x == null || y == null) {
            return false;
        }
        for (String k : new String[] {"x", "y", "w", "h", "hAnchor", "vAnchor", "xAlign", "yAlign", "dropCap", "wrap"}) {
            if (!java.util.Objects.equals(x.attr(k), y.attr(k))) {
                return false;
            }
        }
        return true;
    }

    int place(List<Block> blocks, int from, PageFlow pf) {
        Para first = (Para) blocks.get(from);
        List<Block> group = new ArrayList<>();
        group.add(first);
        int i = from + 1;
        while (i < blocks.size() && blocks.get(i) instanceof Para p && sameFrame(first, p)) {
            group.add(p);
            i++;
        }
        XEl f = first.pp.framePr;
        String drop = f.attr("dropCap", "none");
        boolean dropCap = !drop.equals("none");
        float width = Ooxml.twips(f.attr("w"), 0);
        if (width <= 1) {
            width = dropCap ? natural(group) + 1 : pf.width();
        }
        StackLayout.Result r = StackLayout.layout(group, width, ctx);
        frameRelative(r.anchors());
        float height = r.height();
        Float h = Ooxml.twips(f.attr("h"));
        if (h != null && h > 1 && ("exact".equals(f.attr("hRule")) || height < h)) {
            height = h;
        }
        float[] origin = pf.floatingOrigin(f.attr("hAnchor", dropCap ? "text" : "text"), f.attr("vAnchor", "text"));
        SectionProps s = pf.section();
        String hAnchor = f.attr("hAnchor", "text");
        float areaW = "page".equals(hAnchor) ? s.pageW : "margin".equals(hAnchor) ? s.textWidth() : pf.width();
        float x = origin[0];
        if (drop.equals("margin")) {
            x -= width + Ooxml.twips(f.attr("hSpace"), 0);
        } else if (f.attr("xAlign") != null) {
            x += switch (f.attr("xAlign")) {
                case "center" -> (areaW - width) / 2;
                case "right", "outside" -> areaW - width;
                default -> 0;
            };
        } else {
            x += Ooxml.twips(f.attr("x"), 0);
        }
        String vAnchor = f.attr("vAnchor", "text");
        float areaH = "page".equals(vAnchor) ? s.pageH : s.pageH - s.top - s.bottom;
        float y = origin[1];
        if (f.attr("yAlign") != null && !"inline".equals(f.attr("yAlign"))) {
            y += switch (f.attr("yAlign")) {
                case "center" -> (areaH - height) / 2;
                case "bottom", "outside" -> areaH - height;
                default -> 0;
            };
        } else if (!dropCap) {
            y += Ooxml.twips(f.attr("y"), 0);
        }
        Strip strip = new Strip();
        strip.height = height;
        strip.ops.addAll(r.ops());
        for (Inline.NoteRef n : r.notes()) {
            strip.note(n);
        }
        r.anchors().forEach(strip::anchor);
        pf.placeFixed(strip, x, y);
        String wrap = f.attr("wrap", "around");
        if (!wrap.equals("none") && !wrap.equals("through")) {
            float hs = Ooxml.twips(f.attr("hSpace"), dropCap ? 0 : 9);
            float vs = Ooxml.twips(f.attr("vSpace"), 0);
            Rectangle2D.Float box = new Rectangle2D.Float(x - hs, y - vs, width + 2 * hs, height + 2 * vs);
            if (wrap.equals("notBeside") || wrap.equals("topAndBottom")) {
                pf.excludeBand(box);
            } else {
                pf.exclude(box);
            }
        }
        if (!dropCap && "text".equals(vAnchor) && f.attr("y") == null && f.attr("yAlign") == null) {
            pf.y = Math.max(pf.y, y + height);
        }
        return i;
    }

    private float natural(List<Block> group) {
        float max = 0;
        for (Block b : group) {
            if (b instanceof Para p) {
                ParaItems pi = new ParaItems(ctx, p, null);
                float w = 0;
                for (Item it : pi.items) {
                    w += it.width(0, it.length());
                }
                max = Math.max(max, w);
            }
        }
        return max;
    }
}
