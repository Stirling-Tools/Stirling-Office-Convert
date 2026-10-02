package stirling.software.officeconvert.topdf.docx;

final class RowProps {

    Float height;
    String hRule;
    Boolean cantSplit;
    Boolean header;
    Boolean hidden;
    Integer gridBefore;
    Integer gridAfter;
    Float wBefore;
    Float wAfter;
    String jc;
    Float cellSpacing;

    void mergeFrom(RowProps o) {
        if (o == null) {
            return;
        }
        if (o.height != null) {
            height = o.height;
            hRule = o.hRule;
        }
        cantSplit = o.cantSplit != null ? o.cantSplit : cantSplit;
        header = o.header != null ? o.header : header;
        hidden = o.hidden != null ? o.hidden : hidden;
        gridBefore = o.gridBefore != null ? o.gridBefore : gridBefore;
        gridAfter = o.gridAfter != null ? o.gridAfter : gridAfter;
        wBefore = o.wBefore != null ? o.wBefore : wBefore;
        wAfter = o.wAfter != null ? o.wAfter : wAfter;
        jc = o.jc != null ? o.jc : jc;
        cellSpacing = o.cellSpacing != null ? o.cellSpacing : cellSpacing;
    }

    static RowProps parse(XEl trPr) {
        RowProps r = new RowProps();
        r.apply(trPr);
        return r;
    }

    void apply(XEl trPr) {
        if (trPr == null) {
            return;
        }
        for (XEl k : trPr.kids) {
            switch (k.name) {
                case "w:trHeight" -> {
                    height = Ooxml.twips(k.val());
                    hRule = k.attr("hRule", "atLeast");
                }
                case "w:cantSplit" -> cantSplit = Ooxml.on(k);
                case "w:tblHeader" -> header = Ooxml.on(k);
                case "w:hidden" -> hidden = Ooxml.on(k);
                case "w:gridBefore" -> gridBefore = Ooxml.integer(k.val());
                case "w:gridAfter" -> gridAfter = Ooxml.integer(k.val());
                case "w:wBefore" -> wBefore = TableProps.measure(k);
                case "w:wAfter" -> wAfter = TableProps.measure(k);
                case "w:jc" -> jc = k.val();
                case "w:tblCellSpacing" -> cellSpacing = Ooxml.twips(k.attr("w"), 0);
                default -> {
                }
            }
        }
    }
}
