package stirling.software.officeconvert.topdf.pdf;

import java.awt.Color;
import java.util.Arrays;
import java.util.Objects;

public record Stroke(float width, Color color, float[] dash, float dashPhase, Cap cap, Join join, float miterLimit) {

    public enum Cap {
        BUTT,
        ROUND,
        SQUARE
    }

    public enum Join {
        MITER,
        ROUND,
        BEVEL
    }

    public Stroke {
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(cap, "cap");
        Objects.requireNonNull(join, "join");
        if (!(width >= 0 && width <= 10_000)) {
            throw new IllegalArgumentException("The line width must be 0 to 10000 pt, was " + width);
        }
        if (!(miterLimit >= 1 && miterLimit <= 1000)) {
            throw new IllegalArgumentException("The miter limit must be 1 to 1000, was " + miterLimit);
        }
        if (!Float.isFinite(dashPhase)) {
            throw new IllegalArgumentException("The dash phase must be finite");
        }
        dash = dash == null ? new float[0] : dash.clone();
        float total = 0;
        for (float d : dash) {
            if (!(d >= 0 && d <= 10_000)) {
                throw new IllegalArgumentException("Dash lengths must be 0 to 10000 pt");
            }
            total += d;
        }
        if (dash.length > 0 && total == 0) {
            throw new IllegalArgumentException("A dash pattern cannot be all zero");
        }
    }

    public static Stroke solid(float width, Color color) {
        return new Stroke(width, color, null, 0, Cap.BUTT, Join.MITER, 10);
    }

    @Override
    public float[] dash() {
        return dash.clone();
    }

    public Stroke width(float w) {
        return new Stroke(w, color, dash, dashPhase, cap, join, miterLimit);
    }

    public Stroke color(Color c) {
        return new Stroke(width, c, dash, dashPhase, cap, join, miterLimit);
    }

    public Stroke dash(float phase, float... pattern) {
        return new Stroke(width, color, pattern, phase, cap, join, miterLimit);
    }

    public Stroke cap(Cap c) {
        return new Stroke(width, color, dash, dashPhase, c, join, miterLimit);
    }

    public Stroke join(Join j) {
        return new Stroke(width, color, dash, dashPhase, cap, j, miterLimit);
    }

    public Stroke miterLimit(float limit) {
        return new Stroke(width, color, dash, dashPhase, cap, join, limit);
    }

    float[] dashPattern() {
        return dash;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Stroke s && s.width == width && s.color.equals(color) && Arrays.equals(s.dash, dash)
                && s.dashPhase == dashPhase && s.cap == cap && s.join == join && s.miterLimit == miterLimit;
    }

    @Override
    public int hashCode() {
        return Objects.hash(width, color, Arrays.hashCode(dash), dashPhase, cap, join, miterLimit);
    }

    @Override
    public String toString() {
        return "Stroke[" + width + " pt, " + color + (dash.length > 0 ? ", dash " + Arrays.toString(dash) : "") + ", "
                + cap + ", " + join + "]";
    }
}
