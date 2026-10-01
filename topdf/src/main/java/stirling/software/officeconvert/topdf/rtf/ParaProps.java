package stirling.software.officeconvert.topdf.rtf;

import java.util.ArrayList;
import java.util.List;

final class ParaProps implements Cloneable {

    static final int ALIGN = 0;
    static final int LEFT = 1;
    static final int RIGHT = 2;
    static final int FIRST = 3;
    static final int BEFORE = 4;
    static final int AFTER = 5;
    static final int LINE = 6;
    static final int BEFORE_AUTO = 7;
    static final int AFTER_AUTO = 8;
    static final int KEEP = 9;
    static final int KEEP_NEXT = 10;
    static final int PAGE_BREAK = 11;
    static final int WIDOW = 12;
    static final int NO_LINE = 13;
    static final int NO_HYPHEN = 14;
    static final int CONTEXTUAL = 15;
    static final int OUTLINE = 16;
    static final int TABS = 17;
    static final int BORDERS = 18;
    static final int SHADING = 19;
    static final int BIDI = 20;
    static final int LIST = 21;
    static final int SNAP = 22;
    static final int LAST = 22;

    long set;

    int style;
    String align = "left";
    int left;
    int right;
    int first;
    int before;
    int after;
    int line;
    boolean lineMultiple;
    boolean beforeAuto;
    boolean afterAuto;
    boolean keep;
    boolean keepNext;
    boolean pageBreak;
    boolean widow;
    boolean noLine;
    boolean noHyphen;
    boolean contextual;
    int outline;
    List<Tab> tabs = new ArrayList<>();
    Border top;
    Border leftBorder;
    Border bottom;
    Border rightBorder;
    Border between;
    Shading shade = new Shading();
    boolean bidi;
    int list;
    int level;
    boolean snap = true;

    boolean inTable;
    int depth = 1;
    Frame frame;

    boolean listSeen;
    int leftsAfterList;

    String tabAlign;
    String tabLeader;

    record Tab(int pos, String align, String leader) {}

    boolean has(int prop) {
        return (set & 1L << prop) != 0;
    }

    void mark(int prop) {
        set |= 1L << prop;
    }

    int tableDepth() {
        return inTable ? Math.max(1, depth) : 0;
    }

    ParaProps copy() {
        try {
            ParaProps p = (ParaProps) super.clone();
            p.tabs = new ArrayList<>(tabs);
            p.top = top == null ? null : top.copy();
            p.leftBorder = leftBorder == null ? null : leftBorder.copy();
            p.bottom = bottom == null ? null : bottom.copy();
            p.rightBorder = rightBorder == null ? null : rightBorder.copy();
            p.between = between == null ? null : between.copy();
            p.shade = shade.copy();
            p.frame = frame == null ? null : frame.copy();
            return p;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    void reset() {
        set = 0;
        style = 0;
        tabs = new ArrayList<>();
        top = null;
        leftBorder = null;
        bottom = null;
        rightBorder = null;
        between = null;
        shade = new Shading();
        inTable = false;
        depth = 1;
        frame = null;
        tabAlign = null;
        tabLeader = null;
        listSeen = false;
        leftsAfterList = 0;
    }

    void inherit(ParaProps s) {
        ParaProps c = s.copy();
        for (int i = 0; i <= LAST; i++) {
            if (c.has(i)) {
                mark(i);
            }
        }
        if (c.has(ALIGN)) {
            align = c.align;
        }
        if (c.has(LEFT)) {
            left = c.left;
        }
        if (c.has(RIGHT)) {
            right = c.right;
        }
        if (c.has(FIRST)) {
            first = c.first;
        }
        if (c.has(BEFORE)) {
            before = c.before;
        }
        if (c.has(AFTER)) {
            after = c.after;
        }
        if (c.has(LINE)) {
            line = c.line;
            lineMultiple = c.lineMultiple;
        }
        beforeAuto = c.has(BEFORE_AUTO) ? c.beforeAuto : beforeAuto;
        afterAuto = c.has(AFTER_AUTO) ? c.afterAuto : afterAuto;
        keep = c.has(KEEP) ? c.keep : keep;
        keepNext = c.has(KEEP_NEXT) ? c.keepNext : keepNext;
        pageBreak = c.has(PAGE_BREAK) ? c.pageBreak : pageBreak;
        widow = c.has(WIDOW) ? c.widow : widow;
        noLine = c.has(NO_LINE) ? c.noLine : noLine;
        noHyphen = c.has(NO_HYPHEN) ? c.noHyphen : noHyphen;
        contextual = c.has(CONTEXTUAL) ? c.contextual : contextual;
        outline = c.has(OUTLINE) ? c.outline : outline;
        if (c.has(TABS)) {
            tabs = c.tabs;
        }
        if (c.has(BORDERS)) {
            top = c.top;
            leftBorder = c.leftBorder;
            bottom = c.bottom;
            rightBorder = c.rightBorder;
            between = c.between;
        }
        if (c.has(SHADING)) {
            shade = c.shade;
        }
        bidi = c.has(BIDI) ? c.bidi : bidi;
        if (c.has(LIST)) {
            list = c.list;
            level = c.level;
        }
        snap = c.has(SNAP) ? c.snap : snap;
        if (c.frame != null) {
            frame = c.frame;
        }
    }
}
