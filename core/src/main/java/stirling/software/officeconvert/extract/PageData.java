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
        List<Link> links,
        List<Widget> widgets) {

    public PageData(int index, float width, float height, int direction, List<Glyph> glyphs, List<Glyph> hidden,
            List<Glyph> rotated, PageGraphics graphics, List<Link> links) {
        this(index, width, height, direction, glyphs, hidden, rotated, graphics, links, List.of());
    }

    public record Widget(float x, float top, float right, float bottom, boolean choice) {}

    public record Link(float x, float top, float right, float bottom, String uri, int targetPage) {}
}
