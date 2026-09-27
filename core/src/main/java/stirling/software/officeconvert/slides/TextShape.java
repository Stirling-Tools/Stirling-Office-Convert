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
        boolean title)
        implements SlideShape {

    public TextShape asTitle() {
        return new TextShape(frame, paras, insetLeft, insetTop, insetRight, fillRgb, lineRgb, lineWidth, radius, wrap,
                true);
    }
}
