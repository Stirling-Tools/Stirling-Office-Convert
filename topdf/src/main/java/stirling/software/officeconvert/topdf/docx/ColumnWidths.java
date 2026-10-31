package stirling.software.officeconvert.topdf.docx;

import java.util.List;

final class ColumnWidths {

    record Columns(float[] grid, float sum) {
    }

    private final Ctx ctx;

    ColumnWidths(Ctx ctx) {
        this.ctx = ctx;
    }

    Columns layout(TableBlock t, float avail) {
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
            target = "pct".equals(tp.widthType) ? pctBase(t, avail) * Math.min(tp.width, 1000) / 100f : tp.width;
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
        return new Columns(grid, sum);
    }

    private float pctBase(TableBlock t, float avail) {
        if (ctx.settings.compatibilityMode >= 15 || t.rows.isEmpty() || t.rows.get(0).cells.isEmpty()) {
            return avail;
        }
        return avail + hangingMargins(t);
    }

    private static float hangingMargins(TableBlock t) {
        TableProps tp = t.tp;
        List<TableBlock.Cell> cells = t.rows.get(0).cells;
        CellProps first = cells.get(0).cp;
        CellProps last = cells.get(cells.size() - 1).cp;
        float ml = first.marLeft != null ? first.marLeft : tp.marLeft != null ? tp.marLeft : TableFlow.DEFAULT_MARGIN;
        float mr = last.marRight != null ? last.marRight : tp.marRight != null ? tp.marRight : TableFlow.DEFAULT_MARGIN;
        return ml + mr;
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
            limit += hangingMargins(t);
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
        return cell != null ? cell : table != null ? table : TableFlow.DEFAULT_MARGIN;
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
}
