package stirling.software.officeconvert.sheet;

import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.LogicalOrder;

final class LogicalText {

    private LogicalText() {}

    static String of(Line line, int from) {
        return LogicalOrder.text(line, from);
    }

    static String of(Line line, int from, boolean rtlBase) {
        return LogicalOrder.text(line, from, rtlBase || LogicalOrder.rtlBase(line));
    }

    static boolean isRtl(Glyph g) {
        return LogicalOrder.isRtl(g.text);
    }
}
