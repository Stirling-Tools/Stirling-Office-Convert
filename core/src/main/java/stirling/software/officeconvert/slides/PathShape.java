package stirling.software.officeconvert.slides;

import java.awt.geom.Path2D;

public record PathShape(Frame frame, Path2D path, int fillRgb, float alpha) implements SlideShape {}
