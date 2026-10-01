package stirling.software.officeconvert.topdf.rtf;

import java.util.ArrayList;
import java.util.List;

final class RowProps {

    static final int MAX_CELLS = 512;

    int left;
    int gap = -1;
    int height;
    boolean header;
    boolean keep;
    String align;
    boolean autofit;
    boolean rtl;
    Integer padLeft;
    Integer padRight;
    Integer padTop;
    Integer padBottom;
    Border top;
    Border leftBorder;
    Border bottom;
    Border rightBorder;
    Border insideH;
    Border insideV;
    int width;
    int widthType;
    int shadeColor = -1;
    TableFloat floating;
    List<CellDef> cells = new ArrayList<>();
    CellDef pending = new CellDef();

    RowProps copy() {
        RowProps r = new RowProps();
        r.left = left;
        r.gap = gap;
        r.height = height;
        r.header = header;
        r.keep = keep;
        r.align = align;
        r.autofit = autofit;
        r.rtl = rtl;
        r.padLeft = padLeft;
        r.padRight = padRight;
        r.padTop = padTop;
        r.padBottom = padBottom;
        r.top = top;
        r.leftBorder = leftBorder;
        r.bottom = bottom;
        r.rightBorder = rightBorder;
        r.insideH = insideH;
        r.insideV = insideV;
        r.width = width;
        r.widthType = widthType;
        r.shadeColor = shadeColor;
        r.floating = floating;
        r.cells = new ArrayList<>(cells);
        return r;
    }

    void addCell(int right) {
        if (cells.size() < MAX_CELLS) {
            pending.edge = right;
            cells.add(pending);
        }
        pending = new CellDef();
    }

    static final class CellDef {
        int edge;
        Border top;
        Border left;
        Border bottom;
        Border right;
        Shading shade = new Shading();
        String valign;
        boolean mergeFirst;
        boolean merged;
        boolean vmergeFirst;
        boolean vmerged;
        int width;
        int widthType;
        Integer padLeft;
        Integer padRight;
        Integer padTop;
        Integer padBottom;
        String direction;
        boolean noWrap;
    }
}
