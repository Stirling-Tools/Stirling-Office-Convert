package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import stirling.software.officeconvert.topdf.pdf.Fill;

final class TableFlow {

    static final float DEFAULT_MARGIN = 5.4f;

    private final Ctx ctx;

    private final BlockFlow blocks;

    private float rowMark = -1;

    // The shading of the region the table sits in, for cells that have none of their own
    private Color background;

    TableFlow(Ctx ctx, BlockFlow blocks) {
        this.ctx = ctx;
        this.blocks = blocks;
    }

    static final class CellBox {
        TableBlock.Cell cell;
        float x;
        float w;
        float marL;
        float marR;
        float marT;
        float marB;
        List<Placed> content = new ArrayList<>();
        float contentH;
        Border top;
        Border bottom;
        Border left;
        Border right;
        // The borders this cell takes where a page break closes or reopens the table
        Border edgeTop;
        Border edgeBottom;
        Color fill;
        String vAlign;
        boolean continuation;
        int rowSpan = 1;
        float spanHeight;
        List<Strip.Anchor> anchors = new ArrayList<>();
        List<Inline.NoteRef> notes = new ArrayList<>();
        int rotation;
    }

    static final class RowBox {
        final List<CellBox> cells = new ArrayList<>();
        float height;
        boolean cantSplit;
        boolean header;
        boolean exact;
        float minHeight;
        float borderTop;
        float borderBottom;
        boolean finished;
        // Every cell starts a vertical merge; the row takes its end-of-row mark only if the merges span on
        boolean allStart;
        int index;
        boolean last;
        // Height added so a vertically merged cell above fits its content
        float grown;
    }

    private record Layout(float x, float[] colX, List<RowBox> rows, int headerRows, float spacing, TableProps tp) {

        Layout(float x, float[] colX, List<RowBox> rows, int headerRows) {
            this(x, colX, rows, headerRows, 0, null);
        }
    }

    // What a keep-with-next paragraph needs of the table after it: the first row, or only its first lines when the
    // row may break across pages
    float firstRowHeight(TableBlock t, float width) {
        if (t.rows.isEmpty()) {
            return 0;
        }
        // Repeating header rows only start a page with the first row they head
        int heads = 0;
        while (heads < t.rows.size() && Boolean.TRUE.equals(t.rows.get(heads).rp.header)) {
            heads++;
        }
        int count = heads > 0 && heads < t.rows.size() ? heads + 1 : 1;
        Layout l = layout(t, width, count);
        if (l.rows().isEmpty()) {
            return 0;
        }
        float h = 0;
        for (int k = 0; k < l.rows().size() - 1; k++) {
            h += l.rows().get(k).height;
        }
        return h + leadHeight(l.rows().get(l.rows().size() - 1));
    }

    // How many rows from this one on keep with the row after them, as every cell's last paragraph asks
    private static int keptRows(List<RowBox> rows, int from) {
        int k = from;
        while (k + 1 < rows.size() && keepsWithNext(rows.get(k))) {
            k++;
        }
        return k - from;
    }

    private static boolean keepsWithNext(RowBox row) {
        boolean any = false;
        for (CellBox cb : row.cells) {
            if (cb.continuation || cb.cell == null) {
                continue;
            }
            List<Block> blocks = cb.cell.blocks;
            if (blocks.isEmpty() || !(blocks.get(blocks.size() - 1) instanceof Para p)
                    || !Boolean.TRUE.equals(p.pp.keepNext)) {
                return false;
            }
            any = true;
        }
        return any;
    }

    private static float leadHeight(RowBox row) {
        if (row.exact || row.cantSplit || row.header) {
            return row.height;
        }
        float head = 0;
        for (CellBox cb : row.cells) {
            if (cb.content.isEmpty()) {
                continue;
            }
            // The first lines a row may break after, as widow control and keep rules allow
            int k = 1;
            while (k < cb.content.size() && keepSafe(cb.content, widowSafe(cb.content, k)) != k) {
                k++;
            }
            Placed p = cb.content.get(k - 1);
            head = Math.max(head, cb.marT + p.y + p.strip.height);
        }
        return Math.min(row.height, head + row.borderTop);
    }

    void place(TableBlock t, Region r) {
        if (t.rows.isEmpty()) {
            return;
        }
        Color outer = background;
        background = r.background;
        try {
            place(t, r, layout(t, r.width(), Integer.MAX_VALUE));
        } finally {
            background = outer;
        }
    }

    private void place(TableBlock t, Region r, Layout l) {
        if (t.tp.floating != null && r instanceof PageFlow pf) {
            floating(t, l, pf);
            return;
        }
        if (r.paginated() && !r.atTop() && breaksBefore(t)) {
            r.newFrame(true, true);
        }
        l = repeatable(l, r);
        if (r.paginated()) {
            dodgeFloats(l, r);
        }
        flow(l, r, 0, false, false);
    }

    // Word repeats header rows only when they fit on a page with the first line of the row they head
    private static Layout repeatable(Layout l, Region r) {
        if (l.headerRows() == 0 || !r.paginated()) {
            return l;
        }
        float need = leadHeight(l.rows().get(l.headerRows()));
        for (int k = 0; k < l.headerRows(); k++) {
            need += l.rows().get(k).height;
        }
        if (need <= r.frameHeight() + 0.01f) {
            return l;
        }
        for (int k = 0; k < l.headerRows(); k++) {
            l.rows().get(k).header = false;
        }
        // They still start the table on a fresh page, where they then break like any rows
        if (!r.atTop()) {
            r.newFrame(false, false);
        }
        return new Layout(l.x(), l.colX(), l.rows(), 0, l.spacing(), l.tp());
    }

    // A page break before the first paragraph of the first cell moves the whole table, as Word does
    private static boolean breaksBefore(TableBlock t) {
        List<TableBlock.Cell> cells = t.rows.get(0).cells;
        return !cells.isEmpty() && !cells.get(0).blocks.isEmpty() && cells.get(0).blocks.get(0) instanceof Para p
                && Boolean.TRUE.equals(p.pp.pageBreakBefore);
    }

    // Rows taller than the space left are split line by line and continue on the next frames, as Word does
    private void flow(Layout l, Region r, int from, boolean placedBefore, boolean opens) {
        List<RowBox> headers = new ArrayList<>(l.rows().subList(0, l.headerRows()));
        boolean placedAny = placedBefore;
        boolean edges = r.paginated() && l.spacing() <= 0;
        if (from == 0 && !placedBefore && r.paginated() && !r.atTop() && !headers.isEmpty()
                && headers.size() < l.rows().size()) {
            // Word never leaves repeating header rows at a page bottom without the first row they head
            float need = leadHeight(l.rows().get(headers.size()));
            for (RowBox h : headers) {
                need += h.height;
            }
            float left = r.left() + l.x();
            if (r.y + need > r.rowLimit(r.y, left, left + l.colX()[l.colX().length - 1]) + 0.01f) {
                r.newFrame(false, false);
            }
        }
        Strip lastStrip = null;
        RowBox lastRow = null;
        boolean opening = opens;
        for (int i = from; i < l.rows().size(); i++) {
            RowBox row = l.rows().get(i);
            RowBox current = row;
            int guard = 0;
            float tableLeft = r.left() + l.x();
            float tableRight = tableLeft + l.colX()[l.colX().length - 1];
            if (r.paginated() && !r.atTop() && i >= l.headerRows() && keptRows(l.rows(), i) > 0) {
                // Rows whose paragraphs all keep with the next go to the next page with the row they keep with
                int kept = keptRows(l.rows(), i);
                float need = leadHeight(l.rows().get(i + kept));
                for (int k = i; k < i + kept; k++) {
                    need += l.rows().get(k).height;
                }
                if (need <= r.frameHeight() && r.y + need > r.rowLimit(r.y, tableLeft, tableRight) + 0.01f) {
                    close(lastStrip, lastRow, l, edges);
                    r.newFrame(false, false);
                    opening = edges;
                    if (placedAny) {
                        for (RowBox h : headers) {
                            placeRow(h, l, r);
                            opening = false;
                        }
                    }
                    lastStrip = null;
                }
            }
            boolean fresh = r.atTop();
            while (current != null && guard++ < 10_000) {
                if (opening) {
                    current = edged(current, true, false);
                    opening = false;
                }
                if (r.paginated()) {
                    r.y = r.clearRow(r.y, current.height, tableLeft, tableRight);
                }
                float limit = r.rowLimit(r.y, tableLeft, tableRight);
                if (r.paginated() && r.y + current.height <= limit + 0.01f) {
                    carryMerged(l.rows(), i, current, r.y, limit);
                }
                // A row that ends the page must hold the table's bottom border too
                float closing = edges && !current.last ? edged(current, false, true).height : current.height;
                boolean room = r.y + current.height <= limit + 0.01f
                        && (closing <= current.height || r.y + closing <= limit + 0.01f);
                if (!r.paginated() || room && notesFit(current, r.y, r)) {
                    lastStrip = placeRow(current, l, r);
                    lastRow = current;
                    placedAny = true;
                    current = null;
                    continue;
                }
                boolean top = fresh || r.atTop();
                // A row whose set minimum height is more than the space left moves whole, as Word keeps it
                boolean tall = current.minHeight > limit - r.y + 0.01f;
                boolean splittable = !current.exact
                        && (top ? !current.header : !current.cantSplit && !current.header && !tall);
                RowBox[] parts = splittable ? splitWithNotes(current, limit - r.y, r, !top) : null;
                if (parts == null) {
                    if (top) {
                        lastStrip = placeRow(current, l, r);
                        lastRow = current;
                        placedAny = true;
                        current = null;
                        continue;
                    }
                    close(lastStrip, lastRow, l, edges);
                    r.newFrame(false, false);
                    lastStrip = null;
                    fresh = true;
                    opening = edges;
                    if (placedAny && i >= l.headerRows()) {
                        for (RowBox h : headers) {
                            placeRow(h, l, r);
                            opening = false;
                        }
                    }
                    continue;
                }
                placeRow(edges ? edged(parts[0], false, true) : parts[0], l, r);
                placedAny = true;
                current = parts[1];
                spanOn(current, row, i, l.rows());
                r.newFrame(false, false);
                lastStrip = null;
                fresh = true;
                opening = edges;
                if (i >= l.headerRows()) {
                    for (RowBox h : headers) {
                        if (h != row) {
                            placeRow(h, l, r);
                            opening = false;
                        }
                    }
                }
            }
        }
        r.lastPara = null;
        r.lastAfter = 0;
        r.lastBoxed = true;
    }

    // The row left last on a page before the table goes on takes the table's bottom border
    private void close(Strip placed, RowBox row, Layout l, boolean edges) {
        if (!edges || placed == null || row == null || row.last || row.header) {
            return;
        }
        Strip s = render(edged(row, false, true), l);
        placed.ops.clear();
        placed.ops.addAll(s.ops);
        placed.height = s.height;
    }

    // A merged cell whose rows go on past the page shows what fits beside the rows on this page; the rest of its
    // content moves to its first row on the next page, all of it when not even its first line fits here
    private static void carryMerged(List<RowBox> rows, int index, RowBox row, float top, float limit) {
        boolean reset = false;
        List<CellBox> carried = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        for (CellBox cb : row.cells) {
            int span = cb.rowSpan;
            if (span <= 1 || cb.continuation || cb.cell == null || index + span > rows.size()) {
                continue;
            }
            RowBox lastRow = rows.get(index + span - 1);
            float y = top + row.height;
            int m = 1;
            while (m < span) {
                float h = rows.get(index + m).height - (m == span - 1 ? lastRow.grown : 0);
                if (y + h > limit + 0.01f) {
                    break;
                }
                y += h;
                m++;
            }
            if (m >= span) {
                // Only the height the merged content adds to its last row runs past the page: that row carries it
                if (y + lastRow.grown <= limit + 0.01f || span < 2) {
                    continue;
                }
                m = span - 1;
                y -= rows.get(index + m).height - lastRow.grown;
            }
            float room = y - top - cb.marT - cb.marB - row.borderTop;
            int k = 0;
            while (k < cb.content.size() && cb.content.get(k).y + cb.content.get(k).strip.height <= room + 0.01f) {
                k++;
            }
            k = cb.rotation != 0 ? cb.content.size() : widowSafe(cb.content, k);
            CellBox next = at(rows.get(index + m), cb.cell.col);
            if (next == null || !next.continuation) {
                continue;
            }
            float shift = k == 0 ? 0 : k < cb.content.size() ? cb.content.get(k).y : cb.contentH;
            boolean split = k > 0 && k < cb.content.size();
            CellBox carry = copy(cb);
            carry.continuation = false;
            carry.rotation = 0;
            carry.top = null;
            carry.vAlign = split ? "top" : cb.vAlign;
            carry.rowSpan = span - m;
            for (int j = k; j < cb.content.size(); j++) {
                Placed p = cb.content.get(j);
                carry.content.add(new Placed(p.strip, p.x, p.y - shift, 0, 0, p.gap));
            }
            carry.contentH = Math.max(contentHeight(carry.content), cb.contentH - shift);
            carry.anchors.clear();
            cb.content = new ArrayList<>(cb.content.subList(0, k));
            cb.contentH = contentHeight(cb.content);
            cb.rowSpan = m;
            cb.vAlign = split ? "top" : cb.vAlign;
            CellBox end = at(rows.get(index + m - 1), cb.cell.col);
            cb.bottom = end == null ? null : end.bottom;
            cb.spanHeight = y - top;
            RowBox first = rows.get(index + m);
            first.cells.set(first.cells.indexOf(next), carry);
            if (!reset) {
                lastRow.height -= lastRow.grown;
                lastRow.grown = 0;
                reset = true;
            }
            float need = carry.contentH + carry.marT + carry.marB + first.borderTop + first.borderBottom;
            float have = first.height + below(rows, index + m, span - m);
            if (need > have && !lastRow.exact) {
                lastRow.height += need - have;
                lastRow.grown += need - have;
            }
            carried.add(carry);
            starts.add(index + m);
        }
        for (int c = 0; c < carried.size(); c++) {
            CellBox carry = carried.get(c);
            carry.spanHeight = rows.get(starts.get(c)).height + below(rows, starts.get(c), carry.rowSpan);
        }
    }

    // The rest of a vertically merged cell runs on over the rows it spans, not only over the rest of its first row
    private static void spanOn(RowBox rest, RowBox original, int index, List<RowBox> rows) {
        int n = Math.min(rest.cells.size(), original.cells.size());
        float others = 0;
        boolean any = false;
        for (int c = 0; c < n; c++) {
            CellBox b = rest.cells.get(c);
            int span = original.cells.get(c).rowSpan;
            if (span > 1 && index + span <= rows.size()) {
                any = true;
                RowBox lastRow = rows.get(index + span - 1);
                lastRow.height -= lastRow.grown;
                lastRow.grown = 0;
            } else {
                others = Math.max(others, b.contentH + b.marT + b.marB + rest.borderTop + rest.borderBottom);
            }
        }
        if (!any) {
            return;
        }
        rest.height = others;
        for (int c = 0; c < n; c++) {
            CellBox b = rest.cells.get(c);
            int span = original.cells.get(c).rowSpan;
            if (span <= 1 || index + span > rows.size()) {
                continue;
            }
            float need = b.contentH + b.marT + b.marB + rest.borderTop + rest.borderBottom;
            float have = rest.height + below(rows, index, span);
            RowBox lastRow = rows.get(index + span - 1);
            if (need > have && !lastRow.exact) {
                lastRow.height += need - have;
                lastRow.grown += need - have;
            } else if (need > have) {
                rest.height += need - have;
            }
        }
        for (int c = 0; c < n; c++) {
            CellBox b = rest.cells.get(c);
            int span = original.cells.get(c).rowSpan;
            if (span > 1 && index + span <= rows.size()) {
                b.rowSpan = span;
                b.spanHeight = rest.height + below(rows, index, span);
            }
        }
    }

    private static float below(List<RowBox> rows, int index, int span) {
        float h = 0;
        for (int k = 1; k < span; k++) {
            h += rows.get(index + k).height;
        }
        return h;
    }

    // The footnotes referenced in a row, or in the part of it left on this page, must fit on the same page
    private static boolean notesFit(RowBox row, float top, Region r) {
        List<Inline.NoteRef> refs = new ArrayList<>();
        for (CellBox cb : row.cells) {
            for (Placed p : cb.content) {
                if (p.strip.notes != null) {
                    refs.addAll(p.strip.notes);
                }
            }
        }
        return refs.isEmpty() || r.notesFit(refs, top + row.height);
    }

    private RowBox[] straddling(RowBox row, float avail) {
        if (row.exact || row.cantSplit || row.header || row.minHeight > avail + 0.01f) {
            return null;
        }
        for (CellBox cb : row.cells) {
            if (cb.rowSpan > 1 || !cb.notes.isEmpty()) {
                return null;
            }
        }
        return split(row, avail, true);
    }

    private RowBox[] splitWithNotes(RowBox row, float avail, Region r, boolean keeps) {
        float room = avail;
        for (int tries = 0; tries < 60 && room >= 12; tries++) {
            RowBox[] parts = split(row, room, keeps);
            if (parts == null || notesFit(parts[0], r.y, r)) {
                return parts;
            }
            room -= 6;
        }
        return null;
    }

    private static void dodgeFloats(Layout l, Region r) {
        float left = r.left() + l.x();
        float right = left + l.colX()[l.colX().length - 1];
        for (int guard = 0; guard < 8; guard++) {
            float h = 0;
            for (RowBox row : l.rows()) {
                if (h > 0 && r.y + h + row.height > r.limit()) {
                    break;
                }
                h += row.height;
            }
            float y = r.clearBox(r.y, h, left, right);
            if (y <= r.y + 0.01f) {
                return;
            }
            r.y = y;
        }
    }

    private void floating(TableBlock t, Layout l, PageFlow pf) {
        XEl f = t.tp.floating;
        float width = l.colX()[l.colX().length - 1];
        float height = 0;
        for (RowBox row : l.rows()) {
            height += row.height;
        }
        String horz = f.attr("horzAnchor");
        // Without a vertical anchor Word measures a floating table from the top margin
        String vert = f.attr("vertAnchor", "margin");
        float[] origin = pf.floatingOrigin(horz, vert);
        SectionProps s = pf.section();
        float areaW = "page".equals(horz) ? s.pageW : "margin".equals(horz) ? s.textWidth() : pf.width();
        float areaH = "page".equals(vert) ? s.pageH : s.pageH - s.top - s.bottom;
        float x = origin[0];
        String xs = f.attr("tblpXSpec");
        if (xs != null) {
            x += switch (xs) {
                case "center" -> (areaW - width) / 2;
                case "right", "outside" -> areaW - width;
                default -> 0;
            };
        } else {
            x += Ooxml.twips(f.attr("tblpX"), 0) + l.x();
        }
        float y = origin[1];
        String ys = f.attr("tblpYSpec");
        if (ys != null && !"inline".equals(ys)) {
            y += switch (ys) {
                case "center" -> (areaH - height) / 2;
                case "bottom", "outside" -> areaH - height;
                default -> 0;
            };
        } else {
            y += Ooxml.twips(f.attr("tblpY"), 0);
        }
        float at = y;
        int fit = 0;
        float fitted = 0;
        boolean flows = at < pf.limit();
        while (fit < l.rows().size() && (!flows || at + fitted + l.rows().get(fit).height <= pf.limit() + 0.01f)) {
            fitted += l.rows().get(fit).height;
            fit++;
        }
        RowBox[] parts = flows && fit > l.headerRows() && fit < l.rows().size()
                ? straddling(l.rows().get(fit), pf.limit() - at - fitted) : null;
        if (fit < l.rows().size() && fit <= l.headerRows() && parts == null) {
            fit = 0;
            fitted = 0;
        }
        boolean edges = l.spacing() <= 0 && fit < l.rows().size();
        for (int k = 0; k < fit; k++) {
            RowBox row = l.rows().get(k);
            pf.placeFixed(render(edges && k == fit - 1 && parts == null ? edged(row, false, true) : row, l), x, at);
            at += row.height;
        }
        List<RowBox> rows = l.rows();
        if (parts != null) {
            pf.placeFixed(render(edges ? edged(parts[0], false, true) : parts[0], l), x, at);
            at += parts[0].height;
            fitted += parts[0].height;
            rows = new ArrayList<>(rows);
            parts[1].index = rows.get(fit).index;
            parts[1].last = rows.get(fit).last;
            rows.set(fit, parts[1]);
        }
        float lft = Ooxml.twips(f.attr("leftFromText"), 0);
        float rgt = Ooxml.twips(f.attr("rightFromText"), 0);
        float top = Ooxml.twips(f.attr("topFromText"), 0);
        float bot = Ooxml.twips(f.attr("bottomFromText"), 0);
        if (fit > 0 || parts != null) {
            pf.exclude(new java.awt.geom.Rectangle2D.Float(x - lft, y - top, width + lft + rgt, fitted + top + bot));
        }
        if (fit == l.rows().size()) {
            return;
        }
        if (fit > 0 || parts != null || !pf.atTop()) {
            pf.newFrame(true, false);
        } else {
            pf.y = Math.max(pf.y, at);
        }
        Layout rest = new Layout(x - pf.left(), l.colX(), rows, l.headerRows(), l.spacing(), l.tp());
        if (fit > 0 || parts != null) {
            for (int k = 0; k < l.headerRows(); k++) {
                placeRow(l.rows().get(k), rest, pf);
            }
        }
        boolean placed = fit > 0 || parts != null;
        flow(rest, pf, fit, placed, edges && placed && l.headerRows() == 0);
        if (!"text".equals(f.attr("vertAnchor"))) {
            // The rows that run on stay floating: text goes on from the top of the table's last page, around them
            float from = pf.frameTop();
            pf.exclude(new java.awt.geom.Rectangle2D.Float(x - lft, from, width + lft + rgt, pf.y - from + bot));
            pf.y = from;
            return;
        }
        pf.y += bot;
    }

    private Strip placeRow(RowBox row, Layout l, Region r) {
        Strip s = render(row, l);
        if (!r.paginated()) {
            s.splitter = splitter(row, l);
        }
        r.place(s, r.left() + l.x(), r.y, 0);
        List<Inline.NoteRef> notes = s.notes;
        if (notes != null && !notes.isEmpty()) {
            r.addNotes(notes, 0);
        }
        r.y += row.height;
        return s;
    }

    private Function<Float, Strip[]> splitter(RowBox row, Layout l) {
        if (row.exact || row.cantSplit || row.header) {
            return null;
        }
        return avail -> {
            RowBox[] parts = split(row, avail, false);
            if (parts == null) {
                return null;
            }
            boolean edges = l.spacing() <= 0;
            Strip first = render(edges ? edged(parts[0], false, true) : parts[0], l);
            RowBox next = edges ? edged(parts[1], true, false) : parts[1];
            Strip rest = render(next, l);
            rest.splitter = splitter(next, l);
            return new Strip[] {first, rest};
        };
    }

    private Layout layout(TableBlock t, float avail, int maxRows) {
        TableProps tp = t.tp;
        int ncols = t.grid.length;
        int maxCells = 0;
        for (TableBlock.Row row : t.rows) {
            int c = row.rp.gridBefore == null ? 0 : row.rp.gridBefore;
            for (TableBlock.Cell cell : row.cells) {
                c += cell.span;
            }
            maxCells = Math.max(maxCells, c + (row.rp.gridAfter == null ? 0 : row.rp.gridAfter));
        }
        float[] grid = new float[Math.max(ncols, maxCells)];
        float sum = 0;
        for (int i = 0; i < ncols; i++) {
            grid[i] = t.grid[i];
            sum += grid[i];
        }
        float target = 0;
        if (tp.width != null && tp.width > 0) {
            target = "pct".equals(tp.widthType) ? avail * Math.min(tp.width, 1000) / 100f : tp.width;
        }
        if (sum <= 0.5f) {
            float total = target > 0 ? target : avail;
            fromCells(t, grid, total);
        } else if (grid.length > ncols) {
            float each = sum / ncols;
            for (int i = ncols; i < grid.length; i++) {
                grid[i] = each;
            }
        }
        sum = 0;
        for (float g : grid) {
            sum += g;
        }
        if ("pct".equals(tp.widthType) && target > 0 && Math.abs(sum - target) > 1 && sum > 0) {
            float scale = target / sum;
            for (int i = 0; i < grid.length; i++) {
                grid[i] *= scale;
            }
            sum = target;
        }
        float fit = autoFitLimit(t, avail);
        float[] content = fit > 0 && maxCells == ncols ? contentWidths(t, fit) : null;
        if (content != null) {
            grid = content;
            sum = 0;
            for (float g : grid) {
                sum += g;
            }
        }
        if (sum > fit + 1 && fit > 0) {
            // An autofit table without a width of its own shrinks to fit between the margins
            float scale = fit / sum;
            for (int i = 0; i < grid.length; i++) {
                grid[i] *= scale;
            }
            sum = fit;
        }
        float[] colX = new float[grid.length + 1];
        for (int i = 0; i < grid.length; i++) {
            colX[i + 1] = colX[i] + grid[i];
        }
        float x = tp.ind == null ? 0 : tp.ind;
        String jc = tp.jc == null ? "left" : tp.jc;
        boolean bidi = Boolean.TRUE.equals(tp.bidiVisual);
        if (jc.equals("center")) {
            x = (avail - sum) / 2;
        } else if (jc.equals("right") || jc.equals("end")) {
            x = bidi ? 0 : avail - sum;
        } else if (bidi) {
            x = avail - sum - x;
        }
        boolean leftAligned = !jc.equals("center") && !jc.equals("right") && !jc.equals("end");
        if (leftAligned && ctx.settings.compatibilityMode < 15 && !t.rows.isEmpty()
                && !t.rows.get(0).cells.isEmpty()) {
            CellProps first = t.rows.get(0).cells.get(0).cp;
            float ml = first.marLeft != null ? first.marLeft : tp.marLeft != null ? tp.marLeft : DEFAULT_MARGIN;
            x -= ml;
        }
        List<RowBox> rows = new ArrayList<>();
        int headerRows = 0;
        boolean headersOpen = true;
        int count = Math.min(maxRows, t.rows.size());
        for (int ri = 0; ri < count; ri++) {
            TableBlock.Row row = t.rows.get(ri);
            if (Boolean.TRUE.equals(row.rp.hidden)) {
                rows.add(new RowBox());
                continue;
            }
            RowBox rb = rowBox(t, row, ri, colX, grid.length);
            rows.add(rb);
            if (headersOpen && Boolean.TRUE.equals(row.rp.header)) {
                headerRows++;
                rb.header = true;
            } else {
                headersOpen = false;
            }
        }
        mergeVertical(t, rows, count, rowMark());
        for (RowBox rb : rows) {
            finishHeight(rb);
        }
        float spacing = tp.cellSpacing == null ? 0 : Math.max(0, tp.cellSpacing);
        for (int i = 0; i < rows.size(); i++) {
            RowBox rb = rows.get(i);
            rb.index = i;
            rb.last = i == rows.size() - 1;
            if (spacing > 0 && !rb.exact && !rb.cells.isEmpty()) {
                rb.height += 2 * spacing;
            }
            for (CellBox cb : rb.cells) {
                if (bidi) {
                    cb.x = sum - cb.x - cb.w;
                    Border left = cb.left;
                    cb.left = cb.right;
                    cb.right = left;
                }
                if (spacing > 0) {
                    cb.x += spacing;
                    cb.w = Math.max(0, cb.w - 2 * spacing);
                    cb.spanHeight = cb.spanHeight > 0 ? cb.spanHeight + 2 * spacing * cb.rowSpan : 0;
                    cb.top = cb.cell.cp.top != null ? cb.cell.cp.top : tp.insideH;
                    cb.bottom = cb.cell.cp.bottom != null ? cb.cell.cp.bottom : tp.insideH;
                    cb.left = cb.cell.cp.left != null ? cb.cell.cp.left : tp.insideV;
                    cb.right = cb.cell.cp.right != null ? cb.cell.cp.right : tp.insideV;
                }
            }
        }
        if (spacing <= 0) {
            shareBorders(rows);
        }
        if (headerRows == rows.size()) {
            headerRows = 0;
            for (RowBox rb : rows) {
                rb.header = false;
            }
        }
        return new Layout(x, colX, rows, headerRows, spacing, tp);
    }

    private float autoFitLimit(TableBlock t, float avail) {
        TableProps tp = t.tp;
        boolean sized = tp.width != null && tp.width > 0 && !"auto".equals(tp.widthType) && !"nil".equals(tp.widthType);
        if (sized || Boolean.TRUE.equals(tp.fixed) || tp.floating != null || t.rows.isEmpty()
                || t.rows.get(0).cells.isEmpty()) {
            return 0;
        }
        float limit = avail - (tp.ind == null ? 0 : tp.ind);
        if (ctx.settings.compatibilityMode < 15) {
            // Old layouts hang the cell margins outside the text column
            CellProps first = t.rows.get(0).cells.get(0).cp;
            List<TableBlock.Cell> cells = t.rows.get(0).cells;
            CellProps last = cells.get(cells.size() - 1).cp;
            limit += first.marLeft != null ? first.marLeft : tp.marLeft != null ? tp.marLeft : DEFAULT_MARGIN;
            limit += last.marRight != null ? last.marRight : tp.marRight != null ? tp.marRight : DEFAULT_MARGIN;
        }
        return limit;
    }

    // Word sizes an autofit table whose cells set no width from their text: each column its widest line, or when
    // that is too wide its longest word plus a share of the room left in proportion to what more it wants
    private float[] contentWidths(TableBlock t, float limit) {
        float[] min = new float[t.grid.length];
        float[] max = new float[t.grid.length];
        for (TableBlock.Row row : t.rows) {
            if (row.rp.gridBefore != null && row.rp.gridBefore > 0 || row.rp.gridAfter != null && row.rp.gridAfter > 0
                    || row.cells.size() != min.length) {
                return null;
            }
            for (int c = 0; c < min.length; c++) {
                TableBlock.Cell cell = row.cells.get(c);
                if (cell.span != 1 || cell.cp.width != null && cell.cp.width > 0 && !"auto".equals(cell.cp.widthType)
                        && !"nil".equals(cell.cp.widthType)) {
                    return null;
                }
                float margins = margin(cell.cp.marLeft, t.tp.marLeft) + margin(cell.cp.marRight, t.tp.marRight);
                for (Block b : cell.blocks) {
                    if (!(b instanceof Para para)) {
                        return null;
                    }
                    float[] w = textWidths(para);
                    min[c] = Math.max(min[c], w[0] + margins);
                    max[c] = Math.max(max[c], w[1] + margins);
                }
            }
        }
        float minSum = 0;
        float maxSum = 0;
        for (int c = 0; c < min.length; c++) {
            minSum += min[c];
            maxSum += max[c];
        }
        if (maxSum <= 0) {
            return null;
        }
        if (maxSum <= limit) {
            return max;
        }
        if (minSum >= limit) {
            return min;
        }
        float share = (limit - minSum) / (maxSum - minSum);
        float[] out = new float[min.length];
        for (int c = 0; c < min.length; c++) {
            out[c] = min[c] + (max[c] - min[c]) * share;
        }
        return out;
    }

    private static float margin(Float cell, Float table) {
        return cell != null ? cell : table != null ? table : DEFAULT_MARGIN;
    }

    // The longest word and the widest line of a paragraph, with its indents
    private float[] textWidths(Para para) {
        float word = 0;
        float line = 0;
        float longest = 0;
        float widest = 0;
        for (Item it : new ParaItems(ctx, para, null).items) {
            switch (it.kind) {
                case TEXT -> {
                    int from = 0;
                    for (int i = 0; i <= it.length(); i++) {
                        if (i == it.length() || it.text.charAt(i) == ' ') {
                            word += it.width(from, i);
                            if (i < it.length()) {
                                longest = Math.max(longest, word);
                                word = 0;
                            }
                            from = i + 1;
                        }
                    }
                    line += it.width(0, it.length());
                }
                case OBJECT -> {
                    word += it.objectWidth;
                    line += it.objectWidth;
                }
                case TAB -> {
                    longest = Math.max(longest, word);
                    word = 0;
                    line += ctx.settings.defaultTabStop;
                }
                case BREAK -> {
                    longest = Math.max(longest, word);
                    widest = Math.max(widest, line);
                    word = 0;
                    line = 0;
                }
                default -> {
                }
            }
        }
        longest = Math.max(longest, word);
        widest = Math.max(widest, line);
        ParaProps pp = para.pp;
        float ind = (pp.indLeft == null ? 0 : pp.indLeft) + (pp.indRight == null ? 0 : pp.indRight);
        float first = pp.indFirst == null ? 0 : Math.max(0, pp.indFirst);
        return new float[] {longest + ind + first, widest + ind + first};
    }

    private static void fromCells(TableBlock t, float[] grid, float total) {
        float[] widths = new float[grid.length];
        for (TableBlock.Row row : t.rows) {
            int c = row.rp.gridBefore == null ? 0 : row.rp.gridBefore;
            for (TableBlock.Cell cell : row.cells) {
                if (cell.span == 1 && c < widths.length && cell.cp.width != null && !"pct".equals(cell.cp.widthType)
                        && cell.cp.width > 0) {
                    widths[c] = Math.max(widths[c], cell.cp.width);
                }
                c += cell.span;
            }
        }
        float known = 0;
        int unknown = 0;
        for (float w : widths) {
            if (w > 0) {
                known += w;
            } else {
                unknown++;
            }
        }
        float rest = unknown > 0 ? Math.max(18, (total - known) / unknown) : 0;
        for (int i = 0; i < grid.length; i++) {
            grid[i] = widths[i] > 0 ? widths[i] : rest;
        }
    }

    private RowBox rowBox(TableBlock t, TableBlock.Row row, int ri, float[] colX, int ncols) {
        TableProps tp = t.tp;
        if (row.exceptions != null) {
            tp = tp.copy();
            tp.mergeFrom(row.exceptions);
        }
        RowBox rb = new RowBox();
        rb.cantSplit = Boolean.TRUE.equals(row.rp.cantSplit);
        float h = row.rp.height == null ? 0 : row.rp.height;
        rb.exact = "exact".equals(row.rp.hRule) && h > 0;
        rb.minHeight = h;
        int last = t.rows.size() - 1;
        for (TableBlock.Cell cell : row.cells) {
            int c0 = Math.min(cell.col, ncols);
            int c1 = Math.min(cell.col + cell.span, ncols);
            CellBox cb = new CellBox();
            cb.cell = cell;
            cb.x = colX[c0];
            cb.w = Math.max(0, colX[c1] - colX[c0]);
            CellProps cp = cell.cp;
            cb.marL = pick(cp.marLeft, tp.marLeft, DEFAULT_MARGIN);
            cb.marR = pick(cp.marRight, tp.marRight, DEFAULT_MARGIN);
            cb.marT = pick(cp.marTop, tp.marTop, 0);
            cb.marB = pick(cp.marBottom, tp.marBottom, 0);
            cb.top = cp.top != null ? cp.top : ri == 0 ? tp.top : tp.insideH;
            cb.bottom = cp.bottom != null ? cp.bottom : ri == last ? tp.bottom : tp.insideH;
            cb.left = cp.left != null ? cp.left : c0 == 0 ? tp.left : tp.insideV;
            cb.right = cp.right != null ? cp.right : c1 >= ncols ? tp.right : tp.insideV;
            cb.edgeTop = cp.top != null ? cp.top : tp.top;
            cb.edgeBottom = cp.bottom != null ? cp.bottom : tp.bottom;
            if (ctx.settings.compatibilityMode >= 15) {
                // Word 2013 layout moves cell content right by half the left border, keeping its width
                float shift = half(cb.left);
                cb.marL += shift;
                cb.marR -= shift;
            }
            cb.fill = cp.shadingColor() != null ? cp.shadingColor() : cp.shading == null ? tp.shading : null;
            cb.vAlign = cp.vAlign == null ? "top" : cp.vAlign;
            cb.continuation = "continue".equals(cp.vMerge);
            cb.rotation = rotation(cp.textDirection);
            if (!cb.continuation && cb.rotation != 0) {
                cb.contentH = alongLength(cell, h, cb.marT + cb.marB, rb.exact);
            } else if (!cb.continuation) {
                float inner = Math.max(1, cb.w - cb.marL - cb.marR);
                StackLayout region = StackLayout.cell(inner, ctx);
                region.background = cb.fill != null ? cb.fill : background;
                blocks.place(cell.blocks, region);
                StackLayout.Result res = region.result();
                cb.content = res.placed();
                cb.contentH = res.height();
                cb.anchors.addAll(res.anchors());
                cb.notes.addAll(res.notes());
            }
            rb.cells.add(cb);
        }
        // Word gives every cell of a row the largest top and bottom cell margin in it
        float rowMarT = 0;
        float rowMarB = 0;
        for (CellBox cb : rb.cells) {
            rowMarT = Math.max(rowMarT, cb.marT);
            rowMarB = Math.max(rowMarB, cb.marB);
        }
        boolean cellMarT = false;
        boolean cellMarB = false;
        for (CellBox cb : rb.cells) {
            cb.marT = rowMarT;
            cb.marB = rowMarB;
            cellMarT |= cb.cell.cp.marTop != null;
            cellMarB |= cb.cell.cp.marBottom != null;
        }
        // Word adds the top and bottom cell margins to an at-least row height, and to an exact one those set on a cell
        if (h > 0) {
            rb.minHeight = h + (cellMarT || !rb.exact ? rowMarT : 0) + (cellMarB || !rb.exact ? rowMarB : 0);
        }
        float height = 0;
        for (CellBox cb : rb.cells) {
            if (!cb.continuation && ParaProps.visible(cb.top)) {
                rb.borderTop = Math.max(rb.borderTop, cb.top.total());
            }
            if (ri == last && ParaProps.visible(cb.bottom)) {
                rb.borderBottom = Math.max(rb.borderBottom, cb.bottom.total());
            }
        }
        int starts = 0;
        int continues = 0;
        for (CellBox cb : rb.cells) {
            if (cb.continuation) {
                continues++;
            } else if ("restart".equals(cb.cell.cp.vMerge)) {
                starts++;
            } else {
                height = Math.max(height, sizing(cb) + cb.marT + cb.marB);
            }
        }
        // A row whose cells all start, or all continue, vertical merges is as tall as its end-of-row mark, an empty
        // Normal paragraph
        int n = rb.cells.size();
        if (n > 0 && continues == n) {
            height = Math.max(height, rowMark());
        }
        rb.allStart = n > 0 && starts == n;
        rb.height = height;
        return rb;
    }

    private static float half(Border b) {
        return ParaProps.visible(b) ? b.total() / 2 : 0;
    }

    // With hideMark a closing empty paragraph after other content, the end-of-cell mark, does not size the row
    private static float sizing(CellBox cb) {
        if (!hidesMark(cb)) {
            return cb.contentH;
        }
        Placed mark = cb.content.get(cb.content.size() - 1);
        return Math.max(0, mark.y - mark.gap);
    }

    private static boolean hidesMark(CellBox cb) {
        List<Block> blocks = cb.cell.blocks;
        return Boolean.TRUE.equals(cb.cell.cp.hideMark) && !blocks.isEmpty() && !cb.content.isEmpty()
                && blocks.get(blocks.size() - 1) instanceof Para last && last.empty();
    }

    private float rowMark() {
        if (rowMark < 0) {
            Styles st = ctx.pkg.styles;
            String id = st.defaultParagraphStyle();
            ParaProps pp = new ParaProps();
            pp.mergeFrom(st.defaultPara);
            pp.mergeFrom(st.paragraph(id));
            pp.styleId = id;
            RunProps rp = st.defaultRun.copy();
            rp.mergeFrom(st.paragraphRun(id));
            rp.styleId = null;
            StackLayout region = StackLayout.cell(MAX_ALONG, ctx);
            blocks.place(List.of(new Para(pp, rp, List.of(), id, null, null, null)), region);
            rowMark = region.result().height();
        }
        return rowMark;
    }

    private static int rotation(String direction) {
        if (direction == null) {
            return 0;
        }
        return switch (direction) {
            case "btLr" -> -1;
            case "tbRl", "tbRlV", "tbLrV", "rl", "rlV" -> 1;
            default -> 0;
        };
    }

    // Rotated cell text runs along the row: an auto row grows only to its longest word, then the text wraps
    private float alongLength(TableBlock.Cell cell, float rowHeight, float margins, boolean exact) {
        float set = Math.max(0, rowHeight - margins);
        if (exact) {
            return set;
        }
        StackLayout region = StackLayout.cell(MAX_ALONG, ctx);
        blocks.place(cell.blocks, region);
        float natural = Math.min(MAX_ALONG, longestWord(region.result().ops()));
        return Math.max(set, natural);
    }

    static float longestWord(List<Op> ops) {
        float max = 0;
        for (Op op : ops) {
            float w = switch (op) {
                case Op.Text t -> {
                    float m = 0;
                    for (String word : t.text().split(" ")) {
                        m = Math.max(m, t.style().width(word));
                    }
                    yield m;
                }
                case Op.Glyphs g -> g.run().width(g.style().size());
                case Op.Group g when g.transform() == null -> longestWord(g.ops());
                case Op.Chart c -> longestWord(c.ops());
                case Op.Image i -> i.w();
                default -> 0;
            };
            max = Math.max(max, w);
        }
        return max;
    }

    private static final float MAX_ALONG = 648;

    private static float pick(Float a, Float b, float fallback) {
        return a != null ? a : b != null ? b : fallback;
    }

    private static void resolveRowBorders(List<RowBox> rows, int count) {
        for (int ri = 1; ri < count; ri++) {
            RowBox rb = rows.get(ri);
            RowBox prev = rows.get(ri - 1);
            for (CellBox cb : rb.cells) {
                if (cb.cell == null) {
                    continue;
                }
                if (cb.continuation && ParaProps.visible(cb.cell.cp.top)) {
                    rb.borderTop = Math.max(rb.borderTop, cb.cell.cp.top.total());
                }
                CellBox above = at(prev, cb.cell.col);
                // A border the cell above draws under itself takes room only where the top cell margin cannot hold it
                if (above != null && above.cell != null && ParaProps.visible(above.cell.cp.bottom)
                        && above.cell.cp.bottom.total() > cb.marT + 0.01f) {
                    rb.borderTop = Math.max(rb.borderTop, above.cell.cp.bottom.total());
                }
            }
        }
    }

    private static void mergeVertical(TableBlock t, List<RowBox> rows, int count, float mark) {
        resolveRowBorders(rows, count);
        for (int ri = 0; ri < count; ri++) {
            RowBox rb = rows.get(ri);
            for (CellBox cb : rb.cells) {
                if (cb.cell == null || !"restart".equals(cb.cell.cp.vMerge)) {
                    continue;
                }
                int span = 1;
                for (int rj = ri + 1; rj < count; rj++) {
                    CellBox below = at(rows.get(rj), cb.cell.col);
                    if (below == null || !below.continuation) {
                        break;
                    }
                    span++;
                    below.top = null;
                }
                cb.rowSpan = span;
                if (span > 1) {
                    CellBox lastCell = at(rows.get(ri + span - 1), cb.cell.col);
                    if (lastCell != null) {
                        cb.bottom = lastCell.bottom != null ? lastCell.bottom : cb.bottom;
                    }
                }
            }
            // A restart that no row below continues is a plain cell and sizes the row itself
            boolean merged = rb.allStart;
            for (CellBox cb : rb.cells) {
                merged &= cb.rowSpan > 1;
            }
            if (merged) {
                rb.height = Math.max(rb.height, mark);
            }
        }
        for (int ri = 0; ri < count; ri++) {
            RowBox rb = rows.get(ri);
            finishHeight(rb);
            for (CellBox cb : rb.cells) {
                if (cb.cell == null || !"restart".equals(cb.cell.cp.vMerge)) {
                    continue;
                }
                float need = sizing(cb) + cb.marT + cb.marB + rb.borderTop + rb.borderBottom;
                if (cb.rowSpan == 1) {
                    rb.height = Math.max(rb.height, need);
                    continue;
                }
                float have = 0;
                for (int k = 0; k < cb.rowSpan; k++) {
                    finishHeight(rows.get(ri + k));
                    have += rows.get(ri + k).height;
                }
                if (need > have) {
                    RowBox lastRow = rows.get(ri + cb.rowSpan - 1);
                    if (!lastRow.exact) {
                        lastRow.height += need - have;
                        lastRow.grown += need - have;
                    }
                }
            }
        }
        for (int ri = 0; ri < count; ri++) {
            RowBox rb = rows.get(ri);
            for (CellBox cb : rb.cells) {
                if (cb.rowSpan > 1) {
                    float h = 0;
                    for (int k = 0; k < cb.rowSpan && ri + k < count; k++) {
                        h += rows.get(ri + k).height;
                    }
                    cb.spanHeight = h;
                }
            }
        }
    }

    // Word draws one border between two rows, the heavier of the upper cells' bottoms and the lower cells' tops
    private static void shareBorders(List<RowBox> rows) {
        for (int ri = 0; ri < rows.size(); ri++) {
            for (CellBox up : rows.get(ri).cells) {
                int below = ri + Math.max(1, up.rowSpan);
                if (up.continuation || up.cell == null || below >= rows.size() || !ParaProps.visible(up.bottom)) {
                    continue;
                }
                List<CellBox> lower = new ArrayList<>();
                float covered = 0;
                boolean shared = true;
                for (CellBox low : rows.get(below).cells) {
                    float from = Math.max(low.x, up.x);
                    float to = Math.min(low.x + low.w, up.x + up.w);
                    if (to - from <= 0.01f) {
                        continue;
                    }
                    covered += to - from;
                    boolean inside = low.x >= up.x - 0.01f && low.x + low.w <= up.x + up.w + 0.01f;
                    shared &= !low.continuation && ParaProps.visible(low.top)
                            && (inside || low.top.total() >= up.bottom.total());
                    lower.add(low);
                }
                if (!shared || lower.isEmpty() || covered < up.w - 0.01f) {
                    continue;
                }
                for (CellBox low : lower) {
                    if (up.bottom.total() > low.top.total()) {
                        low.top = up.bottom;
                    }
                }
                up.bottom = null;
            }
        }
    }

    private static CellBox at(RowBox row, int col) {
        for (CellBox cb : row.cells) {
            if (cb.cell != null && cb.cell.col == col) {
                return cb;
            }
        }
        return null;
    }

    private static void finishHeight(RowBox rb) {
        if (rb.finished) {
            return;
        }
        rb.finished = true;
        if (rb.exact) {
            rb.height = rb.minHeight;
        } else {
            rb.height = Math.max(rb.height, rb.minHeight) + rb.borderTop + rb.borderBottom;
        }
    }

    private RowBox[] split(RowBox row, float avail, boolean keeps) {
        if (avail < 12) {
            return null;
        }
        float content = 0;
        for (CellBox cb : row.cells) {
            if (cb.rotation != 0 && !cb.continuation) {
                return null;
            }
            if (!cb.continuation && !"restart".equals(cb.cell.cp.vMerge)) {
                content = Math.max(content, cb.contentH + cb.marT + cb.marB);
            }
        }
        // A row whose set minimum height holds all its content moves whole, as Word keeps it
        if (row.minHeight > 0 && content <= row.minHeight + 0.01f) {
            return null;
        }
        int[] fits = fits(row, avail, keeps);
        RowBox first = new RowBox();
        RowBox rest = new RowBox();
        boolean anyFirst = false;
        boolean anyRest = false;
        float firstH = 0;
        float restH = 0;
        for (int ci = 0; ci < row.cells.size(); ci++) {
            CellBox cb = row.cells.get(ci);
            CellBox a = copy(cb);
            CellBox b = copy(cb);
            a.bottom = null;
            a.rowSpan = 1;
            b.rowSpan = 1;
            a.spanHeight = 0;
            b.spanHeight = 0;
            // The part left on this page keeps its cell margins and the borders drawn above and below it
            float cut = cut(row, cb, avail);
            int fit = fits[ci];
            Strip[] halves = null;
            if (fit < cb.content.size() && cb.content.get(fit).strip.splitter != null) {
                Placed p = cb.content.get(fit);
                if (cut - p.y >= 12) {
                    halves = p.strip.splitter.apply(cut - p.y);
                }
            }
            float shift = -1;
            for (int k = 0; k < cb.content.size(); k++) {
                Placed p = cb.content.get(k);
                if (k < fit) {
                    a.content.add(p);
                    anyFirst = true;
                } else if (k == fit && halves != null) {
                    // A nested table row splits too: its first part ends this page, the rest starts the next
                    a.content.add(new Placed(halves[0], p.x, p.y, 0, 0, p.gap));
                    b.content.add(new Placed(halves[1], p.x, 0, 0, 0, p.gap));
                    shift = p.y + p.strip.height - halves[1].height;
                    anyFirst = true;
                    anyRest = true;
                } else {
                    if (shift < 0) {
                        shift = p.y;
                    }
                    b.content.add(new Placed(p.strip, p.x, p.y - shift, 0, 0, p.gap));
                    anyRest = true;
                }
            }
            // The part left on this page ends with the space after its last paragraph, as Word draws it
            a.contentH = contentHeight(a.content) + (fit > 0 && halves == null ? trail(cb, fit - 1) : 0);
            b.contentH = shift >= 0 ? Math.max(contentHeight(b.content), cb.contentH - shift)
                    : contentHeight(b.content);
            b.anchors.clear();
            a.vAlign = "top";
            b.vAlign = "top";
            first.cells.add(a);
            rest.cells.add(b);
            firstH = Math.max(firstH, a.contentH + a.marT + a.marB + row.borderTop);
            restH = Math.max(restH, b.contentH + b.marT + b.marB + row.borderTop + row.borderBottom);
        }
        if (!anyFirst || !anyRest) {
            return null;
        }
        first.height = firstH;
        first.height = Math.min(first.height, avail);
        rest.height = Math.max(restH, 0);
        rest.minHeight = 0;
        first.borderTop = row.borderTop;
        rest.borderTop = row.borderTop;
        rest.borderBottom = row.borderBottom;
        return new RowBox[] {first, rest};
    }

    private static float cut(RowBox row, CellBox cb, float avail) {
        float below = ParaProps.visible(cb.edgeBottom) ? cb.edgeBottom.total() : 0;
        return avail - cb.marT - cb.marB - row.borderTop - below;
    }

    // At a page break Word closes the table: the row ending a page takes the table's bottom border and the row
    // starting the next page its top border
    static RowBox edged(RowBox row, boolean opens, boolean closes) {
        RowBox e = new RowBox();
        e.cantSplit = row.cantSplit;
        e.header = row.header;
        e.exact = row.exact;
        e.minHeight = row.minHeight;
        e.finished = row.finished;
        e.allStart = row.allStart;
        e.index = row.index;
        e.last = row.last;
        e.grown = row.grown;
        e.borderTop = opens ? 0 : row.borderTop;
        e.borderBottom = closes ? 0 : row.borderBottom;
        for (CellBox cb : row.cells) {
            CellBox c = copy(cb);
            c.content = cb.content;
            c.contentH = cb.contentH;
            if (opens && !c.continuation) {
                c.top = c.edgeTop;
                if (ParaProps.visible(c.top)) {
                    e.borderTop = Math.max(e.borderTop, c.top.total());
                }
            }
            if (closes && !c.continuation && c.rowSpan <= 1) {
                c.bottom = c.edgeBottom;
                if (ParaProps.visible(c.bottom)) {
                    e.borderBottom = Math.max(e.borderBottom, c.bottom.total());
                }
            }
            e.cells.add(c);
        }
        float grow = row.exact ? 0 : e.borderTop - row.borderTop + e.borderBottom - row.borderBottom;
        e.height = row.height + grow;
        for (CellBox c : e.cells) {
            if (c.rowSpan > 1) {
                c.spanHeight += grow;
            }
        }
        return e;
    }

    // Lines of each cell left on this page: a paragraph's last line must also fit the space after it, and widow
    // control applies; when it would empty a cell the row moves, unless only the space after held the lines back
    private static int[] fits(RowBox row, float avail, boolean keeps) {
        int n = row.cells.size();
        int[] fits = new int[n];
        for (int ci = 0; ci < n; ci++) {
            CellBox cb = row.cells.get(ci);
            float cut = cut(row, cb, avail);
            int physical = 0;
            int fit = 0;
            while (physical < cb.content.size()) {
                Placed p = cb.content.get(physical);
                float bottom = p.y + p.strip.height;
                if (bottom > cut + 0.01f) {
                    break;
                }
                physical++;
                if (bottom + trail(cb, physical - 1) <= cut + 0.01f) {
                    fit = physical;
                }
            }
            int safe = widowSafe(cb.content, fit);
            if (fit > 0 && safe == 0) {
                if (physical <= fit) {
                    return new int[n];
                }
                safe = fit;
            }
            if (keeps) {
                int kept = keepSafe(cb.content, safe);
                if (safe > 0 && kept == 0) {
                    return new int[n];
                }
                safe = kept;
            }
            fits[ci] = safe;
        }
        return fits;
    }

    // Keep lines together and keep with next hold inside a cell too, so a row may only break where they allow
    private static int keepSafe(List<Placed> content, int fit) {
        int k = fit;
        while (k > 0 && k < content.size()) {
            Strip before = content.get(k - 1).strip;
            Strip after = content.get(k).strip;
            boolean inside = after.line > 0;
            boolean ends = before.lines > 0 && before.line == before.lines - 1;
            if (inside ? !after.keepLines : !(ends && before.keepNext)) {
                break;
            }
            k--;
        }
        return k;
    }

    private static int widowSafe(List<Placed> content, int fit) {
        if (fit <= 0 || fit >= content.size()) {
            return fit;
        }
        Strip next = content.get(fit).strip;
        if (next.line <= 0 || !next.widow) {
            return fit;
        }
        int before = next.line;
        int after = next.lines > 0 ? next.lines - next.line : 2;
        int k = fit;
        if (after == 1 && before >= 2) {
            k--;
            before--;
        }
        if (before == 1) {
            k -= 1;
        }
        return Math.max(0, k);
    }

    // A paragraph's last line in a cell carries the space after it
    private static float trail(CellBox cb, int i) {
        Placed p = cb.content.get(i);
        float bottom = p.y + p.strip.height;
        if (i == cb.content.size() - 1) {
            return Math.max(0, cb.contentH - bottom);
        }
        if (p.strip.lines <= 0 || p.strip.line != p.strip.lines - 1) {
            return 0;
        }
        Placed next = cb.content.get(i + 1);
        return Math.max(0, next.y - next.gap - bottom);
    }

    private static float contentHeight(List<Placed> content) {
        float h = 0;
        for (Placed p : content) {
            h = Math.max(h, p.y + p.strip.height);
        }
        return h;
    }

    private static CellBox copy(CellBox cb) {
        CellBox c = new CellBox();
        c.cell = cb.cell;
        c.x = cb.x;
        c.w = cb.w;
        c.marL = cb.marL;
        c.marR = cb.marR;
        c.marT = cb.marT;
        c.marB = cb.marB;
        c.top = cb.top;
        c.bottom = cb.bottom;
        c.left = cb.left;
        c.right = cb.right;
        c.edgeTop = cb.edgeTop;
        c.edgeBottom = cb.edgeBottom;
        c.fill = cb.fill;
        c.vAlign = cb.vAlign;
        c.continuation = cb.continuation;
        c.rowSpan = cb.rowSpan;
        c.spanHeight = cb.spanHeight;
        c.rotation = cb.rotation;
        c.notes.addAll(cb.notes);
        c.anchors.addAll(cb.anchors);
        return c;
    }

    // Cells with spacing are separate boxes inside the table's own outline, as Word draws them
    private void spaced(RowBox row, Layout l, Strip s, float sp) {
        float total = l.colX()[l.colX().length - 1];
        for (CellBox cb : row.cells) {
            if (cb.continuation) {
                continue;
            }
            float h = (cb.rowSpan > 1 ? cb.spanHeight : row.height) - 2 * sp;
            if (cb.fill != null && cb.w > 0 && h > 0) {
                s.ops.add(new Op.Rect(cb.x, sp, cb.w, h, Fill.solid(cb.fill), null));
            }
            List<Op> content = new ArrayList<>();
            for (Placed p : cb.content) {
                if (!p.strip.ops.isEmpty()) {
                    content.add(new Op.Group(p.x, p.y, null, null, p.strip.ops));
                }
                if (p.strip.notes != null) {
                    for (Inline.NoteRef n : p.strip.notes) {
                        s.note(n);
                    }
                }
            }
            float dy = sp + cb.marT + (ParaProps.visible(cb.top) ? cb.top.total() : 0);
            float free = h - cb.marT - cb.marB - cb.contentH;
            if (free > 0) {
                dy += "center".equals(cb.vAlign) ? free / 2 : "bottom".equals(cb.vAlign) ? free : 0;
            }
            if (!content.isEmpty()) {
                s.ops.add(new Op.Group(cb.x + cb.marL, dy, null, null, content));
            }
            float y0 = sp;
            float y1 = sp + h;
            if (ParaProps.visible(cb.top)) {
                ParaFlow.border(s.ops, cb.top, cb.x, y0 + cb.top.total() / 2, cb.x + cb.w, y0 + cb.top.total() / 2,
                        true);
            }
            if (ParaProps.visible(cb.bottom)) {
                ParaFlow.border(s.ops, cb.bottom, cb.x, y1 - cb.bottom.total() / 2, cb.x + cb.w,
                        y1 - cb.bottom.total() / 2, false);
            }
            if (ParaProps.visible(cb.left)) {
                ParaFlow.border(s.ops, cb.left, cb.x + cb.left.total() / 2, y0, cb.x + cb.left.total() / 2, y1,
                        true);
            }
            if (ParaProps.visible(cb.right)) {
                ParaFlow.border(s.ops, cb.right, cb.x + cb.w - cb.right.total() / 2, y0,
                        cb.x + cb.w - cb.right.total() / 2, y1, false);
            }
        }
        TableProps tp = l.tp();
        if (ParaProps.visible(tp.left)) {
            ParaFlow.border(s.ops, tp.left, tp.left.total() / 2, 0, tp.left.total() / 2, row.height, true);
        }
        if (ParaProps.visible(tp.right)) {
            ParaFlow.border(s.ops, tp.right, total - tp.right.total() / 2, 0, total - tp.right.total() / 2, row.height,
                    false);
        }
        if (row.index == 0 && ParaProps.visible(tp.top)) {
            ParaFlow.border(s.ops, tp.top, 0, tp.top.total() / 2, total, tp.top.total() / 2, true);
        }
        if (row.last && ParaProps.visible(tp.bottom)) {
            ParaFlow.border(s.ops, tp.bottom, 0, row.height - tp.bottom.total() / 2, total,
                    row.height - tp.bottom.total() / 2, false);
        }
    }

    private void rotated(CellBox cb, float h, RowBox row, Strip s) {
        float along = Math.max(1, h - cb.marT - cb.marB - row.borderTop - row.borderBottom);
        StackLayout region = StackLayout.cell(along, ctx);
        region.background = cb.fill != null ? cb.fill : background;
        blocks.place(cb.cell.blocks, region);
        StackLayout.Result r = region.result();
        float across = Math.max(0, cb.w - cb.marL - cb.marR);
        float free = Math.max(0, across - r.height());
        String va = cb.vAlign == null ? "top" : cb.vAlign;
        float off = switch (va) {
            case "center" -> free / 2;
            case "bottom" -> cb.rotation < 0 ? free : 0;
            default -> cb.rotation < 0 ? 0 : free;
        };
        float left = cb.x + cb.marL + off;
        float top = cb.marT + row.borderTop;
        java.awt.geom.AffineTransform t = new java.awt.geom.AffineTransform();
        if (cb.rotation < 0) {
            t.translate(left, top + along);
            t.rotate(-Math.PI / 2);
        } else {
            t.translate(left + r.height(), top);
            t.rotate(Math.PI / 2);
        }
        s.ops.add(new Op.Group(0, 0, t, null, r.ops()));
        for (Inline.NoteRef n : r.notes()) {
            s.note(n);
        }
    }

    private Strip render(RowBox row, Layout l) {
        Strip s = new Strip();
        s.height = row.height;
        float sp = l.spacing();
        if (sp > 0) {
            spaced(row, l, s, sp);
            return s;
        }
        for (CellBox cb : row.cells) {
            float h = cb.rowSpan > 1 ? cb.spanHeight : row.height;
            if (cb.continuation) {
                continue;
            }
            if (cb.fill != null && cb.w > 0 && h > 0) {
                s.ops.add(new Op.Rect(cb.x, 0, cb.w, h, Fill.solid(cb.fill), null));
            }
        }
        for (CellBox cb : row.cells) {
            if (cb.continuation) {
                continue;
            }
            float h = cb.rowSpan > 1 ? cb.spanHeight : row.height;
            if (cb.rotation != 0) {
                rotated(cb, h, row, s);
                continue;
            }
            float dy = cb.marT + row.borderTop;
            float free = h - cb.marT - cb.marB - cb.contentH - row.borderTop - row.borderBottom;
            if (free > 0) {
                if ("center".equals(cb.vAlign)) {
                    dy += free / 2;
                } else if ("bottom".equals(cb.vAlign)) {
                    dy += free;
                }
            }
            List<Op> content = new ArrayList<>();
            for (Placed p : cb.content) {
                if (!p.strip.ops.isEmpty()) {
                    content.add(new Op.Group(p.x, p.y, null, null, p.strip.ops));
                }
                if (p.strip.notes != null) {
                    for (Inline.NoteRef n : p.strip.notes) {
                        s.note(n);
                    }
                }
            }
            float inner = Math.max(0, cb.w - cb.marL - cb.marR);
            for (Strip.Anchor a : cb.anchors) {
                Strip.Anchor moved = a.shift(cb.x + cb.marL, dy);
                s.anchor(a.inCell() ? moved : new Strip.Anchor(a.drawing(), moved.paraX(), moved.paraTop(),
                        cb.x + cb.marL, inner, new Rectangle2D.Float(cb.x, 0, cb.w, h)));
            }
            if (!content.isEmpty()) {
                s.ops.add(new Op.Group(cb.x + cb.marL, dy, null, null, content));
            }
        }
        for (CellBox cb : row.cells) {
            float h = cb.continuation ? row.height : cb.rowSpan > 1 ? cb.spanHeight : row.height;
            if (!cb.continuation && ParaProps.visible(cb.top)) {
                float y = cb.top.total() / 2;
                ParaFlow.border(s.ops, cb.top, cb.x, y, cb.x + cb.w, y, true);
            }
            if (!cb.continuation && ParaProps.visible(cb.bottom)) {
                float y = h - cb.bottom.total() / 2;
                ParaFlow.border(s.ops, cb.bottom, cb.x, y, cb.x + cb.w, y, false);
            }
            if (ParaProps.visible(cb.left)) {
                float x = cb.x + cb.left.total() / 2;
                ParaFlow.border(s.ops, cb.left, x, 0, x, h, true);
            }
            if (ParaProps.visible(cb.right)) {
                float x = cb.x + cb.w + cb.right.total() / 2;
                ParaFlow.border(s.ops, cb.right, x, 0, x, h, false);
            }
        }
        return s;
    }
}
