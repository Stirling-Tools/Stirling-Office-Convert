package stirling.software.officeconvert.model;

import java.util.List;

public sealed interface Inline {

    record Text(String text, RunStyle style, String link, int anchorPage) implements Inline {}

    record Tab(RunStyle style) implements Inline {}

    record Break() implements Inline {}

    record ColumnBreak() implements Inline {}

    record PageBreak() implements Inline {}

    record PageNumber(RunStyle style, boolean total) implements Inline {}

    record Image(Picture picture) implements Inline {}

    record FootnoteRef(int id, String marker, boolean custom, RunStyle style) implements Inline {}

    record FootnoteMark(String marker, boolean custom, RunStyle style) implements Inline {}

    record TextBox(
            float x,
            float y,
            float width,
            float height,
            int fillRgb,
            float insetLeft,
            float insetRight,
            float wrapGap,
            List<Paragraph> paragraphs,
            int lineRgb,
            float lineWidth,
            boolean rounded,
            boolean overlay,
            int direction,
            int groundRgb,
            boolean upright,
            boolean fromParagraph)
            implements Inline {

        public TextBox(float x, float y, float width, float height, int fillRgb, float insetLeft, float insetRight,
                float wrapGap, List<Paragraph> paragraphs, int lineRgb, float lineWidth, boolean rounded, boolean overlay,
                int direction, int groundRgb, boolean upright) {
            this(x, y, width, height, fillRgb, insetLeft, insetRight, wrapGap, paragraphs, lineRgb, lineWidth, rounded,
                    overlay, direction, groundRgb, upright, false);
        }

        public TextBox(float x, float y, float width, float height, int fillRgb, float insetLeft, float insetRight,
                float wrapGap, List<Paragraph> paragraphs, int lineRgb, float lineWidth, boolean rounded, boolean overlay) {
            this(x, y, width, height, fillRgb, insetLeft, insetRight, wrapGap, paragraphs, lineRgb, lineWidth, rounded,
                    overlay, 0, -1, false, false);
        }

        public TextBox(float x, float y, float width, float height, int fillRgb, float insetLeft, float insetRight,
                float wrapGap, List<Paragraph> paragraphs, int lineRgb, float lineWidth, boolean rounded, boolean overlay,
                int direction) {
            this(x, y, width, height, fillRgb, insetLeft, insetRight, wrapGap, paragraphs, lineRgb, lineWidth, rounded,
                    overlay, direction, -1, false, false);
        }
    }

    record FormControl(FormField field, Paragraph shown, float width, float height, float insetLeft, float insetTop,
            float insetRight, float insetBottom, int fillRgb, int lineRgb, float lineWidth, float radius,
            boolean ellipse, float lower)
            implements Inline {}

    record Shape(float x, float y, float width, float height, int rgb, int lineRgb, float lineWidth, boolean rounded,
            boolean ellipse, boolean fromParagraph, int z)
            implements Inline {

        public Shape(float x, float y, float width, float height, int rgb) {
            this(x, y, width, height, rgb, -1, 0, false, false, false, 0);
        }

        public Shape(float x, float y, float width, float height, int rgb, int lineRgb, float lineWidth, boolean rounded) {
            this(x, y, width, height, rgb, lineRgb, lineWidth, rounded, false, false, 0);
        }

        public Shape riding(float offset) {
            return new Shape(x, offset, width, height, rgb, lineRgb, lineWidth, rounded, ellipse, true, z);
        }
    }
}
