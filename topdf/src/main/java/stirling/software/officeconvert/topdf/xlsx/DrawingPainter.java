package stirling.software.officeconvert.topdf.xlsx;

import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.dml.DmlShapes;
import stirling.software.officeconvert.topdf.docx.Charts;
import stirling.software.officeconvert.topdf.io.DecodedPicture;
import stirling.software.officeconvert.topdf.pdf.Crop;
import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.Stroke;

final class DrawingPainter {

    private final Grid grid;

    private final PdfCanvas canvas;

    private final Band rows;

    private final Band cols;

    private final double originX;

    private final double originY;

    private final double colScale;

    DrawingPainter(Grid grid, PdfCanvas canvas, Band rows, Band cols, double originX, double originY) {
        this.grid = grid;
        this.canvas = canvas;
        this.rows = rows;
        this.cols = cols;
        this.originX = originX;
        this.originY = originY;
        PrintMetrics m = grid.book().metrics();
        this.colScale = m.printerDigit() * PrintMetrics.PX / (m.screenDigit() * 0.75);
    }

    void paint(Drawings drawings) throws IOException {
        for (Drawings.Item item : drawings.items) {
            grid.book().job().checkpoint();
            double[] r = rect(item);
            if (r == null || !(r[2] > 0 || r[3] > 0)) {
                continue;
            }
            if (r[0] > cols.length() + originX + 2 || r[1] > rows.length() + originY + 2 || r[0] + r[2] < originX - 2
                    || r[1] + r[3] < originY - 2) {
                continue;
            }
            if (item.chart() != null) {
                if (!Charts.draw(grid.book().job(), item.chart(), grid.book().workbook().themePart, canvas,
                        (float) r[0], (float) r[1], (float) r[2], (float) r[3])) {
                    grid.book().job().warn("A chart could not be drawn (" + item.chart() + ")");
                }
            } else if (item.picture() != null) {
                picture(item, r);
            } else {
                shape(item, r);
            }
        }
    }

    private double[] rect(Drawings.Item item) {
        double x0;
        double y0;
        double x1;
        double y1;
        if (item.from() != null) {
            x0 = x(item.from());
            y0 = y(item.from());
            if (item.to() != null) {
                x1 = x(item.to());
                y1 = y(item.to());
            } else {
                x1 = x0 + item.absW() * colScale;
                y1 = y0 + item.absH() * grid.rowFactor();
            }
        } else {
            x0 = originX + item.absX() * colScale - absolute(cols.first, true);
            y0 = originY + item.absY() * grid.rowFactor() - absolute(rows.first, false);
            x1 = x0 + item.absW() * colScale;
            y1 = y0 + item.absH() * grid.rowFactor();
        }
        if (item.child() != null) {
            Drawings.Rect c = item.child();
            double w = x1 - x0;
            double h = y1 - y0;
            return new double[] {x0 + c.x() * w, y0 + c.y() * h, c.w() * w, c.h() * h};
        }
        return new double[] {x0, y0, x1 - x0, y1 - y0};
    }

    private double absolute(int index, boolean columns) {
        double at = 0;
        int limit = Math.min(index, columns ? Columns.MAX : 200_000);
        for (int i = 0; i < limit; i++) {
            at += columns ? grid.columnWidth(i) : grid.rowHeight(i);
        }
        return at;
    }

    private double x(Drawings.Anchor a) {
        double w = grid.columnWidth(a.col());
        return originX + cols.start(a.col()) + Math.min(a.colOff() * colScale, w);
    }

    private double y(Drawings.Anchor a) {
        double h = grid.rowHeight(a.row());
        return originY + rows.start(a.row()) + Math.min(a.rowOff() * grid.rowFactor(), h);
    }

    private void picture(Drawings.Item item, double[] r) throws IOException {
        DecodedPicture pic = grid.book().picture(item.picture());
        if (pic == null) {
            return;
        }
        Crop crop = crop(item.crop());
        canvas.image(pic, (float) r[0], (float) r[1], (float) r[2], (float) r[3], crop, (float) item.rotation(),
                item.flipH(), item.flipV(), 1);
    }

    private static Crop crop(Element src) {
        if (src == null) {
            return Crop.NONE;
        }
        float l = (float) (Dml.number(src, "l", 0) / 100000.0);
        float t = (float) (Dml.number(src, "t", 0) / 100000.0);
        float rr = (float) (Dml.number(src, "r", 0) / 100000.0);
        float b = (float) (Dml.number(src, "b", 0) / 100000.0);
        if (!(l + rr < 0.99 && t + b < 0.99)) {
            return Crop.NONE;
        }
        return new Crop(l, t, rr, b);
    }

    private void shape(Drawings.Item item, double[] r) throws IOException {
        float x = (float) r[0];
        float y = (float) r[1];
        float w = (float) r[2];
        float h = (float) r[3];
        canvas.save();
        try {
            if (item.rotation() != 0) {
                canvas.rotate((float) item.rotation(), x + w / 2, y + h / 2);
            }
            Fill fill = item.fill() == null ? null : Fill.solid(item.fill());
            Stroke stroke = item.line() == null ? null : Stroke.solid((float) Math.max(0.25, item.lineWidth()),
                    item.line());
            String g = item.geometry() == null ? "rect" : item.geometry();
            List<DmlShapes.Outline> outlines = item.custom() != null
                    ? DmlShapes.custom(item.custom(), new Rectangle2D.Float(x, y, w, h))
                    : g.equals("line") || g.startsWith("straightConnector") ? null
                    : DmlShapes.preset(g, item.adjust(), new Rectangle2D.Float(x, y, w, h));
            if (item.connector() && outlines == null || g.equals("line") || g.startsWith("straightConnector")) {
                if (stroke != null) {
                    boolean down = item.flipV() == item.flipH();
                    float x0 = x;
                    float y0 = down ? y : y + h;
                    float x1 = x + w;
                    float y1 = down ? y + h : y;
                    if (item.flipH()) {
                        x0 = x + w;
                        x1 = x;
                        y0 = down ? y + h : y;
                        y1 = down ? y : y + h;
                    }
                    canvas.line(x0, y0, x1, y1, stroke);
                    arrow(item.head(), x1, y1, x0, y0, stroke);
                    arrow(item.tail(), x0, y0, x1, y1, stroke);
                }
            } else if (outlines != null) {
                canvas.save();
                try {
                    if (item.flipH() || item.flipV()) {
                        canvas.translate(x + w / 2, y + h / 2);
                        canvas.scale(item.flipH() ? -1 : 1, item.flipV() ? -1 : 1);
                        canvas.translate(-(x + w / 2), -(y + h / 2));
                    }
                    for (DmlShapes.Outline o : outlines) {
                        canvas.draw(o.shape(), o.filled() ? fill : null, o.stroked() ? stroke : null);
                    }
                } finally {
                    canvas.restore();
                }
            } else if (g.equals("ellipse") || g.equals("flowChartConnector")) {
                canvas.ellipse(x, y, w, h, fill, stroke);
            } else if (g.startsWith("roundRect") || g.equals("flowChartAlternateProcess")) {
                float rad = Math.min(w, h) * 0.16667f;
                canvas.roundRect(x, y, w, h, rad, rad, fill, stroke);
            } else if (g.equals("triangle")) {
                Path2D.Float p = new Path2D.Float();
                p.moveTo(x + w / 2, y);
                p.lineTo(x + w, y + h);
                p.lineTo(x, y + h);
                p.closePath();
                canvas.draw(p, fill, stroke);
            } else if (g.equals("diamond") || g.equals("flowChartDecision")) {
                Path2D.Float p = new Path2D.Float();
                p.moveTo(x + w / 2, y);
                p.lineTo(x + w, y + h / 2);
                p.lineTo(x + w / 2, y + h);
                p.lineTo(x, y + h / 2);
                p.closePath();
                canvas.draw(p, fill, stroke);
            } else {
                canvas.rect(x, y, w, h, fill, stroke);
            }
            if (item.text() != null && !item.text().isEmpty()) {
                text(item, x, y, w, h);
            }
        } finally {
            canvas.restore();
        }
    }

    // A line end drawn at (tipX, tipY), pointing away from (fromX, fromY), sized from the line width like Excel's
    private void arrow(String type, float fromX, float fromY, float tipX, float tipY, Stroke stroke)
            throws IOException {
        if (type == null) {
            return;
        }
        double dx = tipX - fromX;
        double dy = tipY - fromY;
        double len = Math.hypot(dx, dy);
        if (len < 0.01) {
            return;
        }
        float size = Math.max(3, stroke.width() * 3);
        double ux = dx / len;
        double uy = dy / len;
        Fill fill = Fill.solid(stroke.color());
        if (type.equals("oval")) {
            canvas.ellipse(tipX - size / 2, tipY - size / 2, size, size, fill, null);
            return;
        }
        Path2D.Float p = new Path2D.Float();
        p.moveTo(tipX, tipY);
        p.lineTo(tipX - ux * size - uy * size / 2, tipY - uy * size + ux * size / 2);
        if (type.equals("diamond")) {
            p.lineTo(tipX - 2 * ux * size, tipY - 2 * uy * size);
        } else if (type.equals("stealth")) {
            p.lineTo(tipX - ux * size * 0.6, tipY - uy * size * 0.6);
        }
        p.lineTo(tipX - ux * size + uy * size / 2, tipY - uy * size - ux * size / 2);
        if (type.equals("arrow")) {
            Path2D.Float open = new Path2D.Float();
            open.moveTo(tipX - ux * size - uy * size / 2, tipY - uy * size + ux * size / 2);
            open.lineTo(tipX, tipY);
            open.lineTo(tipX - ux * size + uy * size / 2, tipY - uy * size - ux * size / 2);
            canvas.draw(open, null, stroke);
            return;
        }
        p.closePath();
        canvas.draw(p, fill, null);
    }

    private void text(Drawings.Item item, double x, double y, double w, double h) throws IOException {
        double[] ins = item.insets();
        double left = x + ins[0];
        double width = Math.max(1, w - ins[0] - ins[2]);
        Typesetter t = grid.book().typesetter();
        List<Object[]> lines = new ArrayList<>();
        double total = 0;
        for (Drawings.Para p : item.text()) {
            List<TextRun> runs = p.runs();
            double size = 0;
            for (TextRun r : runs) {
                size = Math.max(size, r.font().size());
            }
            double pitch = Math.max(1, size) * 1.22;
            boolean empty = runs.stream().allMatch(r -> r.text().isEmpty());
            if (empty) {
                lines.add(new Object[] {List.of(), 0.0, pitch, p.align()});
                total += pitch;
                continue;
            }
            for (CellLayout.Line l : CellLayout.wrap(t, runs, width, 1)) {
                lines.add(new Object[] {l.runs(), l.width(), pitch, p.align()});
                total += pitch;
            }
        }
        double top = switch (item.textAnchor() == null ? "t" : item.textAnchor()) {
            case "ctr" -> y + (h - total) / 2;
            case "b" -> y + h - ins[3] - total;
            default -> y + ins[1];
        };
        canvas.save();
        try {
            canvas.clipRect((float) x, (float) y, (float) w, (float) h);
            double at = top;
            for (Object[] l : lines) {
                @SuppressWarnings("unchecked")
                List<TextRun> runs = (List<TextRun>) l[0];
                double lw = (Double) l[1];
                double pitch = (Double) l[2];
                String align = (String) l[3];
                double lx = left;
                if ("ctr".equals(align)) {
                    lx += (width - lw) / 2;
                } else if ("r".equals(align)) {
                    lx += width - lw;
                }
                double baseline = at + pitch * 0.8;
                for (TextRun r : runs) {
                    lx += t.draw(canvas, r.text(), r.font(), r.font().size(), lx, baseline);
                }
                at += pitch;
            }
        } finally {
            canvas.restore();
        }
    }
}
