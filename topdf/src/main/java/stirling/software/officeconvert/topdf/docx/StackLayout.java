package stirling.software.officeconvert.topdf.docx;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

final class StackLayout extends Region {

    record Result(List<Op> ops, float height, List<Placed> placed, List<Strip.Anchor> anchors,
            List<Inline.NoteRef> notes, List<Op> pageOps) {

        Result(List<Op> ops, float height, List<Placed> placed, List<Strip.Anchor> anchors,
                List<Inline.NoteRef> notes) {
            this(ops, height, placed, anchors, notes, List.of());
        }
    }

    private final float width;

    final List<Placed> placed = new ArrayList<>();

    private final List<Strip.Anchor> pending = new ArrayList<>();

    private final List<Strip.Anchor> anchors = new ArrayList<>();

    private final List<Inline.NoteRef> notes = new ArrayList<>();

    private float bottom;

    private SectionProps page;

    private float shift;

    private final List<Op> pageOps = new ArrayList<>();

    private boolean cell;

    private boolean note;

    private float pageTop = Float.NaN;

    private float grid;

    private final List<PageBox.Exclusion> exclusions = new ArrayList<>();

    StackLayout(float width) {
        this.width = width;
    }

    static StackLayout body(float width, float grid) {
        StackLayout s = new StackLayout(width);
        s.grid = grid;
        return s;
    }

    static StackLayout cell(float width, Ctx ctx) {
        StackLayout s = new StackLayout(width);
        s.cell = true;
        s.grid = ctx.settings.adjustLineHeightInTable ? ctx.bodyGrid() : 0;
        return s;
    }

    boolean inCell() {
        return cell;
    }

    boolean inNote() {
        return note;
    }

    // Text boxes in the body snap to the section's document grid, and table cells when the document asks
    @Override
    float gridPitch() {
        return note || page != null ? 0 : grid;
    }

    static Result layout(List<Block> blocks, float width, Ctx ctx) {
        StackLayout s = new StackLayout(width);
        s.grid = ctx.bodyGrid();
        new BlockFlow(ctx).place(blocks, s);
        return s.result();
    }

    static Result note(List<Block> blocks, float width, Ctx ctx) {
        StackLayout s = new StackLayout(width);
        s.note = true;
        new BlockFlow(ctx).place(blocks, s);
        return s.result();
    }

    // Headers and footers float their framed paragraphs, which take no room in the stack; top is NaN for a footer
    static Result header(List<Block> blocks, SectionProps page, float shift, float top, Ctx ctx) {
        StackLayout s = new StackLayout(page.textWidth());
        s.page = page;
        s.shift = shift;
        s.pageTop = top;
        new BlockFlow(ctx).place(blocks, s);
        return s.result();
    }

    boolean floatsFrames() {
        return page != null;
    }

    // Header content past the bottom of the page is never seen, so it is not laid out
    @Override
    boolean exhausted() {
        return page != null && Math.max(bottom, y) > page.pageH;
    }

    SectionProps page() {
        return page;
    }

    float pageLeft() {
        return page.bodyLeft() + shift;
    }

    void placeOnPage(List<Op> ops, float x, float y) {
        pageOps.add(new Op.Group(x, y, null, null, ops));
    }

    // Objects anchored in a frame placed on the page, in this stack's coordinates
    void anchorOnPage(List<Strip.Anchor> list, float x, float y) {
        if (list.isEmpty()) {
            return;
        }
        Strip s = new Strip();
        list.forEach(s::anchor);
        placeFloating(s, x - pageLeft(), y - (Float.isNaN(pageTop) ? 0 : pageTop));
    }

    void placeFloating(Strip s, float x, float y) {
        Placed p = new Placed(s, x, y, 0, 0, 0);
        p.fixed = true;
        placed.add(p);
    }

    Result result() {
        List<Op> ops = new ArrayList<>();
        for (Placed p : placed) {
            if (!p.strip.ops.isEmpty()) {
                ops.add(new Op.Group(p.x, p.y, null, null, p.strip.ops));
            }
            if (p.strip.anchors != null) {
                for (Strip.Anchor a : p.strip.anchors) {
                    anchors.add(a.shift(p.x, p.y));
                }
            }
            if (p.strip.notes != null) {
                for (Inline.NoteRef n : p.strip.notes) {
                    if (!notes.contains(n)) {
                        notes.add(n);
                    }
                }
            }
        }
        for (Strip.Anchor a : pending) {
            anchors.add(a);
        }
        return new Result(ops, Math.max(bottom, y), placed, anchors, notes, pageOps);
    }

    @Override
    float left() {
        return 0;
    }

    @Override
    float width() {
        return width;
    }

    @Override
    float limit() {
        return Float.MAX_VALUE / 4;
    }

    @Override
    float frameHeight() {
        return Float.MAX_VALUE / 4;
    }

    @Override
    boolean paginated() {
        return false;
    }

    @Override
    boolean atTop() {
        return placed.isEmpty();
    }

    @Override
    boolean softTop() {
        return false;
    }

    @Override
    void place(Strip s, float x, float y, float gap) {
        if (!pending.isEmpty()) {
            for (Strip.Anchor a : pending) {
                s.anchor(a.shift(-x, -y));
            }
            pending.clear();
        }
        placed.add(new Placed(s, x, y, 0, 0, gap));
        bottom = Math.max(bottom, y + s.height);
    }

    @Override
    void newFrame(boolean page, boolean hard) {
    }

    @Override
    List<Object> anchor(Drawing d, Para p, float paraX, float paraTop) {
        Strip.Anchor a = new Strip.Anchor(d, paraX, paraTop);
        pending.add(a);
        boolean wraps = d.wraps() && !d.wrap.equals("topAndBottom") && !PageFlow.emptyPolygon(d);
        PageBox.Exclusion e = page == null || !wraps ? null : exclusion(d, paraX, paraTop);
        if (e == null) {
            return List.of(a);
        }
        exclusions.add(e);
        return List.of(a, e);
    }

    // Header and footer text wraps beside the objects anchored in it; Word keeps it level with top and bottom ones
    private PageBox.Exclusion exclusion(Drawing d, float paraX, float paraTop) {
        boolean follows = d.vRel == null || d.vRel.equals("paragraph") || d.vRel.equals("line");
        if (!follows && Float.isNaN(pageTop) || d.height > (page.pageH - page.top - page.bottom) / 2) {
            return null;
        }
        float top = Float.isNaN(pageTop) ? 0 : pageTop;
        float left = pageLeft();
        FloatLayout.Frame frame = new FloatLayout.Frame(page.pageW, page.pageH, left, page.right - shift, page.top,
                page.bottom, left, width);
        Rectangle2D.Float b = PageFlow.wrapBox(d, FloatLayout.position(d, frame, paraX, top + paraTop));
        b.x -= left;
        b.y -= top;
        return new PageBox.Exclusion(b, d.wrapSide, false, null);
    }

    @Override
    void unanchor(List<Object> handles) {
        pending.removeAll(handles);
        exclusions.removeAll(handles);
    }

    @Override
    boolean wraps() {
        return !exclusions.isEmpty();
    }

    @Override
    float[] spans(float top, float h, float x0, float x1, float minWidth) {
        return PageFlow.spans(exclusions, top, h, x0, x1, minWidth);
    }
}
