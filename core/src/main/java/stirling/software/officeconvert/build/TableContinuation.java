package stirling.software.officeconvert.build;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Table;

final class TableContinuation {

    private static final float TOLERANCE = 3f;

    private TableContinuation() {}

    static boolean continues(Table prev, Table next) {
        if (prev.rightToLeft != next.rightToLeft || prev.columnWidths.size() != next.columnWidths.size()
                || Math.abs(prev.indent - next.indent) > TOLERANCE) {
            return false;
        }
        for (int i = 0; i < prev.columnWidths.size(); i++) {
            if (Math.abs(prev.columnWidths.get(i) - next.columnWidths.get(i)) > TOLERANCE) {
                return false;
            }
        }
        return !prev.rows.isEmpty() && !next.rows.isEmpty();
    }

    static boolean join(Table prev, Table next, String pageBookmark) {
        List<Table.Row> rows = next.rows;
        boolean remnant = headerRemnant(prev.rows.getFirst(), rows.getFirst());
        boolean header = text(rows.getFirst()).equals(text(prev.rows.getFirst())) && !text(rows.getFirst()).isBlank()
                || remnant;
        if (header) {
            rows = rows.subList(1, rows.size());
        }
        Table.Row cut = prev.rows.getLast();
        boolean cutRow = !remnant && !rows.isEmpty() && !blank(rows.getFirst()) && continuesRow(cut, rows.getFirst());
        if (!header && !cutRow) {
            return false;
        }
        if (header) {
            prev.rows.getFirst().header = true;
            if (!rows.isEmpty() && blank(rows.getFirst())) {
                rows = rows.subList(1, rows.size());
            }
        }
        Paragraph first = firstParagraph(rows);
        if (pageBookmark != null && first != null && first.bookmark == null) {
            first.bookmark = pageBookmark;
        }
        if (cutRow) {
            for (int i = 0; i < cut.cells.size(); i++) {
                cut.cells.get(i).paragraphs.addAll(rows.getFirst().cells.get(i).paragraphs);
            }
            cut.splits = true;
            cut.height = 0;
            cut.exactHeight = false;
            rows = rows.subList(1, rows.size());
        }
        prev.rows.addAll(rows);
        return true;
    }

    static float dropLeftover(Table next) {
        if (next.rows.size() < 2 || !blank(next.rows.getFirst())) {
            return 0;
        }
        return next.rows.removeFirst().height;
    }

    private static boolean headerRemnant(Table.Row header, Table.Row first) {
        if (!headerLike(header) || blank(first)) {
            return false;
        }
        Map<Integer, Table.Cell> heading = byColumn(header);
        boolean gap = false;
        int column = 0;
        for (Table.Cell cell : first.cells) {
            String text = text(cell).strip();
            if (text.isEmpty()) {
                gap = true;
            } else {
                Table.Cell above = heading.get(column);
                if (above == null || !text.equals(text(above).strip()) || !bold(cell)) {
                    return false;
                }
            }
            column += Math.max(1, cell.gridSpan);
        }
        return gap;
    }

    private static boolean headerLike(Table.Row row) {
        if (row.header) {
            return true;
        }
        boolean any = false;
        for (Table.Cell cell : row.cells) {
            if (!text(cell).isBlank()) {
                if (!bold(cell)) {
                    return false;
                }
                any = true;
            }
        }
        return any;
    }

    private static Map<Integer, Table.Cell> byColumn(Table.Row row) {
        Map<Integer, Table.Cell> out = new HashMap<>();
        int column = 0;
        for (Table.Cell cell : row.cells) {
            out.put(column, cell);
            column += Math.max(1, cell.gridSpan);
        }
        return out;
    }

    private static boolean bold(Table.Cell cell) {
        boolean any = false;
        for (Paragraph p : cell.paragraphs) {
            for (Inline in : p.inlines) {
                if (in instanceof Inline.Text t && !t.text().isBlank()) {
                    if (t.style() == null || !t.style().bold()) {
                        return false;
                    }
                    any = true;
                }
            }
        }
        return any;
    }

    private static boolean blank(Table.Row row) {
        return text(row).replace("|", "").isBlank();
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
