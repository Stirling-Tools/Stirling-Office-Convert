package stirling.software.officeconvert.model;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class ColumnCarriers {

    private static final int MAX_SPACERS = 3;

    public interface Write {
        void block(Block block) throws IOException;
    }

    private final Write write;

    private Paragraph carrier;

    private final List<Paragraph> spacers = new ArrayList<>();

    public ColumnCarriers(Write write) {
        this.write = write;
    }

    public void block(Block block) throws IOException {
        if (block instanceof Paragraph p && carrier(p)) {
            flush();
            carrier = p;
        } else if (carrier != null && block instanceof Paragraph p && spacer(p) && spacers.size() < MAX_SPACERS) {
            spacers.add(p);
        } else if (carrier != null && block instanceof Paragraph p && takesBreak(p)) {
            float room = 0;
            for (Paragraph s : spacers) {
                room += s.spaceBefore + s.lineHeight + s.spaceAfter;
            }
            carrier = null;
            spacers.clear();
            withBreakFirst(p, room);
        } else {
            flush();
            write.block(block);
        }
    }

    public void flush() throws IOException {
        if (carrier != null) {
            write.block(carrier);
            carrier = null;
        }
        for (Paragraph s : spacers) {
            write.block(s);
        }
        spacers.clear();
    }

    private void withBreakFirst(Paragraph p, float room) throws IOException {
        float before = p.spaceBefore;
        p.inlines.addFirst(new Inline.ColumnBreak());
        p.spaceBefore += room;
        try {
            write.block(p);
        } finally {
            p.inlines.removeFirst();
            p.spaceBefore = before;
        }
    }

    static boolean carrier(Paragraph p) {
        return p.inlines.size() == 1 && p.inlines.getFirst() instanceof Inline.ColumnBreak && p.endsSection == null
                && p.bookmark == null && !p.pageBreakBefore;
    }

    static boolean spacer(Paragraph p) {
        return p.inlines.isEmpty() && p.lineRule == Paragraph.LineRule.EXACT && p.endsSection == null
                && p.bookmark == null && p.anchors.isEmpty() && !p.pageBreakBefore && p.list == null
                && p.shading < 0 && p.borderBottom <= 0;
    }

    static boolean takesBreak(Paragraph p) {
        return !p.pageBreakBefore && !p.inlines.isEmpty() && !(p.inlines.getFirst() instanceof Inline.ColumnBreak);
    }
}
