package stirling.software.officeconvert.extract;

import java.util.List;

public record PageData(
        int index,
        float width,
        float height,
        int direction,
        List<Glyph> glyphs,
        List<Glyph> hidden,
        List<Glyph> rotated,
        PageGraphics graphics,
        List<Link> links) {

    public record Link(float x, float top, float right, float bottom, String uri, int targetPage) {}
}
