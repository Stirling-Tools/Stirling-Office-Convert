package stirling.software.officeconvert.slides;

import java.util.List;

public record TextShape(
        Frame frame,
        List<TextPara> paras,
        float insetLeft,
        float insetTop,
        float insetRight,
        int fillRgb,
        int lineRgb,
        float lineWidth,
        float radius,
        boolean wrap,
        boolean title,
        boolean vertical,
        boolean invisible)
        implements SlideShape {

    public TextShape(Frame frame, List<TextPara> paras, float insetLeft, float insetTop, float insetRight, int fillRgb,
            int lineRgb, float lineWidth, float radius, boolean wrap, boolean title) {
        this(frame, paras, insetLeft, insetTop, insetRight, fillRgb, lineRgb, lineWidth, radius, wrap, title, false);
    }

    public TextShape(Frame frame, List<TextPara> paras, float insetLeft, float insetTop, float insetRight, int fillRgb,
            int lineRgb, float lineWidth, float radius, boolean wrap, boolean title, boolean vertical) {
        this(frame, paras, insetLeft, insetTop, insetRight, fillRgb, lineRgb, lineWidth, radius, wrap, title, vertical,
                false);
    }

    public TextShape asTitle() {
        return new TextShape(frame, paras, insetLeft, insetTop, insetRight, fillRgb, lineRgb, lineWidth, radius, wrap,
                true, vertical, invisible);
    }

    public TextShape visible() {
        return new TextShape(frame, paras, insetLeft, insetTop, insetRight, fillRgb, lineRgb, lineWidth, radius, wrap,
                title, vertical, false);
    }
}
