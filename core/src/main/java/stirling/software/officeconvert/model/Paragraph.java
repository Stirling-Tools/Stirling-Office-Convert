package stirling.software.officeconvert.model;

import java.util.ArrayList;
import java.util.List;

public final class Paragraph implements Block {

    public enum Align {
        LEFT,
        CENTER,
        RIGHT,
        JUSTIFY
    }

    public enum LineRule {
        EXACT,
        AT_LEAST,
        AUTO
    }

    public record TabStop(float pos, Kind kind, char leader) {

        public enum Kind {
            LEFT,
            CENTER,
            RIGHT,
            DECIMAL
        }
    }

    public record ListRef(int numId, int level) {}

    public final List<Inline> inlines = new ArrayList<>();
    public final List<TabStop> tabs = new ArrayList<>();
    public String style = "Normal";
    public Align align = Align.LEFT;
    public float indentLeft;
    public float indentRight;
    public float indentFirst;
    public float spaceBefore;
    public float spaceAfter;
    public float lineHeight;
    public LineRule lineRule = LineRule.AUTO;
    public ListRef list;
    public boolean pageBreakBefore;
    public boolean keepNext;
    public boolean bidi;
    public int shading = -1;
    public float borderBottom;
    public int borderBottomRgb;
    public Section endsSection;
    public int sourceLines = 1;
    public float textWidth;
    public float sourceTop = Float.NaN;
    public float sourceBottom = Float.NaN;
    public String bookmark;
    public RunStyle markStyle;

    public boolean isEmpty() {
        return inlines.isEmpty();
    }
}
