package stirling.software.officeconvert.topdf.docx;

import java.util.List;

final class Para implements Block {

    final ParaProps pp;

    final RunProps mark;

    final List<Inline> items;

    final String styleId;

    final String label;

    final RunProps labelProps;

    final Numbering.Level level;

    SectionProps section;

    boolean joinsNext;

    // The paragraphs before and after this one in document order, across table cells
    Para docPrev;

    Para docNext;

    Para(ParaProps pp, RunProps mark, List<Inline> items, String styleId, String label, RunProps labelProps,
            Numbering.Level level) {
        this.pp = pp;
        this.mark = mark;
        this.items = items;
        this.styleId = styleId;
        this.label = label;
        this.labelProps = labelProps;
        this.level = level;
    }

    // An empty paragraph that carries a section break takes no line, even when it is numbered
    boolean sectionMark() {
        if (section == null) {
            return false;
        }
        for (Inline i : items) {
            if (!(i instanceof Inline.Bookmark)) {
                return false;
            }
        }
        return true;
    }

    boolean empty() {
        for (Inline i : items) {
            if (!(i instanceof Inline.Bookmark)) {
                return false;
            }
        }
        return label == null || label.isEmpty();
    }
}
