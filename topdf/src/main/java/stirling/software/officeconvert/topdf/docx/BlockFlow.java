package stirling.software.officeconvert.topdf.docx;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;

public final class BlockFlow {

    public static final int CHAIN_LIMIT = 512;

    private final Ctx ctx;

    private final ParaFlow paras;

    private final TableFlow tables;

    private final FrameFlow frames;

    BlockFlow(Ctx ctx) {
        this.ctx = ctx;
        this.paras = new ParaFlow(ctx);
        this.tables = new TableFlow(ctx, this);
        this.frames = new FrameFlow(ctx);
    }


    void place(List<Block> blocks, Region r) {
        int n = blocks.size();
        for (int i = 0; i < n && !r.exhausted(); i++) {
            try {
                ctx.job.checkpoint();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Block b = blocks.get(i);
            Block next = i + 1 < n ? blocks.get(i + 1) : null;
            if (r instanceof PageFlow pf && !(b instanceof Para fp && FrameFlow.framed(fp))) {
                stretchTail(blocks, i, pf);
            }
            if (b instanceof Para p && r instanceof PageFlow pf && FrameFlow.framed(p)) {
                i = frames.place(blocks, i, pf) - 1;
            } else if (b instanceof Para p && r instanceof StackLayout s && s.floatsFrames() && FrameFlow.floats(p)) {
                i = frames.place(blocks, i, s) - 1;
            } else if (b instanceof Para p) {
                boolean sectionMark = p.sectionMark();
                if (r.paginated() && Boolean.TRUE.equals(p.pp.keepNext) && !r.atTop() && !ParaFlow.hidden(p)
                        && !sectionMark && !continuesChain(blocks, i)) {
                    keepTogether(blocks, i, r);
                }
                paras.place(p, r, next);
            } else if (b instanceof TableBlock t) {
                tables.place(t, r);
                if (t.tp.floating != null && !"text".equals(t.tp.floating.attr("vertAnchor"))
                        && r instanceof PageFlow pf && next instanceof Para p && p.empty() && !p.sectionMark()
                        && pf.suspendTableExclusion()) {
                    paras.place(p, r, i + 2 < n ? blocks.get(i + 2) : null);
                    pf.restoreTableExclusion();
                    i++;
                }
            }
        }
    }

    // A page or column break inside the chain ends it: what follows the break starts a new frame anyway
    private void keepTogether(List<Block> blocks, int i, Region r) {
        if (blocks.get(i) instanceof Para start && hardBreak(start) != NO_BREAK) {
            return;
        }
        float width = r.width();
        float chain = 0;
        float after = r.lastAfter;
        int j = i;
        while (j < blocks.size() && j - i < CHAIN_LIMIT && blocks.get(j) instanceof Para p
                && Boolean.TRUE.equals(p.pp.keepNext) && hardBreak(p) == NO_BREAK && (j == i || !startsPage(p))) {
            Block next = j + 1 < blocks.size() ? blocks.get(j + 1) : null;
            float[] m = paras.measure(p, width, next, r);
            chain += paras.collapse(m[0], after) + m[1] + m[2];
            after = m[2];
            j++;
            if (chain > r.frameHeight()) {
                return;
            }
        }
        boolean headed = false;
        if (j < blocks.size()) {
            Block last = blocks.get(j);
            if (last instanceof Para p && !startsPage(p)) {
                float[] m = paras.measure(p, width, null, r);
                chain += paras.collapse(m[0], after) + (Boolean.TRUE.equals(p.pp.keepLines) ? m[1] : m[3]);
            } else if (last instanceof TableBlock t) {
                chain += tables.firstRowHeight(t, width);
                headed = !t.rows.isEmpty() && Boolean.TRUE.equals(t.rows.get(0).rp.header);
            }
        }
        // A table whose header rows start no page here takes what keeps with it along, however tall they are
        if (r.y + chain > r.limit() && (chain <= r.frameHeight() || headed)) {
            r.newFrame(false, false);
        }
    }

    private List<Block> tailBlocks;

    private int tailEnd = -1;

    private int tailFrom;

    private float tailWidth;

    private float[] tailHeights;

    private float[] tailBefore;

    private void stretchTail(List<Block> blocks, int i, PageFlow pf) {
        float room = pf.tailRoom();
        if (room <= 0 || pf.atTop()) {
            return;
        }
        if (blocks != tailBlocks || i >= tailEnd || pf.width() != tailWidth) {
            tailBlocks = blocks;
            tailWidth = pf.width();
            tailHeights = null;
            if (chapterEnd(blocks, i, pf)) {
                tailHeights(blocks, i, tailEnd, pf, pf.frameHeight() + room);
            }
        }
        if (tailHeights == null || i < tailFrom) {
            return;
        }
        float tail = tailHeights[i - tailFrom] + paras.collapse(tailBefore[i - tailFrom], pf.lastAfter);
        float free = pf.limit() - pf.y;
        if (tail > free && tail <= free + room) {
            pf.stretchTail(room);
        }
    }

    private boolean chapterEnd(List<Block> blocks, int i, PageFlow pf) {
        for (int j = i; j < blocks.size(); j++) {
            if (blocks.get(j) instanceof Para p) {
                if (j > i && startsPage(p)) {
                    tailEnd = j;
                    return true;
                }
                if (hardBreak(p) != NO_BREAK || FrameFlow.framed(p)) {
                    tailEnd = j + 1;
                    return false;
                }
            }
        }
        tailEnd = blocks.size();
        return pf.endsPage();
    }

    // The height of the blocks from each one to the end of the chapter, from the end back as far as a page reaches
    private void tailHeights(List<Block> blocks, int from, int end, PageFlow pf, float most) {
        float[] heights = new float[end - from];
        float[] befores = new float[end - from];
        float sum = 0;
        float nextBefore = 0;
        int k = end - 1;
        for (; k >= from && sum <= most; k--) {
            Block b = blocks.get(k);
            float before;
            if (b instanceof Para p && !hasNotes(p)) {
                float[] m = paras.measure(p, tailWidth, k + 1 < end ? blocks.get(k + 1) : null, pf);
                sum += m[1] + (k == end - 1 ? 0 : m[2] + paras.collapse(nextBefore, m[2]));
                before = m[0];
            } else if (b instanceof TableBlock t && t.rows.size() <= TAIL_ROWS && t.tp.floating == null) {
                sum += tables.height(t, tailWidth) + (k == end - 1 ? 0 : nextBefore);
                before = 0;
            } else {
                break;
            }
            heights[k - from] = sum;
            befores[k - from] = before;
            nextBefore = before;
        }
        tailFrom = k + 1;
        if (tailFrom < end) {
            tailHeights = Arrays.copyOfRange(heights, tailFrom - from, end - from);
            tailBefore = Arrays.copyOfRange(befores, tailFrom - from, end - from);
        }
    }

    private static final int TAIL_ROWS = 60;

    private static boolean hasNotes(Para p) {
        for (Inline in : p.items) {
            if (in instanceof Inline.NoteRef) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsPage(Para p) {
        return hardBreak(p) == LEADING_BREAK || Boolean.TRUE.equals(p.pp.pageBreakBefore);
    }

    private static boolean continuesChain(List<Block> blocks, int i) {
        return i > 0 && blocks.get(i - 1) instanceof Para prev && Boolean.TRUE.equals(prev.pp.keepNext)
                && !ParaFlow.hidden(prev) && !prev.sectionMark() && hardBreak(prev) == NO_BREAK;
    }

    private static final int NO_BREAK = 0;

    private static final int LEADING_BREAK = 1;

    private static final int LATER_BREAK = 2;

    private static int hardBreak(Para p) {
        boolean leading = true;
        for (Inline in : p.items) {
            if (in instanceof Inline.Break b && !b.rp().hidden()
                    && ("page".equals(b.type()) || "column".equals(b.type()))) {
                return leading ? LEADING_BREAK : LATER_BREAK;
            }
            if (!(in instanceof Inline.Bookmark || in instanceof Inline.Text t && t.text().isEmpty())) {
                leading = false;
            }
        }
        return NO_BREAK;
    }
}
