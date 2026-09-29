package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.topdf.io.DecodedPicture;
import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.Stroke;

final class DrawingPainter {

    private static final Color PLACEHOLDER = new Color(0xD9D9D9);

    private DrawingPainter() {}

    static void paint(Drawing d, float x, float y, List<Op> ops, Ctx ctx) {
        if (d.graphic == null || d.width <= 0 && d.height <= 0) {
            return;
        }
        graphic(d.graphic, x, y, d.width, d.height, ops, ctx, 0);
    }

    private static void graphic(Drawing.Graphic g, float x, float y, float w, float h, List<Op> ops, Ctx ctx,
            int depth) {
        if (depth > 16) {
            return;
        }
        switch (g) {
            case Drawing.Picture p -> {
                DecodedPicture pic = ctx.picture(p.part());
                if (pic != null && p.shadow() != null && w > 0 && h > 0 && (p.outline() != null || opaque(p))) {
                    List<Op> inner = new ArrayList<>();
                    float grow = p.outline() == null ? 0 : p.outline().width() / 2;
                    ShapeShadow.soft(x - grow, y - grow, w + 2 * grow, h + 2 * grow, p.shadow(), inner);
                    rotated(ops, inner, x, y, w, h, p.rotation(), false, false);
                }
                if (pic != null && w > 0 && h > 0) {
                    ops.add(new Op.Image(pic, x, y, w, h, p.crop(), p.rotation(), p.flipH(), p.flipV(), p.alpha()));
                }
                if (p.outline() != null && w > 0 && h > 0) {
                    List<Op> inner = new ArrayList<>();
                    inner.add(new Op.Rect(x, y, w, h, null, p.outline()));
                    rotated(ops, inner, x, y, w, h, p.rotation(), false, false);
                }
            }
            case Drawing.Shape s -> {
                List<Op> inner = new ArrayList<>();
                boolean line = Geometry.isLine(s.geometry());
                Shape outline = Geometry.shape(s.geometry(), s.geom(), x, y, w, h, line && s.flipH(), line && s.flipV());
                Fill fill = line || s.fill() == null ? null : ShapeFills.fill(s, x, y, w, h);
                if (fill != null || s.line() != null) {
                    inner.add(new Op.Path(line ? ShapePath.trimmed(outline, s.ends(), s.line()) : outline, fill,
                            s.line()));
                }
                ShapePath.arrows(outline, s.ends(), s.line(), inner);
                if (s.text() != null && !s.text().blocks().isEmpty()) {
                    textBox(s.text(), x, y, w, h, inner, ctx);
                }
                rotated(ops, inner, x, y, w, h, s.rotation(), !line && s.flipH(), !line && s.flipV());
            }
            case Drawing.Group grp -> {
                List<Op> inner = new ArrayList<>();
                float kx = grp.baseW() > 0 ? w / grp.baseW() : 1;
                float ky = grp.baseH() > 0 ? h / grp.baseH() : 1;
                for (Drawing.Child c : grp.children()) {
                    graphic(c.graphic(), x + c.x() * kx, y + c.y() * ky, c.w() * kx, c.h() * ky, inner, ctx,
                            depth + 1);
                }
                rotated(ops, inner, x, y, w, h, grp.rotation(), grp.flipH(), grp.flipV());
            }
            case Drawing.ChartGraphic c -> {
                List<Op> chart = ctx.chart(c.chart(), w, h);
                if (!chart.isEmpty()) {
                    ops.add(new Op.Group(x, y, null, null, chart));
                }
            }
            case Drawing.MathGraphic m -> ops.add(new Op.Group(x, y + m.box().ascent, null, null, m.box().ops));
            case Drawing.WordArt a -> {
                List<Op> inner = new ArrayList<>();
                ShapeText.wordArt(a, x, y, w, h, inner, ctx);
                rotated(ops, inner, x, y, w, h, a.rotation(), a.flipH(), a.flipV());
            }
            case Drawing.Placeholder ph -> {
                if (w > 0 && h > 0) {
                    ops.add(new Op.Rect(x, y, w, h, null, Stroke.solid(0.5f, PLACEHOLDER)));
                }
            }
        }
    }

    // A shadow is cast by the picture's own outline only where the picture cannot be see-through
    private static boolean opaque(Drawing.Picture p) {
        String name = p.part().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    private static void rotated(List<Op> ops, List<Op> inner, float x, float y, float w, float h, float rotation,
            boolean flipH, boolean flipV) {
        if (inner.isEmpty()) {
            return;
        }
        if (rotation == 0 && !flipH && !flipV) {
            ops.addAll(inner);
            return;
        }
        double cx = x + w / 2.0;
        double cy = y + h / 2.0;
        AffineTransform t = new AffineTransform();
        t.translate(cx, cy);
        t.rotate(Math.toRadians(rotation));
        t.scale(flipH ? -1 : 1, flipV ? -1 : 1);
        t.translate(-cx, -cy);
        ops.add(new Op.Group(0, 0, t, null, inner));
    }

    private static void textBox(Drawing.TextBox tb, float x, float y, float w, float h, List<Op> ops, Ctx ctx) {
        if (ctx.depth > 8) {
            return;
        }
        if (tb.vertical()) {
            vertical(tb, x, y, w, h, ops, ctx);
            return;
        }
        float inner = tb.noWrap() ? 10_000 : Math.max(1, w - tb.left() - tb.right());
        ctx.depth++;
        StackLayout.Result r;
        try {
            r = StackLayout.layout(tb.blocks(), inner, ctx);
        } finally {
            ctx.depth--;
        }
        float top = y + tb.top();
        float avail = h - tb.top() - tb.bottom();
        if ("ctr".equals(tb.anchor())) {
            top = y + tb.top() + (avail - r.height()) / 2;
        } else if ("b".equals(tb.anchor())) {
            top = y + h - tb.bottom() - r.height();
        }
        int textAt = ops.size();
        ops.add(new Op.Group(x + tb.left(), top, null, null, r.ops()));
        nested(r, x + tb.left(), top, inner, Math.max(1, avail), ops, textAt, ctx);
    }

    // Objects anchored inside a text box are placed against the box's text area
    private static void nested(StackLayout.Result r, float x, float y, float w, float h, List<Op> ops, int textAt,
            Ctx ctx) {
        if (r.anchors().isEmpty() || ctx.depth > 8) {
            return;
        }
        FloatLayout.Frame frame = new FloatLayout.Frame(w, h, 0, 0, 0, 0, 0, w);
        ctx.depth++;
        try {
            for (Strip.Anchor a : r.anchors()) {
                Drawing d = a.drawing();
                java.awt.geom.Rectangle2D.Float box = FloatLayout.position(d, frame, a.paraX(), a.paraTop());
                List<Op> inner = new ArrayList<>();
                paint(d, x + box.x, y + box.y, inner, ctx);
                if (d.behind) {
                    ops.addAll(textAt, inner);
                    textAt += inner.size();
                } else {
                    ops.addAll(inner);
                }
            }
        } finally {
            ctx.depth--;
        }
    }

    private static void vertical(Drawing.TextBox tb, float x, float y, float w, float h, List<Op> ops, Ctx ctx) {
        float inner = Math.max(1, h - tb.left() - tb.right());
        ctx.depth++;
        StackLayout.Result r;
        try {
            r = StackLayout.layout(tb.blocks(), inner, ctx);
        } finally {
            ctx.depth--;
        }
        AffineTransform t = new AffineTransform();
        if (tb.clockwise()) {
            t.translate(x + w, y);
            t.rotate(Math.PI / 2);
        } else {
            t.translate(x, y + h);
            t.rotate(-Math.PI / 2);
        }
        ops.add(new Op.Group(tb.left(), tb.top(), t, null, r.ops()));
    }
}
