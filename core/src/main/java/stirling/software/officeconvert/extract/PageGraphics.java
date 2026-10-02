package stirling.software.officeconvert.extract;

import java.awt.Shape;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;

public record PageGraphics(
        List<Rule> rules,
        List<Fill> fills,
        List<ImageDraw> images,
        List<VectorMark> marks,
        List<Area> pastBudget,
        Map<Object, Integer> paintOrder,
        Set<Object> seeThroughPaint,
        Map<Object, Outline> outlines,
        List<Area> masked) {

    public PageGraphics(List<Rule> rules, List<Fill> fills, List<ImageDraw> images, List<VectorMark> marks) {
        this(rules, fills, images, marks, List.of(), Map.of(), Set.of(), Map.of(), List.of());
    }

    public record Area(float x, float top, float right, float bottom) {}

    public record Outline(Shape path, int rgb, float alpha) {}

    public Outline outline(Object painted) {
        return painted == null ? null : outlines.get(painted);
    }

    public boolean seeThrough(Object painted) {
        return seeThroughPaint.contains(painted);
    }

    public int painted() {
        int last = -1;
        for (int at : paintOrder.values()) {
            last = Math.max(last, at);
        }
        return last + 1;
    }

    public int order(Object painted) {
        Integer at = painted == null ? null : paintOrder.get(painted);
        return at == null ? Integer.MAX_VALUE : at;
    }

    public record Rule(
            boolean horizontal, float pos, float start, float end, float thickness, int rgb) {

        public float length() {
            return end - start;
        }
    }

    public record Fill(float x, float top, float right, float bottom, int rgb) {

        public float width() {
            return right - x;
        }

        public float height() {
            return bottom - top;
        }

        public float area() {
            return width() * height();
        }
    }

    public record ImageDraw(
            float x,
            float top,
            float right,
            float bottom,
            float clipX,
            float clipTop,
            float clipRight,
            float clipBottom,
            PDImage image,
            COSBase key,
            int quarterTurns,
            boolean flipH,
            boolean flipV,
            boolean skewed,
            int stencilRgb,
            float alpha) {

        public float visibleWidth() {
            return clipRight - clipX;
        }

        public float visibleHeight() {
            return clipBottom - clipTop;
        }
    }

    public record VectorMark(
            float x,
            float top,
            float right,
            float bottom,
            int segments,
            boolean curved,
            boolean diagonal,
            boolean filled,
            boolean shading,
            int rgb,
            boolean stroked,
            int strokeRgb,
            float lineWidth,
            boolean round,
            boolean boxy,
            boolean sparse) {

        public VectorMark(float x, float top, float right, float bottom, int segments, boolean curved, boolean diagonal,
                boolean filled, boolean shading, int rgb, boolean stroked, int strokeRgb, float lineWidth, boolean round) {
            this(x, top, right, bottom, segments, curved, diagonal, filled, shading, rgb, stroked, strokeRgb, lineWidth, round,
                    false, false);
        }

        public float width() {
            return right - x;
        }

        public float height() {
            return bottom - top;
        }
    }
}
