package stirling.software.officeconvert.model;

import java.io.IOException;

public final class ColumnCarriers {

    private ColumnCarriers() {}

    public static boolean carrier(Paragraph p) {
        return p.inlines.size() == 1 && p.inlines.getFirst() instanceof Inline.ColumnBreak && p.endsSection == null
                && p.bookmark == null && p.anchors.isEmpty() && !p.pageBreakBefore;
    }

    public static boolean takesBreak(Paragraph p) {
        return !p.pageBreakBefore && !p.inlines.isEmpty() && !(p.inlines.getFirst() instanceof Inline.ColumnBreak);
    }

    public interface Write {
        void run() throws IOException;
    }

    public static void withBreakFirst(Paragraph p, Write write) throws IOException {
        p.inlines.addFirst(new Inline.ColumnBreak());
        try {
            write.run();
        } finally {
            p.inlines.removeFirst();
        }
    }
}
