package stirling.software.officeconvert.build;

import java.util.List;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Table;

final class TableContinuation {

    private static final float TOLERANCE = 3f;

    private TableContinuation() {}

    static boolean continues(Table prev, Table next) {
        if (prev.columnWidths.size() != next.columnWidths.size() || Math.abs(prev.indent - next.indent) > TOLERANCE) {
            return false;
        }
        for (int i = 0; i < prev.columnWidths.size(); i++) {
            if (Math.abs(prev.columnWidths.get(i) - next.columnWidths.get(i)) > TOLERANCE) {
                return false;
            }
        }
        return !prev.rows.isEmpty() && !next.rows.isEmpty();
    }

    static void join(Table prev, Table next, String pageBookmark) {
        List<Table.Row> rows = next.rows;
        if (text(rows.getFirst()).equals(text(prev.rows.getFirst())) && !text(rows.getFirst()).isBlank()) {
            prev.rows.getFirst().header = true;
            rows = rows.subList(1, rows.size());
        }
        Paragraph first = firstParagraph(rows);
        if (pageBookmark != null && first != null && first.bookmark == null) {
            first.bookmark = pageBookmark;
        }
        Table.Row cut = prev.rows.getLast();
        if (!rows.isEmpty() && continuesRow(cut, rows.getFirst())) {
            for (int i = 0; i < cut.cells.size(); i++) {
                cut.cells.get(i).paragraphs.addAll(rows.getFirst().cells.get(i).paragraphs);
            }
            cut.splits = true;
            cut.height = 0;
            cut.exactHeight = false;
            rows = rows.subList(1, rows.size());
        }
        prev.rows.addAll(rows);
    }

    private static Paragraph firstParagraph(List<Table.Row> rows) {
        for (Table.Row row : rows) {
            for (Table.Cell c : row.cells) {
                if (!c.paragraphs.isEmpty()) {
                    return c.paragraphs.getFirst();
                }
            }
        }
        return null;
    }

    private static boolean continuesRow(Table.Row cut, Table.Row next) {
        return cut.cells.size() == next.cells.size() && text(next.cells.getFirst()).isBlank();
    }

    private static String text(Table.Row row) {
        StringBuilder sb = new StringBuilder();
        for (Table.Cell c : row.cells) {
            sb.append(text(c)).append('|');
        }
        return sb.toString().strip();
    }

    private static String text(Table.Cell cell) {
        StringBuilder sb = new StringBuilder();
        for (Paragraph p : cell.paragraphs) {
            for (Inline in : p.inlines) {
                if (in instanceof Inline.Text t) {
                    sb.append(t.text());
                }
            }
        }
        return sb.toString();
    }
}
