package stirling.software.officeconvert.topdf.docx;

import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.topdf.docx.TableFlow.CellBox;
import stirling.software.officeconvert.topdf.docx.TableFlow.RowBox;

final class TableBorders {

    private TableBorders() {}

    static float half(Border b) {
        return ParaProps.visible(b) ? b.total() / 2 : 0;
    }

    // With hideMark a closing empty paragraph after other content, the end-of-cell mark, does not size the row
    static float sizing(CellBox cb) {
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

    static void mergeVertical(TableBlock t, List<RowBox> rows, int count, float mark) {
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
    static void shareBorders(List<RowBox> rows) {
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

    static CellBox at(RowBox row, int col) {
        for (CellBox cb : row.cells) {
            if (cb.cell != null && cb.cell.col == col) {
                return cb;
            }
        }
        return null;
    }

    static void finishHeight(RowBox rb) {
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
}
