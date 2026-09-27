package stirling.software.officeconvert.table;

import java.util.Arrays;
import java.util.List;

public record PageContent(
        int pageNumber,
        float width,
        float height,
        List<PdfWord> words,
        List<Ruling> horizontals,
        List<Ruling> verticals,
        List<FillBox> fills) {

    public record PdfWord(
            String text,
            float x,
            float right,
            float top,
            float bottom,
            float baseline,
            float fontSize,
            boolean bold,
            boolean italic,
            int rgb,
            float spaceWidth,
            float[] glyphLeft,
            float[] glyphRight,
            String[] glyphText) {

        float centreX() {
            return (x + right) / 2f;
        }

        float centreY() {
            return (top + bottom) / 2f;
        }

        float width() {
            return right - x;
        }

        PdfWord slice(int from, int to) {
            if (from >= to) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < to; i++) {
                sb.append(glyphText[i]);
            }
            float[] left = Arrays.copyOfRange(glyphLeft, from, to);
            float[] rightEdges = Arrays.copyOfRange(glyphRight, from, to);
            String[] texts = Arrays.copyOfRange(glyphText, from, to);
            float r = rightEdges[0];
            for (float e : rightEdges) {
                r = Math.max(r, e);
            }
            return new PdfWord(
                    sb.toString(),
                    left[0],
                    r,
                    top,
                    bottom,
                    baseline,
                    fontSize,
                    bold,
                    italic,
                    rgb,
                    spaceWidth,
                    left,
                    rightEdges,
                    texts);
        }
    }

    public record Ruling(float pos, float start, float end, float thickness, int rgb) {

        float length() {
            return end - start;
        }
    }

    public record FillBox(float x, float top, float right, float bottom, int rgb) {

        float area() {
            return (right - x) * (bottom - top);
        }
    }
}
