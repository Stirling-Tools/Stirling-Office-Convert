package stirling.software.officeconvert.topdf.docx;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

final class BlockFlow {

    private static final int CHAIN_LIMIT = 24;

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
            if (b instanceof Para p && r instanceof PageFlow pf && FrameFlow.framed(p)) {
                i = frames.place(blocks, i, pf) - 1;
            } else if (b instanceof Para p && r instanceof StackLayout s && s.floatsFrames() && FrameFlow.floats(p)) {
                i = frames.place(blocks, i, s) - 1;
            } else if (b instanceof Para p) {
                boolean sectionMark = p.sectionMark();
                if (r.paginated() && Boolean.TRUE.equals(p.pp.keepNext) && !r.atTop() && !ParaFlow.hidden(p)
                        && !sectionMark) {
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
                && Boolean.TRUE.equals(p.pp.keepNext) && hardBreak(p) == NO_BREAK) {
            Block next = j + 1 < blocks.size() ? blocks.get(j + 1) : null;
            float[] m = paras.measure(p, width, next, r);
            chain += paras.collapse(m[0], after) + m[1] + m[2];
            after = m[2];
            j++;
            if (chain > r.frameHeight()) {
                return;
            }
        }
        if (j < blocks.size()) {
            Block last = blocks.get(j);
            if (last instanceof Para p && hardBreak(p) != LEADING_BREAK) {
                float[] m = paras.measure(p, width, null, r);
                chain += paras.collapse(m[0], after) + m[3];
            } else if (last instanceof TableBlock t) {
                chain += tables.firstRowHeight(t, width);
            }
        }
        if (r.y + chain > r.limit() && chain <= r.frameHeight()) {
            r.newFrame(false, false);
        }
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
