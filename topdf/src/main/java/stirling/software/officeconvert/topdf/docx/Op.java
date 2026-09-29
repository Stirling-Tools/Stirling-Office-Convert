package stirling.software.officeconvert.topdf.docx;

import java.awt.geom.AffineTransform;
import java.util.List;

import stirling.software.officeconvert.topdf.font.GlyphRun;
import stirling.software.officeconvert.topdf.io.DecodedPicture;
import stirling.software.officeconvert.topdf.pdf.Crop;
import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.Stroke;
import stirling.software.officeconvert.topdf.pdf.TextStyle;

sealed interface Op {

    record Text(float x, float baseline, String text, TextStyle style) implements Op {}

    record Glyphs(float x, float baseline, GlyphRun run, TextStyle style) implements Op {}

    record Rect(float x, float y, float w, float h, Fill fill, Stroke stroke) implements Op {}

    record Line(float x1, float y1, float x2, float y2, Stroke stroke) implements Op {}

    record Path(java.awt.Shape shape, Fill fill, Stroke stroke) implements Op {}

    record Image(DecodedPicture picture, float x, float y, float w, float h, Crop crop, float rotation, boolean flipH,
            boolean flipV, float alpha) implements Op {}

    record Link(float x, float y, float w, float h, String url, String anchor) implements Op {}

    record Dest(String name, float x, float y) implements Op {}

    record Group(float dx, float dy, AffineTransform transform, float[] clip, List<Op> ops) implements Op {}
}
