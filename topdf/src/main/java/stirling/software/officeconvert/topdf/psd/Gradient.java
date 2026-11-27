package stirling.software.officeconvert.topdf.psd;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

final class Gradient {

    static final int SAMPLES = 1024;

    private final String type;

    private final double angle;

    private final double scale;

    private final boolean reverse;

    private final double offsetX;

    private final double offsetY;

    private final float[][] colors;

    private final float[] alphas;

    private Gradient(String type, double angle, double scale, boolean reverse, double offsetX, double offsetY,
            float[][] colors, float[] alphas) {
        this.type = type;
        this.angle = angle;
        this.scale = scale;
        this.reverse = reverse;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.colors = colors;
        this.alphas = alphas;
    }

    record Stop(double at, double mid, float[] value) {}

    static Gradient of(Descriptor d, int channels, Function<float[], float[]> toDocument) {
        if (d == null) {
            return null;
        }
        Descriptor grad = d.object("Grad");
        if (grad == null) {
            return null;
        }
        List<Stop> colorStops = new ArrayList<>();
        for (Object o : grad.list("Clrs")) {
            if (o instanceof Descriptor s) {
                float[] c = s.color("Clr ");
                if (c == null) {
                    String which = s.enumValue("Type");
                    c = "BckC".equals(which) ? new float[] {1, 1, 1} : new float[] {0, 0, 0};
                }
                colorStops.add(new Stop(s.number("Lctn", 0) / 4096, s.number("Mdpn", 50) / 100, toDocument.apply(c)));
            }
        }
        List<Stop> alphaStops = new ArrayList<>();
        for (Object o : grad.list("Trns")) {
            if (o instanceof Descriptor s) {
                alphaStops.add(new Stop(s.number("Lctn", 0) / 4096, s.number("Mdpn", 50) / 100,
                        new float[] {(float) (s.number("Opct", 100) / 100)}));
            }
        }
        if (colorStops.isEmpty()) {
            return null;
        }
        if (alphaStops.isEmpty()) {
            alphaStops.add(new Stop(0, 0.5, new float[] {1}));
        }
        colorStops.sort((a, b) -> Double.compare(a.at(), b.at()));
        alphaStops.sort((a, b) -> Double.compare(a.at(), b.at()));
        float[][] colors = new float[SAMPLES][];
        float[] alphas = new float[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            double t = i / (double) (SAMPLES - 1);
            colors[i] = sample(colorStops, t, channels);
            alphas[i] = sample(alphaStops, t, 1)[0];
        }
        String type = d.enumValue("Type");
        Descriptor offset = d.object("Ofst");
        return new Gradient(type == null ? "Lnr " : type, Math.toRadians(d.number("Angl", 90)),
                d.number("Scl ", 100) / 100, d.flag("Rvrs", false),
                offset == null ? 0 : offset.number("Hrzn", 0) / 100, offset == null ? 0 : offset.number("Vrtc", 0) / 100,
                colors, alphas);
    }

    private static float[] sample(List<Stop> stops, double t, int n) {
        Stop first = stops.get(0);
        Stop last = stops.get(stops.size() - 1);
        if (t <= first.at()) {
            return first.value();
        }
        if (t >= last.at()) {
            return last.value();
        }
        for (int i = 0; i + 1 < stops.size(); i++) {
            Stop a = stops.get(i);
            Stop b = stops.get(i + 1);
            if (t >= a.at() && t <= b.at()) {
                double span = b.at() - a.at();
                double u = span <= 0 ? 0 : (t - a.at()) / span;
                double m = Math.max(0.05, Math.min(0.95, a.mid()));
                u = u < m ? 0.5 * u / m : 0.5 + 0.5 * (u - m) / (1 - m);
                float[] v = new float[Math.min(a.value().length, b.value().length)];
                for (int k = 0; k < v.length; k++) {
                    v[k] = (float) (a.value()[k] + (b.value()[k] - a.value()[k]) * u);
                }
                return v;
            }
        }
        return last.value();
    }

    void row(int y, int width, double left, double top, double right, double bottom, float[] out, int stride, int n) {
        double w = Math.max(1, right - left);
        double h = Math.max(1, bottom - top);
        double cx = left + w / 2 + offsetX * w;
        double cy = top + h / 2 + offsetY * h;
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double length = (Math.abs(w * cos) + Math.abs(h * sin)) * scale;
        double radius = Math.max(1e-6, length / 2);
        double py = y + 0.5 - cy;
        for (int x = 0; x < width; x++) {
            double px = x + 0.5 - cx;
            double along = px * cos - py * sin;
            double across = px * sin + py * cos;
            double t = switch (type) {
                case "Rdl " -> Math.hypot(px, py) / radius;
                case "Angl" -> {
                    double a = Math.atan2(-py, px) - angle;
                    a = a % (2 * Math.PI);
                    if (a < 0) {
                        a += 2 * Math.PI;
                    }
                    yield 1 - a / (2 * Math.PI);
                }
                case "Rflc" -> Math.abs(along) / radius;
                case "Dmnd" -> (Math.abs(along) + Math.abs(across)) / radius;
                default -> along / length + 0.5;
            };
            if (reverse) {
                t = 1 - t;
            }
            int i = (int) Math.round(Math.max(0, Math.min(1, t)) * (SAMPLES - 1));
            int at = x * stride;
            System.arraycopy(colors[i], 0, out, at, n);
            out[at + n] = alphas[i];
        }
    }
}
