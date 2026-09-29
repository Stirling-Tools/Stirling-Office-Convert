package stirling.software.officeconvert.topdf.docx;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

final class PageBox {

    record FloatBox(Drawing drawing, Rectangle2D.Float box, List<Op> ops, boolean behind, long z) {}

    record Exclusion(Rectangle2D.Float box, String side, boolean band, FloatBox owner, WrapShape shape) {

        Exclusion(Rectangle2D.Float box, String side, boolean band, FloatBox owner) {
            this(box, side, band, owner, null);
        }
    }

    // A tight or through wrap polygon in page points, with the distances text keeps from it
    record WrapShape(float[] xy, float padL, float padR, float padT, float padB) {

        // The polygon's reach across a line from top to bottom, or null where it does not come that far
        float[] extent(float top, float bottom) {
            float a = top - padB;
            float b = bottom + padT;
            float lo = Float.MAX_VALUE;
            float hi = -Float.MAX_VALUE;
            int n = xy.length / 2;
            for (int i = 0; i < n; i++) {
                float x1 = xy[i * 2];
                float y1 = xy[i * 2 + 1];
                float x2 = xy[(i + 1) % n * 2];
                float y2 = xy[(i + 1) % n * 2 + 1];
                if (y1 >= a && y1 <= b) {
                    lo = Math.min(lo, x1);
                    hi = Math.max(hi, x1);
                }
                for (float yy : new float[] {a, b}) {
                    if ((y1 - yy) * (y2 - yy) < 0) {
                        float x = x1 + (yy - y1) * (x2 - x1) / (y2 - y1);
                        lo = Math.min(lo, x);
                        hi = Math.max(hi, x);
                    }
                }
            }
            return lo > hi ? null : new float[] {lo - padL, hi + padR};
        }
    }

    record NotePart(Inline.NoteRef ref, List<Placed> strips, float height, boolean continued) {}

    final SectionProps sect;

    final int section;

    final float w;

    final float h;

    int number;

    boolean firstOfSection;

    boolean blank;

    float bodyTop;

    float bodyBottom;

    final List<Placed> placed = new ArrayList<>();

    final List<FloatBox> floats = new ArrayList<>();

    final List<FloatBox> headerFloats = new ArrayList<>();

    final List<Exclusion> exclusions = new ArrayList<>();

    // The part of the exclusions that header and footer objects make; tables split at them instead of moving
    final List<Exclusion> marginal = new ArrayList<>();

    final Map<Drawing, Exclusion> seeded = new IdentityHashMap<>();

    final List<Inline.NoteRef> notes = new ArrayList<>();

    float noteHeight;

    final List<NotePart> noteParts = new ArrayList<>();

    boolean splitNote;

    final List<Op> noteOps = new ArrayList<>();

    final List<Op> decor = new ArrayList<>();

    List<Op> header = List.of();

    List<Op> footer = List.of();

    int sectionPages = 1;

    float shift;

    PageBox(SectionProps sect, int section) {
        this.sect = sect;
        this.section = section;
        this.w = sect.pageW;
        this.h = sect.pageH;
    }

    String display() {
        return NumberFormat.format(number, sect.pageNumberFormat);
    }
}
