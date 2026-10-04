package stirling.software.officeconvert.topdf.pdf;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record Gradient(Kind kind, float x1, float y1, float x2, float y2, float radius, List<Stop> stops) {

    public enum Kind {
        LINEAR,
        RADIAL
    }

    public record Stop(float offset, Color color) {
        public Stop {
            Objects.requireNonNull(color, "color");
            if (!Float.isFinite(offset)) {
                throw new IllegalArgumentException("A gradient stop offset must be finite");
            }
        }
    }

    public Gradient {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(stops, "stops");
        if (stops.isEmpty()) {
            throw new IllegalArgumentException("A gradient needs at least one stop");
        }
        if (!Float.isFinite(x1) || !Float.isFinite(y1) || !Float.isFinite(x2) || !Float.isFinite(y2)
                || !(radius >= 0 && Float.isFinite(radius))) {
            throw new IllegalArgumentException("Gradient geometry must be finite");
        }
        List<Stop> sorted = new ArrayList<>(stops);
        for (Stop s : sorted) {
            Objects.requireNonNull(s, "stop");
        }
        sorted.sort(Comparator.comparingDouble(Stop::offset));
        stops = List.copyOf(sorted);
    }

    public static Gradient linear(float x1, float y1, float x2, float y2, List<Stop> stops) {
        return new Gradient(Kind.LINEAR, x1, y1, x2, y2, 0, stops);
    }

    public static Gradient radial(float cx, float cy, float radius, List<Stop> stops) {
        return new Gradient(Kind.RADIAL, cx, cy, cx, cy, radius, stops);
    }

    public static Gradient radial(float fx, float fy, float cx, float cy, float radius, List<Stop> stops) {
        return new Gradient(Kind.RADIAL, fx, fy, cx, cy, radius, stops);
    }

    public Color average() {
        float r = 0;
        float g = 0;
        float b = 0;
        float a = 0;
        for (Stop s : stops) {
            r += s.color().getRed();
            g += s.color().getGreen();
            b += s.color().getBlue();
            a += s.color().getAlpha();
        }
        int n = stops.size();
        return new Color(Math.round(r / n), Math.round(g / n), Math.round(b / n), Math.round(a / n));
    }
}
