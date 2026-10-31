package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.topdf.docx.ChartPainter.Plot;
import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.Stroke;

final class ChartMarks {

    private ChartMarks() {}

    static void errorBars(List<Op> ops, Chart.ErrorBars e, Chart.Series s, Plot p, List<double[]> raw,
            boolean scatter) {
        if (e.x() && (!scatter || p.xScale() == null)) {
            return;
        }
        for (double[] r : raw) {
            int i = (int) r[0];
            if (!e.x()) {
                errorBar(ops, e, p, (float) r[2], r[1], i, s.values());
                continue;
            }
            double x = s.xs() != null && i < s.xs().length ? s.xs()[i] : Double.NaN;
            if (Double.isNaN(x)) {
                continue;
            }
            double[] range = errorRange(e, x, i, s.xs());
            if (range == null) {
                continue;
            }
            float y = p.valuePos(r[1]);
            errorLine(ops, e, p.xPos(range[0]), y, p.xPos(range[1]), y, true);
        }
    }

    static void errorBar(List<Op> ops, Chart.ErrorBars e, Plot p, float along, double value, int i, double[] values) {
        double[] range = errorRange(e, value, i, values);
        if (range == null) {
            return;
        }
        float a = p.valuePos(range[0]);
        float b = p.valuePos(range[1]);
        if (p.horizontal()) {
            errorLine(ops, e, a, along, b, along, true);
        } else {
            errorLine(ops, e, along, a, along, b, false);
        }
    }

    private static double[] errorRange(Chart.ErrorBars e, double value, int i, double[] all) {
        double sum = 0;
        double squares = 0;
        int n = 0;
        for (double v : all) {
            if (Double.isFinite(v)) {
                sum += v;
                n++;
            }
        }
        double mean = n == 0 ? 0 : sum / n;
        for (double v : all) {
            if (Double.isFinite(v)) {
                squares += (v - mean) * (v - mean);
            }
        }
        double deviation = n > 1 ? Math.sqrt(squares / (n - 1)) : 0;
        double center = e.kind().equals("stdDev") ? mean : value;
        double plus;
        double minus;
        switch (e.kind()) {
            case "percentage" -> plus = minus = Math.abs(value) * e.value() / 100;
            case "stdDev" -> plus = minus = e.value() * deviation;
            case "stdErr" -> plus = minus = n > 0 ? deviation / Math.sqrt(n) : 0;
            case "cust" -> {
                plus = i < e.plus().length && Double.isFinite(e.plus()[i]) ? e.plus()[i] : 0;
                minus = i < e.minus().length && Double.isFinite(e.minus()[i]) ? e.minus()[i] : 0;
            }
            default -> plus = minus = e.value();
        }
        if (e.type().equals("plus")) {
            minus = 0;
        } else if (e.type().equals("minus")) {
            plus = 0;
        }
        if (!Double.isFinite(plus) || !Double.isFinite(minus) || plus == 0 && minus == 0) {
            return null;
        }
        return new double[] {center - Math.abs(minus), center + Math.abs(plus)};
    }

    static void errorLine(List<Op> ops, Chart.ErrorBars e, float x1, float y1, float x2, float y2, boolean across) {
        Path2D.Float path = new Path2D.Float();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        if (e.endCap()) {
            float c = 3;
            for (float[] end : new float[][] {{x1, y1}, {x2, y2}}) {
                if (across) {
                    path.moveTo(end[0], end[1] - c);
                    path.lineTo(end[0], end[1] + c);
                } else {
                    path.moveTo(end[0] - c, end[1]);
                    path.lineTo(end[0] + c, end[1]);
                }
            }
        }
        ops.add(new Op.Path(path, null, e.line()));
    }

    static void trend(List<Op> ops, Chart.Trend t, Chart.Series s, Plot p, List<double[]> raw, boolean scatter) {
        if (scatter && p.xScale() == null) {
            return;
        }
        List<double[]> pts = new ArrayList<>();
        for (double[] r : raw) {
            int i = (int) r[0];
            double x = scatter ? s.xs() != null && i < s.xs().length ? s.xs()[i] : Double.NaN : i + 1;
            if (Double.isFinite(x) && Double.isFinite(r[1])) {
                pts.add(new double[] {x, r[1]});
            }
        }
        if (pts.size() < 2) {
            return;
        }
        Path2D.Float path = new Path2D.Float();
        if (t.type().equals("movingAvg")) {
            boolean started = false;
            for (int k = t.period() - 1; k < pts.size(); k++) {
                double sum = 0;
                for (int j = k - t.period() + 1; j <= k; j++) {
                    sum += pts.get(j)[1];
                }
                started = trendTo(path, p, pts.get(k)[0], sum / t.period(), scatter, started);
            }
        } else {
            double[] fit = Trends.fit(t.type(), t.order(), pts);
            if (fit == null) {
                return;
            }
            double lo = pts.stream().mapToDouble(q -> q[0]).min().orElse(0) - t.backward();
            double hi = pts.stream().mapToDouble(q -> q[0]).max().orElse(0) + t.forward();
            int steps = t.type().equals("linear") ? 1 : 96;
            boolean started = false;
            for (int k = 0; k <= steps; k++) {
                double x = lo + (hi - lo) * k / steps;
                double y = Trends.at(t.type(), fit, x);
                if (!Double.isFinite(y) || y < p.scale().min() || y > p.scale().max()) {
                    started = false;
                    continue;
                }
                started = trendTo(path, p, x, y, scatter, started);
            }
        }
        ops.add(new Op.Path(path, null, t.line()));
    }

    private static boolean trendTo(Path2D.Float path, Plot p, double x, double y, boolean scatter, boolean started) {
        float along;
        if (scatter) {
            along = p.xPos(x);
        } else {
            float first = p.center(0);
            float step = p.count() > 1 ? p.center(1) - first : p.band();
            along = (float) (first + step * (x - 1));
        }
        float v = p.valuePos(y);
        float px = p.horizontal() && !scatter ? v : along;
        float py = p.horizontal() && !scatter ? along : v;
        if (started) {
            path.lineTo(px, py);
        } else {
            path.moveTo(px, py);
        }
        return true;
    }

    // Smoothed lines pass through every point on Bezier curves with Catmull-Rom tangents, as Office draws them
    static void trace(Path2D.Float path, List<float[]> pts, boolean smooth) {
        path.moveTo(pts.get(0)[0], pts.get(0)[1]);
        for (int i = 1; i < pts.size(); i++) {
            float[] b = pts.get(i);
            if (!smooth || pts.size() < 3) {
                path.lineTo(b[0], b[1]);
                continue;
            }
            float[] a = pts.get(i - 1);
            float[] before = pts.get(Math.max(0, i - 2));
            float[] after = pts.get(Math.min(pts.size() - 1, i + 1));
            path.curveTo(a[0] + (b[0] - before[0]) / 6, a[1] + (b[1] - before[1]) / 6, b[0] - (after[0] - a[0]) / 6,
                    b[1] - (after[1] - a[1]) / 6, b[0], b[1]);
        }
    }

    static void marker(List<Op> ops, Chart.Marker m, float cx, float cy) {
        float r = m.size() / 2;
        Fill fill = m.fill() == null ? null : Fill.solid(m.fill());
        Stroke line = m.line() == null ? null : Stroke.solid(0.75f, m.line());
        if (fill == null && line == null) {
            return;
        }
        java.awt.Shape shape;
        switch (m.symbol()) {
            case "none" -> {
                return;
            }
            case "square" -> shape = new Rectangle2D.Float(cx - r, cy - r, 2 * r, 2 * r);
            case "diamond" -> shape = polygon(cx, cy - r, cx + r, cy, cx, cy + r, cx - r, cy);
            case "triangle" -> shape = polygon(cx, cy - r, cx + r, cy + r, cx - r, cy + r);
            case "arrowDown" -> shape = polygon(cx - r, cy - r, cx + r, cy - r, cx, cy + r);
            case "dash" -> shape = new Rectangle2D.Float(cx - r, cy - r / 4, 2 * r, r / 2);
            case "dot" -> shape = new Rectangle2D.Float(cx - r / 2, cy - r / 4, r, r / 2);
            case "x", "star", "plus" -> {
                Path2D.Float p = new Path2D.Float();
                if (!m.symbol().equals("plus")) {
                    p.moveTo(cx - r, cy - r);
                    p.lineTo(cx + r, cy + r);
                    p.moveTo(cx - r, cy + r);
                    p.lineTo(cx + r, cy - r);
                }
                if (!m.symbol().equals("x")) {
                    p.moveTo(cx, cy - r);
                    p.lineTo(cx, cy + r);
                    p.moveTo(cx - r, cy);
                    p.lineTo(cx + r, cy);
                }
                Color c = m.line() != null ? m.line() : m.fill();
                ops.add(new Op.Path(p, null, Stroke.solid(0.75f, c)));
                return;
            }
            default -> shape = new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r);
        }
        ops.add(new Op.Path(shape, fill, line));
    }

    static Path2D.Float polygon(float... xy) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) {
            p.lineTo(xy[i], xy[i + 1]);
        }
        p.closePath();
        return p;
    }
}
