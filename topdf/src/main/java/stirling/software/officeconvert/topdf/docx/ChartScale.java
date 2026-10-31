package stirling.software.officeconvert.topdf.docx;

import java.util.ArrayList;
import java.util.List;

final class ChartScale {

    static final int MAX_TIGHT_STEPS = 10;

    private final Chart chart;

    ChartScale(Chart chart) {
        this.chart = chart;
    }

    record Scale(double min, double max, double unit) {

        // A set maximum that is no multiple of the unit ends the axis between gridlines
        int steps() {
            return (int) Math.max(1, Math.min(1000, Math.floor((max - min) / unit + 1e-6)));
        }

        float fraction(int step) {
            return max > min ? (float) Math.min(1, step * unit / (max - min)) : 0;
        }
    }

    double[] range(boolean secondary) {
        double lo = 0;
        double hi = 0;
        double dataLo = Double.POSITIVE_INFINITY;
        double dataHi = Double.NEGATIVE_INFINITY;
        boolean any = false;
        boolean stackedAny = false;
        for (Chart.Group g : chart.groups()) {
            if (g.secondary() != secondary) {
                continue;
            }
            boolean percent = g.grouping().equals("percentStacked");
            boolean stacked = g.grouping().equals("stacked");
            int n = 0;
            for (Chart.Series s : g.series()) {
                n = Math.max(n, s.values().length);
            }
            if (percent) {
                hi = Math.max(hi, 1);
                any = true;
                stackedAny = true;
                continue;
            }
            double[] pos = new double[n];
            double[] neg = new double[n];
            for (Chart.Series s : g.series()) {
                for (int i = 0; i < s.values().length; i++) {
                    double v = s.values()[i];
                    if (Double.isNaN(v)) {
                        continue;
                    }
                    any = true;
                    if (stacked) {
                        stackedAny = true;
                        if (v >= 0) {
                            pos[i] += v;
                        } else {
                            neg[i] += v;
                        }
                    } else {
                        dataLo = Math.min(dataLo, v);
                        dataHi = Math.max(dataHi, v);
                        lo = Math.min(lo, v);
                        hi = Math.max(hi, v);
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                hi = Math.max(hi, pos[i]);
                lo = Math.min(lo, neg[i]);
            }
        }
        if (!any || hi - lo <= 0) {
            return new double[] {lo, lo + 1};
        }
        return stackedAny ? new double[] {lo, hi} : zeroRule(dataLo, dataHi, lo, hi);
    }

    // Excel leaves zero out when the values sit in a band narrower than a sixth of their size
    private static double[] zeroRule(double dataLo, double dataHi, double lo, double hi) {
        if (dataLo > 0 && dataHi > dataLo && (dataHi - dataLo) < dataHi / 6) {
            return new double[] {dataLo - (dataHi - dataLo) / 2, dataHi};
        }
        if (dataHi < 0 && dataHi > dataLo && (dataHi - dataLo) < -dataLo / 6) {
            return new double[] {dataLo, dataHi + (dataHi - dataLo) / 2};
        }
        return new double[] {lo, hi};
    }

    double[] xRange() {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (Chart.Group g : chart.groups()) {
            for (Chart.Series s : g.series()) {
                if (s.xs() == null) {
                    continue;
                }
                for (double x : s.xs()) {
                    if (!Double.isNaN(x)) {
                        lo = Math.min(lo, x);
                        hi = Math.max(hi, x);
                    }
                }
            }
        }
        if (lo > hi) {
            return null;
        }
        if (hi - lo <= 0) {
            return new double[] {Math.min(0, lo), Math.max(hi, lo + 1)};
        }
        if (chart.tightScale()) {
            return new double[] {lo, hi};
        }
        return zeroRule(lo, hi, Math.min(0, lo), Math.max(0, hi));
    }

    Scale fit(Chart.Axis va, double unit, double[] r, boolean clearOfData) {
        double range = r[1] - r[0];
        double room = chart.tightScale() ? 0 : 0.05 * range;
        double top = r[1] > 0 ? r[1] + room : r[1];
        double bottom = r[0] < 0 ? r[0] - room : r[0];
        double max = va != null && va.max() != null ? va.max() : Math.ceil(top / unit - 1e-9) * unit;
        double min = va != null && va.min() != null ? va.min() : Math.floor(bottom / unit + 1e-9) * unit;
        if (chart.tightScale() && clearOfData) {
            if ((va == null || va.max() == null) && r[1] > 0 && max - r[1] < unit * 1e-6) {
                max += unit;
            }
            if ((va == null || va.min() == null) && r[0] < 0 && r[0] - min < unit * 1e-6) {
                min -= unit;
            }
        }
        if (max <= min) {
            max = min + unit;
        }
        return new Scale(min, max, unit);
    }

    List<Double> units(Chart.Axis va, double[] r) {
        List<Double> out = new ArrayList<>();
        if (va != null && va.majorUnit() != null) {
            out.add(va.majorUnit());
            return out;
        }
        double lo = va != null && va.min() != null ? va.min() : r[0];
        double hi = va != null && va.max() != null ? va.max() : r[1];
        double span = Math.max(Math.abs(hi - lo), 1e-9);
        double magnitude = Math.pow(10, Math.floor(Math.log10(span)) - 2);
        for (int k = 0; k < 6; k++) {
            for (double m : new double[] {1, 2, 5}) {
                out.add(m * magnitude);
            }
            magnitude *= 10;
        }
        return out;
    }

    // The smallest round major unit whose gridlines stay at least the given gap apart
    Scale scale(Chart.Axis va, float length, float spacing) {
        return scale(va, length, spacing, range(false));
    }

    Scale scale(Chart.Axis va, float length, float spacing, double[] r) {
        Scale last = null;
        for (double unit : units(va, r)) {
            Scale sc = fit(va, unit, r, false);
            last = sc;
            if ((sc.max() - sc.min()) / unit > (chart.tightScale() ? MAX_TIGHT_STEPS : 1000)) {
                continue;
            }
            if (length / ((sc.max() - sc.min()) / unit) >= spacing) {
                return sc;
            }
        }
        return last == null ? new Scale(0, 1, 1) : last;
    }
}
