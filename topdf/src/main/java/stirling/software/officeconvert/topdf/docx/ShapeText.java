package stirling.software.officeconvert.topdf.docx;

import java.awt.geom.AffineTransform;
import java.util.List;

import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontMetrics;
import stirling.software.officeconvert.topdf.pdf.TextStyle;

final class ShapeText {

    private static final float SIZE = 100;

    private ShapeText() {}

    // Classic WordArt (a VML text path, as in Word's watermarks) is set at the shape's height as its em size and its
    // advance is stretched to the shape's width, the baseline where the font's Windows ascent puts it
    static void wordArt(Drawing.WordArt a, float x, float y, float w, float h, List<Op> ops, Ctx ctx) {
        if (w <= 0 || h <= 0 || a.text().isEmpty()) {
            return;
        }
        FontFace face = ctx.fonts.face(a.family(), a.bold(), a.italic());
        float advance = face.width(a.text(), SIZE);
        if (advance <= 0) {
            return;
        }
        FontMetrics m = face.metrics();
        float em = Math.max(1, m.unitsPerEm());
        int cell = m.winAscent() + m.winDescent();
        float ascent = cell > 0 ? (float) m.winAscent() / cell : 0.8f;
        float[] first = face.inkBounds(a.text().codePointAt(0));
        float lead = first == null ? 0 : first[0] * SIZE / em;
        AffineTransform t = new AffineTransform();
        t.translate(x, y + h * ascent);
        t.scale(w / advance, h / SIZE);
        t.translate(-lead, 0);
        TextStyle style = TextStyle.of(face, SIZE).color(a.fill());
        ops.add(new Op.Group(0, 0, t, null, List.of(new Op.Text(0, 0, a.text(), style))));
    }
}
