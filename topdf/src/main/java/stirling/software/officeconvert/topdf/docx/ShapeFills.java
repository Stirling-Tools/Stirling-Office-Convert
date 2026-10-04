package stirling.software.officeconvert.topdf.docx;

import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.Gradient;

final class ShapeFills {

    private ShapeFills() {}

    // A shape's fill in its box: the gradient it names, else its solid colour
    static Fill fill(Drawing.Shape s, float x, float y, float w, float h) {
        Drawing.Shade shade = s.shade();
        if (shade == null || w <= 0 || h <= 0) {
            return Fill.solid(s.fill());
        }
        float cx = x + w / 2;
        float cy = y + h / 2;
        try {
            if (shade.path() != null) {
                float r = (float) Math.hypot(w, h) / 2;
                List<Gradient.Stop> inward = new ArrayList<>();
                for (Gradient.Stop st : shade.stops()) {
                    inward.add(new Gradient.Stop(st.offset(), st.color()));
                }
                return Fill.of(Gradient.radial(cx, cy, Math.max(0.1f, r), inward));
            }
            double a = Math.toRadians(shade.angle());
            float dx = (float) Math.cos(a);
            float dy = (float) Math.sin(a);
            float half = (Math.abs(w * dx) + Math.abs(h * dy)) / 2;
            return Fill.of(Gradient.linear(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
                    shade.stops()));
        } catch (IllegalArgumentException e) {
            return Fill.solid(s.fill());
        }
    }
}
