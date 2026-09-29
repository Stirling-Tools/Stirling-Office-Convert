package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;
import java.util.List;

import stirling.software.officeconvert.topdf.pdf.Crop;
import stirling.software.officeconvert.topdf.pdf.Gradient;
import stirling.software.officeconvert.topdf.pdf.Stroke;

final class Drawing {

    sealed interface Graphic {
    }

    record Picture(String part, Crop crop, float rotation, boolean flipH, boolean flipV, float alpha, Stroke outline,
            Chart.Shadow shadow) implements Graphic {

        Picture(String part, Crop crop, float rotation, boolean flipH, boolean flipV, float alpha, Stroke outline) {
            this(part, crop, rotation, flipH, flipV, alpha, outline, null);
        }
    }

    record Shape(String geometry, XEl geom, Color fill, Stroke line, float rotation, boolean flipH, boolean flipV,
            TextBox text, LineEnds ends, Shade shade) implements Graphic {

        Shape(String geometry, XEl geom, Color fill, Stroke line, float rotation, boolean flipH, boolean flipV,
                TextBox text) {
            this(geometry, geom, fill, line, rotation, flipH, flipV, text, null, null);
        }
    }

    // A gradient fill: stops from 0 to 1, a linear angle in degrees (clockwise from left to right) or a path kind
    record Shade(List<Gradient.Stop> stops, float angle, String path) {}

    // Arrowheads at the start (head) and end (tail) of a line: type and width and length as sm, med or lg
    record LineEnds(String head, String headW, String headL, String tail, String tailW, String tailL) {}

    record TextBox(List<Block> blocks, float left, float top, float right, float bottom, String anchor, boolean grow,
            boolean noWrap, String vert) {

        boolean vertical() {
            return vert != null && !vert.equals("horz");
        }

        boolean clockwise() {
            return vertical() && !vert.equals("vert270") && !vert.equals("wordArtVertRtl");
        }
    }

    record Child(Graphic graphic, float x, float y, float w, float h) {}

    record Group(List<Child> children, float rotation, boolean flipH, boolean flipV, float baseW, float baseH)
            implements Graphic {}

    record Placeholder(String kind) implements Graphic {}

    record WordArt(String text, String family, boolean bold, boolean italic, Color fill, float rotation, boolean flipH,
            boolean flipV) implements Graphic {}

    record ChartGraphic(Chart chart) implements Graphic {}

    record MathGraphic(MathBox box) implements Graphic {}

    boolean inline = true;
    float width;
    float height;
    String hRel = "column";
    String vRel = "paragraph";
    String hAlign;
    String vAlign;
    float pctWidth;
    String pctWidthFrom;
    float pctHeight;
    String pctHeightFrom;
    float hOffset;
    float vOffset;
    Float hPct;
    Float vPct;
    String wrap = "none";
    String wrapSide = "bothSides";
    boolean behind;
    long z;
    float distT;
    float distB;
    float distL;
    float distR;
    float effL;
    float effT;
    float effR;
    float effB;
    boolean layoutInCell = true;
    boolean hidden;
    Graphic graphic;
    // Bounds of a tight or through wrap polygon as fractions of the size: left, top, right, bottom
    float[] wrapBounds;
    // The tight or through wrap polygon itself, as x and y fraction pairs
    float[] wrapPoints;

    float rulePct;

    // Depth below the text baseline of an inline equation; negative for objects that sit on the text descent
    float baselineDepth = -1;

    boolean wraps() {
        return !inline && !wrap.equals("none") && !wrap.equals("inline");
    }
}
