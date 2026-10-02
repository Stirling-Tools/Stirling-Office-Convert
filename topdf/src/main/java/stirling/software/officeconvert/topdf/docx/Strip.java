package stirling.software.officeconvert.topdf.docx;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

final class Strip {

    // cellX and cellW are the text area of the cell the object sits in; cell is the cell's own outline, or null
    record Anchor(Drawing drawing, float paraX, float paraTop, float cellX, float cellW, Rectangle2D.Float cell) {

        Anchor(Drawing drawing, float paraX, float paraTop) {
            this(drawing, paraX, paraTop, 0, -1, null);
        }

        Anchor shift(float dx, float dy) {
            return new Anchor(drawing, paraX + dx, paraTop + dy, cellX + dx, cellW,
                    cell == null ? null : new Rectangle2D.Float(cell.x + dx, cell.y + dy, cell.width, cell.height));
        }

        boolean inCell() {
            return cellW >= 0;
        }
    }

    float height;

    int line = -1;

    int lines;

    boolean widow;

    float baseline = Float.NaN;

    boolean numbered;

    boolean keepLines;

    boolean keepNext;

    final List<Op> ops = new ArrayList<>();

    List<Inline.NoteRef> notes;

    List<Anchor> anchors;

    // A table row inside a cell can split at a height, giving the part that fits and the rest
    Function<Float, Strip[]> splitter;

    void anchor(Anchor a) {
        if (anchors == null) {
            anchors = new ArrayList<>();
        }
        anchors.add(a);
    }

    void note(Inline.NoteRef n) {
        if (notes == null) {
            notes = new ArrayList<>();
        }
        if (!notes.contains(n)) {
            notes.add(n);
        }
    }
}
