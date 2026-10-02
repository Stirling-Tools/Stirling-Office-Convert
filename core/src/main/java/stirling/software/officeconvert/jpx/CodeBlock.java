package stirling.software.officeconvert.jpx;

import java.util.ArrayList;
import java.util.List;

final class CodeBlock {

    final int x0;

    final int y0;

    final int x1;

    final int y1;

    boolean included;

    int zeroPlanes;

    int lblock = 3;

    int passes;

    final List<Segment> segments = new ArrayList<>(1);

    CodeBlock(int x0, int y0, int x1, int y1) {
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
    }

    Segment segment(int id, int firstPass) {
        if (!segments.isEmpty()) {
            Segment last = segments.get(segments.size() - 1);
            if (last.id == id) {
                return last;
            }
        }
        Segment s = new Segment(id, firstPass);
        segments.add(s);
        return s;
    }
}
