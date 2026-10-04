package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.font.BidiRuns;
import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.Stroke;
import stirling.software.officeconvert.topdf.pdf.TextStyle;

final class Painter {

    private final RenderJob job;

    private int failures;

    private final Map<List<Op>, Integer> chartUses = new IdentityHashMap<>();

    private final Map<List<Op>, PDFormXObject> chartForms = new IdentityHashMap<>();

    private Color background;

    Painter(RenderJob job) {
        this.job = job;
    }

    Painter background(Color color) {
        background = color;
        return this;
    }

    // Each page is let go once drawn, so a long document never holds all of its pages' drawing and its PDF at once
    void paint(List<PageBox> pages) throws IOException {
        for (PageBox p : pages) {
            countCharts(p);
        }
        for (int i = 0; i < pages.size(); i++) {
            job.checkpoint();
            PageBox p = pages.set(i, null);
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

    private void countCharts(PageBox p) {
        countCharts(p.header);
        countCharts(p.footer);
        for (PageBox.FloatBox f : p.headerFloats) {
            countCharts(f.ops());
        }
        for (PageBox.FloatBox f : p.floats) {
            countCharts(f.ops());
        }
        for (Placed pl : p.placed) {
            countCharts(pl.strip.ops);
        }
        countCharts(p.noteOps);
        countCharts(p.decor);
    }

    private void countCharts(List<Op> ops) {
        for (Op op : ops) {
            if (op instanceof Op.Chart ch) {
                chartUses.merge(ch.ops(), 1, Integer::sum);
            } else if (op instanceof Op.Group g) {
                countCharts(g.ops());
            }
        }
    }

    // A chart shown more than once is drawn into one form XObject that every use places
    private void chart(PdfCanvas c, Op.Chart ch) throws IOException {
        if (chartUses.getOrDefault(ch.ops(), 0) < 2 || !(ch.w() > 0 && ch.h() > 0)) {
            group(c, ch.x(), ch.y(), ch.ops());
            return;
        }
        float margin = Math.max(ch.w(), ch.h());
        PDFormXObject form = chartForms.get(ch.ops());
        if (form == null) {
            try (PdfCanvas f = c.output().newForm(ch.w(), ch.h(), margin)) {
                ops(f, ch.ops());
                form = f.form();
            }
            chartForms.put(ch.ops(), form);
        }
        c.form(form, ch.x() - margin, ch.y() - margin, ch.w() + 2 * margin, ch.h() + 2 * margin);
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

    // Text one face cannot show as it stands (other scripts, right to left, shaping) goes face by face in visual order
    static float text(PdfCanvas c, Op.Text t) throws IOException {
        String s = t.text();
        FontFace face = t.style().face();
        if (!BidiRuns.needed(s) && !FontFace.needsShaping(s) && covered(face, s)) {
            return c.text(s, t.x(), t.baseline(), t.style());
        }
        float x = t.x();
        for (BidiRuns.Run run : BidiRuns.visual(BidiRuns.logical(s, null))) {
            List<String> parts = new ArrayList<>();
            List<FontFace> faces = new ArrayList<>();
            String part = run.of(s);
            int start = 0;
            FontFace current = null;
            for (int i = 0; i < part.length(); ) {
                int cp = part.codePointAt(i);
                FontFace f = shows(face, cp) ? face : c.output().fonts().fallback(cp, face);
                if (f == null || Character.getType(cp) == Character.NON_SPACING_MARK && current != null) {
                    f = current == null ? face : current;
                }
                if (current != null && !f.equals(current)) {
                    parts.add(part.substring(start, i));
                    faces.add(current);
                    start = i;
                }
                current = f;
                i += Character.charCount(cp);
            }
            parts.add(part.substring(start));
            faces.add(current == null ? face : current);
            for (int k = 0; k < parts.size(); k++) {
                int at = run.rightToLeft() ? parts.size() - 1 - k : k;
                String p = parts.get(at);
                FontFace f = faces.get(at);
                TextStyle st = t.style().face(f);
                if ((run.rightToLeft() || FontFace.needsShaping(p)) && f.shapeable()) {
                    x += c.drawGlyphs(f.shape(p, run.rightToLeft()), x, t.baseline(), st);
                } else {
                    x += c.text(p, x, t.baseline(), st);
                }
            }
        }
        return x - t.x();
    }

    private static boolean covered(FontFace face, String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (!shows(face, cp)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    private static boolean shows(FontFace face, int cp) {
        return face.covers(cp) || Character.isWhitespace(cp) || Character.isISOControl(cp) || cp == 0x00AD
                || Character.getType(cp) == Character.FORMAT;
    }

    private void op(PdfCanvas c, Op op) throws IOException {
        switch (op) {
            case Op.Text t -> text(c, t);
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
            case Op.Chart ch -> chart(c, ch);
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
