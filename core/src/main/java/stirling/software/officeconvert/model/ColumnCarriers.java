package stirling.software.officeconvert.model;

import java.io.IOException;

public final class ColumnCarriers {

    private static final float HAIRLINE = 1f;

    public interface Write {
        void block(Block block) throws IOException;
    }

    private final Write write;

    private Paragraph carrier;

    public ColumnCarriers(Write write) {
        this.write = write;
    }

    public void block(Block block) throws IOException {
        if (block instanceof Paragraph p && carrier(p)) {
            flush();
            carrier = p;
        } else if (carrier != null && block instanceof Paragraph p && spacer(p)) {
            carrier = null;
            spacerWithBreak(p);
        } else if (carrier != null && block instanceof Paragraph p && takesBreak(p)) {
            carrier = null;
            withBreakFirst(p);
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
    }

    private void spacerWithBreak(Paragraph p) throws IOException {
        float line = p.lineHeight;
        float before = p.spaceBefore;
        float after = p.spaceAfter;
        p.inlines.addFirst(new Inline.ColumnBreak());
        p.spaceBefore = 0;
        p.spaceAfter = before + after + Math.max(0, line - HAIRLINE);
        p.lineHeight = Math.min(line, HAIRLINE);
        try {
            write.block(p);
        } finally {
            p.inlines.removeFirst();
            p.lineHeight = line;
            p.spaceBefore = before;
            p.spaceAfter = after;
        }
    }

    private void withBreakFirst(Paragraph p) throws IOException {
        p.inlines.addFirst(new Inline.ColumnBreak());
        try {
            write.block(p);
        } finally {
            p.inlines.removeFirst();
        }
    }

    static boolean carrier(Paragraph p) {
        return p.inlines.size() == 1 && p.inlines.getFirst() instanceof Inline.ColumnBreak && p.endsSection == null
                && p.bookmark == null && p.anchors.isEmpty() && !p.pageBreakBefore;
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
