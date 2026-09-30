package stirling.software.officeconvert.topdf.xlsx;

import java.awt.geom.AffineTransform;
import java.io.IOException;

import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.io.DecodedPicture;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;

final class PagePainter {

    private final Book book;

    private final RenderJob job;

    PagePainter(Book book) {
        this.book = book;
        this.job = book.job();
    }

    void paint(SheetPlan plan, Paginator.Page page, int pageInSheet, HeaderFooterText.Context ctx) throws IOException {
        PageSetup setup = plan.setup;
        try (PdfCanvas canvas = job.newPage(setup.output().width(), setup.output().height())) {
            double s = plan.scale;
            double k = setup.resized() ? PageSetup.LETTER_ON_A4 : 1;
            book.typesetter().pageScale(s * k);
            headerFooter(canvas, plan, pageInSheet, ctx, s * k);
            if (setup.resized()) {
                canvas.transform(setup.resize());
            }
            Headings headings = setup.headings() ? new Headings(plan.grid, plan.grid.lastRow()) : null;
            double headW = headings == null ? 0 : headings.width(s * k);
            double headH = headings == null ? 0 : headings.height(s * k);
            double contentW = headW;
            for (Band b : page.cols()) {
                contentW += b.length();
            }
            double contentH = headH;
            for (Band b : page.rows()) {
                contentH += b.length();
            }
            double ox = setup.left() + PrintMetrics.ORIGIN;
            double oy = setup.top() + PrintMetrics.ORIGIN;
            if (setup.centerHorizontally()) {
                ox = setup.centeredLeft(contentW * s);
            }
            if (setup.centerVertically()) {
                oy = setup.centeredTop(contentH * s);
            }
            canvas.save();
            canvas.translate((float) ox, (float) oy);
            canvas.scale((float) s, (float) s);
            boolean mirror = plan.grid.rightToLeft();
            canvas.save();
            if (mirror) {
                canvas.transform(new AffineTransform(-1, 0, 0, 1, contentW, 0));
                book.typesetter().mirrored(true);
            }
            if (headings != null) {
                headings.paint(canvas, page.rows(), page.cols(), s * k);
            }
            BlockPainter blocks = new BlockPainter(plan.grid, canvas, job, setup.gridlines(), s * k);
            double y = headH;
            double bodyX = headW;
            double bodyY = headH;
            for (Band rb : page.rows()) {
                double x = headW;
                for (Band cb : page.cols()) {
                    canvas.save();
                    canvas.translate((float) x, (float) y);
                    blocks.paint(rb, cb);
                    canvas.restore();
                    if (cb == page.bodyCols()) {
                        bodyX = x;
                    }
                    x += cb.length();
                }
                if (rb == page.bodyRows()) {
                    bodyY = y;
                }
                y += rb.length();
            }
            if (setup.gridlines() || headings != null) {
                frame(canvas, contentW, contentH, s * k);
            }
            book.typesetter().mirrored(false);
            canvas.restore();
            if (blocks.failures() > 0) {
                job.warn("Some cells on sheet " + plan.name + " could not be drawn");
            }
            if (!plan.drawings.isEmpty()) {
                double bodyW = page.bodyCols().length();
                canvas.save();
                canvas.clipRect((float) (mirror ? contentW - bodyX - bodyW : bodyX), (float) bodyY, (float) bodyW,
                        (float) page.bodyRows().length());
                new DrawingPainter(plan.grid, canvas, page.bodyRows(), page.bodyCols(), bodyX, bodyY,
                        mirror ? contentW : 0).paint(plan.drawings);
                canvas.restore();
            }
            canvas.restore();
            book.typesetter().pageScale(1);
        }
    }

    static void frame(PdfCanvas canvas, double w, double h, double device) throws IOException {
        double t = BorderPainter.deviceWidth(Headings.THIN, device);
        boolean edge = BorderPainter.fullSize(device);
        BorderPainter.solid(canvas, true, 0, 0, w, t, BlockPainter.GRID, edge, device);
        BorderPainter.solid(canvas, true, h, 0, w, t, BlockPainter.GRID, edge, device);
        BorderPainter.solid(canvas, false, 0, 0, h, t, BlockPainter.GRID, edge, device);
        BorderPainter.solid(canvas, false, w, 0, h, t, BlockPainter.GRID, edge, device);
    }

    private void headerFooter(PdfCanvas canvas, SheetPlan plan, int pageInSheet, HeaderFooterText.Context ctx,
            double scale) throws IOException {
        HeaderFooterSet hf = plan.setup.headerFooter();
        String head = hf.header(pageInSheet, ctx.page());
        String foot = hf.footer(pageInSheet, ctx.page());
        if ((head == null || head.isEmpty()) && (foot == null || foot.isEmpty())) {
            return;
        }
        FontSpec base = book.styles().defaultFont();
        HeaderFooterPainter p = new HeaderFooterPainter(book.typesetter(), canvas);
        String variant = hf.variant(pageInSheet, ctx.page());
        if (head != null && !head.isEmpty()) {
            HeaderFooterText.Sections s = HeaderFooterText.parse(head, base, ctx, book.colors());
            if (!s.isEmpty()) {
                p.header(s, plan.setup, scale);
            }
            pictures(p, plan, s, true, variant);
        }
        if (foot != null && !foot.isEmpty()) {
            HeaderFooterText.Sections s = HeaderFooterText.parse(foot, base, ctx, book.colors());
            if (!s.isEmpty()) {
                p.footer(s, plan.setup, scale);
            }
            pictures(p, plan, s, false, variant);
        }
    }

    private void pictures(HeaderFooterPainter p, SheetPlan plan, HeaderFooterText.Sections s, boolean header,
            String variant) throws IOException {
        for (int i = 0; i < 3; i++) {
            if (!s.pictures()[i]) {
                continue;
            }
            HeaderPictures.Picture pic = plan.pictures.get("LCR".charAt(i) + (header ? "H" : "F") + variant);
            DecodedPicture decoded = pic == null ? null : book.picture(pic.part());
            if (decoded != null) {
                p.picture(decoded, pic.width(), pic.height(), i, header, plan.setup);
            }
        }
    }
}
