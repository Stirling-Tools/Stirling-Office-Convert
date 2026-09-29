package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.Stroke;

final class Painter {

    private final RenderJob job;

    private int failures;

    private Color background;

    Painter(RenderJob job) {
        this.job = job;
    }

    Painter background(Color color) {
        background = color;
        return this;
    }

    void paint(List<PageBox> pages) throws IOException {
        for (PageBox p : pages) {
            job.checkpoint();
            try (PdfCanvas c = job.newPage(p.w, p.h)) {
                page(c, p);
            }
        }
    }

    private void page(PdfCanvas c, PageBox p) throws IOException {
        if (background != null && !Color.WHITE.equals(background)) {
            c.rect(0, 0, p.w, p.h, Fill.solid(background), null);
        }
        List<PageBox.FloatBox> headerFloats = new ArrayList<>(p.headerFloats);
        headerFloats.sort(Comparator.comparingLong(PageBox.FloatBox::z));
        layer(c, headerFloats, true);
        ops(c, p.header);
        ops(c, p.footer);
        layer(c, headerFloats, false);
        List<PageBox.FloatBox> floats = new ArrayList<>(p.floats);
        floats.sort(Comparator.comparingLong(PageBox.FloatBox::z));
        layer(c, floats, true);
        for (Placed pl : p.placed) {
            if (pl.strip.ops.isEmpty()) {
                continue;
            }
            group(c, pl.x, pl.y, pl.strip.ops);
        }
        ops(c, p.noteOps);
        ops(c, p.decor);
        layer(c, floats, false);
        borders(c, p);
    }

    void draw(PdfCanvas c, List<Op> ops) throws IOException {
        ops(c, ops);
    }

    private void layer(PdfCanvas c, List<PageBox.FloatBox> floats, boolean behind) throws IOException {
        for (PageBox.FloatBox f : floats) {
            if (f.behind() == behind) {
                ops(c, f.ops());
            }
        }
    }

    private void group(PdfCanvas c, float dx, float dy, List<Op> ops) throws IOException {
        if (dx == 0 && dy == 0) {
            ops(c, ops);
            return;
        }
        c.save();
        try {
            c.translate(dx, dy);
            ops(c, ops);
        } finally {
            c.restore();
        }
    }

    private void ops(PdfCanvas c, List<Op> ops) throws IOException {
        for (Op op : ops) {
            try {
                op(c, op);
            } catch (RuntimeException e) {
                if (e instanceof RenderJob.PageLimitReached r) {
                    throw r;
                }
                if (failures++ < 3) {
                    job.warn("Part of a page could not be drawn: " + e);
                }
            }
        }
    }

    private void op(PdfCanvas c, Op op) throws IOException {
        switch (op) {
            case Op.Text t -> c.text(t.text(), t.x(), t.baseline(), t.style());
            case Op.Glyphs g -> c.drawGlyphs(g.run(), g.x(), g.baseline(), g.style());
            case Op.Rect r -> c.rect(r.x(), r.y(), r.w(), r.h(), r.fill(), r.stroke());
            case Op.Line l -> c.line(l.x1(), l.y1(), l.x2(), l.y2(), l.stroke());
            case Op.Path p -> c.draw(p.shape(), p.fill(), p.stroke());
            case Op.Image i -> c.image(i.picture(), i.x(), i.y(), i.w(), i.h(), i.crop(), i.rotation(), i.flipH(),
                    i.flipV(), i.alpha());
            case Op.Link l -> {
                if (l.url() != null) {
                    c.link(l.x(), l.y(), l.w(), l.h(), l.url());
                } else if (l.anchor() != null) {
                    c.linkTo(l.x(), l.y(), l.w(), l.h(), "bm:" + l.anchor());
                }
            }
            case Op.Dest d -> c.destination("bm:" + d.name(), d.x(), d.y());
            case Op.Group g -> {
                if (g.transform() == null && g.clip() == null) {
                    group(c, g.dx(), g.dy(), g.ops());
                    return;
                }
                c.save();
                try {
                    if (g.transform() != null) {
                        c.transform(g.transform());
                    }
                    if (g.dx() != 0 || g.dy() != 0) {
                        c.translate(g.dx(), g.dy());
                    }
                    if (g.clip() != null) {
                        c.clipRect(g.clip()[0], g.clip()[1], g.clip()[2], g.clip()[3]);
                    }
                    ops(c, g.ops());
                } finally {
                    c.restore();
                }
            }
        }
    }

    private void borders(PdfCanvas c, PageBox p) throws IOException {
        XEl pb = p.sect.pageBorders;
        if (pb == null) {
            return;
        }
        boolean fromText = "text".equals(pb.attr("offsetFrom"));
        String display = pb.attr("display", "allPages");
        if (display.equals("firstPage") && !p.firstOfSection || display.equals("notFirstPage") && p.firstOfSection) {
            return;
        }
        SectionProps s = p.sect;
        for (XEl side : pb.kids) {
            Border b = Border.parse(side, null);
            if (b == null || !b.visible()) {
                continue;
            }
            float left;
            float top;
            float right;
            float bottom;
            if (fromText) {
                left = s.bodyLeft() + p.shift - b.space() - b.width() / 2;
                right = s.pageW - s.right + p.shift + b.space() + b.width() / 2;
                top = p.bodyTop - b.space() - b.width() / 2;
                bottom = p.bodyBottom + b.space() + b.width() / 2;
            } else {
                left = b.space() + b.width() / 2;
                right = s.pageW - b.space() - b.width() / 2;
                top = b.space() + b.width() / 2;
                bottom = s.pageH - b.space() - b.width() / 2;
            }
            Stroke stroke = Stroke.solid(b.width(), b.color());
            switch (side.name) {
                case "w:top" -> c.line(left, top, right, top, stroke);
                case "w:bottom" -> c.line(left, bottom, right, bottom, stroke);
                case "w:left" -> c.line(left, top, left, bottom, stroke);
                case "w:right" -> c.line(right, top, right, bottom, stroke);
                default -> {
                }
            }
        }
    }
}
