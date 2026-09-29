package stirling.software.officeconvert.topdf.docx;

import java.util.List;

abstract class Region {

    float y;

    float lastAfter;

    Para lastPara;

    boolean lastBoxed;

    // The shading behind this region (a table cell's), which automatic text colour contrasts with
    java.awt.Color background;

    abstract float left();

    abstract float width();

    abstract float limit();

    abstract float frameHeight();

    abstract boolean paginated();

    abstract boolean atTop();

    abstract boolean softTop();

    boolean frameStart() {
        return atTop();
    }

    boolean exhausted() {
        return false;
    }

    abstract void place(Strip s, float x, float y, float gap);

    abstract void newFrame(boolean page, boolean hard);

    abstract List<Object> anchor(Drawing d, Para p, float paraX, float paraTop);

    abstract void unanchor(List<Object> handles);

    float[] spans(float top, float h, float x0, float x1, float minWidth) {
        return new float[] {top, x0, x1};
    }

    boolean wraps() {
        return paginated();
    }

    boolean anchorsFit(List<Object> handles) {
        return true;
    }

    float clearFloats(float top, float h, float x0, float x1) {
        return top;
    }

    float clearBox(float top, float h, float boxLeft, float boxRight) {
        return top;
    }

    float clearRow(float top, float h, float x0, float x1) {
        return clearFloats(top, h, x0, x1);
    }

    float rowLimit(float top, float x0, float x1) {
        return limit();
    }

    boolean notesFit(List<Inline.NoteRef> refs, float bottom) {
        return notesFit(refs, bottom, true);
    }

    boolean notesFit(List<Inline.NoteRef> refs, float bottom, boolean split) {
        return true;
    }

    void addNotes(List<Inline.NoteRef> refs, float after) {
    }

    boolean hardLimit() {
        return false;
    }

    boolean firstInFlow() {
        return lastPara == null && !lastBoxed;
    }
}
