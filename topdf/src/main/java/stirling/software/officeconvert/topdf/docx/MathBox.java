package stirling.software.officeconvert.topdf.docx;

import java.util.ArrayList;
import java.util.List;

// A laid-out piece of an equation: drawing ops relative to its left edge on its baseline (y grows down)
final class MathBox {

    enum Kind {
        ORD,
        OP,
        BIN,
        REL,
        OPEN,
        CLOSE,
        PUNCT,
        INNER
    }

    float width;

    float ascent;

    float descent;

    Kind kind = Kind.ORD;

    // Room to leave after an italic letter before a superscript
    float italicCorrection;

    final List<Op> ops = new ArrayList<>();

    MathBox() {}

    MathBox(float width, float ascent, float descent) {
        this.width = width;
        this.ascent = ascent;
        this.descent = descent;
    }

    float height() {
        return ascent + descent;
    }

    void place(MathBox child, float dx, float dy) {
        if (child.ops.isEmpty()) {
            return;
        }
        if (dx == 0 && dy == 0) {
            ops.addAll(child.ops);
        } else {
            ops.add(new Op.Group(dx, dy, null, null, child.ops));
        }
    }
}
