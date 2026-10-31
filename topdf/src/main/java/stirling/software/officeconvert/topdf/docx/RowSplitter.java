package stirling.software.officeconvert.topdf.docx;

import java.util.List;

import stirling.software.officeconvert.topdf.docx.TableFlow.CellBox;
import stirling.software.officeconvert.topdf.docx.TableFlow.RowBox;

final class RowSplitter {

    private RowSplitter() {}

    static RowBox[] split(RowBox row, float avail, boolean keeps) {
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
    static int keepSafe(List<Placed> content, int fit) {
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

    static int widowSafe(List<Placed> content, int fit) {
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

    static float contentHeight(List<Placed> content) {
        float h = 0;
        for (Placed p : content) {
            h = Math.max(h, p.y + p.strip.height);
        }
        return h;
    }

    static CellBox copy(CellBox cb) {
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
}
