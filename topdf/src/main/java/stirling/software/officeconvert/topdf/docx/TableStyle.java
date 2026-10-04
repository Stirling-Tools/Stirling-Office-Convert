package stirling.software.officeconvert.topdf.docx;

import java.util.LinkedHashMap;
import java.util.Map;

final class TableStyle {

    static final String[] ORDER = {"wholeTable", "band1Vert", "band2Vert", "band1Horz", "band2Horz", "firstCol",
        "lastCol", "firstRow", "lastRow", "neCell", "nwCell", "seCell", "swCell"};

    static final class Part {
        final ParaProps pPr = new ParaProps();
        final RunProps rPr = new RunProps();
        final TableProps tblPr = new TableProps();
        final RowProps trPr = new RowProps();
        final CellProps tcPr = new CellProps();

        void mergeFrom(Part o) {
            pPr.mergeFrom(o.pPr);
            rPr.mergeFrom(o.rPr);
            tblPr.mergeFrom(o.tblPr);
            trPr.mergeFrom(o.trPr);
            tcPr.mergeFrom(o.tcPr);
        }
    }

    static final TableStyle EMPTY = new TableStyle();

    final Part base = new Part();

    final Map<String, Part> conditional = new LinkedHashMap<>();

    void mergeFrom(TableStyle o) {
        base.mergeFrom(o.base);
        for (Map.Entry<String, Part> e : o.conditional.entrySet()) {
            conditional.computeIfAbsent(e.getKey(), k -> new Part()).mergeFrom(e.getValue());
        }
    }

    static Part parsePart(XEl holder, Theme theme) {
        Part p = new Part();
        p.pPr.apply(holder.child("w:pPr"), theme);
        p.rPr.apply(holder.child("w:rPr"), theme);
        p.tblPr.apply(holder.child("w:tblPr"), theme);
        p.trPr.apply(holder.child("w:trPr"));
        p.trPr.hidden = null;
        p.tcPr.apply(holder.child("w:tcPr"), theme);
        return p;
    }
}
