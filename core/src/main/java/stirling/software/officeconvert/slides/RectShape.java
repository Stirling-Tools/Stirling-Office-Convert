package stirling.software.officeconvert.slides;

public record RectShape(Frame frame, int fillRgb, int lineRgb, float lineWidth, float radius, float alpha)
        implements SlideShape {}
