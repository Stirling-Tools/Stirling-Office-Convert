package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.Stroke;

final class ParaFlow {

    static final float AUTO_SPACING = 14;

    private final Ctx ctx;

    ParaFlow(Ctx ctx) {
        this.ctx = ctx;
    }

    record Box(float x0, float x1, Border top, Border bottom, Border left, Border right, Border between, Color fill,
            float topExtra, float bottomExtra, boolean joinPrev, boolean joinNext) {

        boolean visible() {
            return fill != null || ParaProps.visible(top) || ParaProps.visible(bottom) || ParaProps.visible(left)
                    || ParaProps.visible(right) || ParaProps.visible(between);
        }
    }

    static boolean hidden(Para p) {
        if (!p.mark.hidden()) {
            return false;
        }
        for (Inline in : p.items) {
            RunProps rp = switch (in) {
                case Inline.Text t -> t.rp();
                case Inline.Tab t -> t.rp();
                case Inline.Break b -> b.rp();
                case Inline.Field f -> f.rp();
                case Inline.NoteRef n -> n.rp();
                case Inline.Obj o -> o.rp();
                default -> null;
            };
            if (rp != null && !rp.hidden()) {
                return false;
            }
        }
        return true;
    }

    float before(Para p, Region r) {
        ParaProps pp = p.pp;
        float b = pp.before == null ? 0 : pp.before;
        Para prev = r.lastPara;
        if (Boolean.TRUE.equals(pp.beforeAuto) && !ctx.settings.htmlAutoSpacingOff) {
            boolean top = r.firstInFlow() && !(r instanceof StackLayout s && s.inNote());
            b = top || listed(p) && listed(prev) ? 0 : AUTO_SPACING;
        }
        if (prev == null && r instanceof StackLayout s && s.inCell()) {
            // Word compares a cell's first paragraph with the one before it in the document, even outside the cell
            prev = p.docPrev;
        }
        if (Boolean.TRUE.equals(pp.contextualSpacing) && prev != null && Objects.equals(prev.styleId, p.styleId)) {
            b = 0;
        }
        return collapse(b, Math.max(r.lastAfter, suppressedAfter(prev, p, r)));
    }

    // Word collapses the space before against the space after that contextual spacing took from the paragraph above
    private static float suppressedAfter(Para prev, Para p, Region r) {
        if (prev == null || prev != r.lastPara || !Boolean.TRUE.equals(prev.pp.contextualSpacing)
                || Boolean.TRUE.equals(prev.pp.afterAuto) || prev.pp.after == null
                || !Objects.equals(prev.styleId, p.styleId)) {
            return 0;
        }
        return prev.pp.after;
    }

    // Word lets the larger of the space after and the space before win, unless HTML spacing rules are off
    float collapse(float before, float lastAfter) {
        return ctx.settings.htmlAutoSpacingOff ? before : Math.max(0, before - lastAfter);
    }

    float after(Para p, Block next, Region r) {
        ParaProps pp = p.pp;
        float a = pp.after == null ? 0 : pp.after;
        if (Boolean.TRUE.equals(pp.afterAuto) && !ctx.settings.htmlAutoSpacingOff) {
            boolean last = next == null && r instanceof StackLayout s && s.inCell();
            a = last || listed(p) && next instanceof Para n && listed(n) ? 0 : AUTO_SPACING;
        }
        if (!(next instanceof Para) && r instanceof StackLayout s && s.inCell() && p.docNext != null) {
            next = p.docNext;
        }
        if (Boolean.TRUE.equals(pp.contextualSpacing) && next instanceof Para n && Objects.equals(n.styleId, p.styleId)) {
            a = 0;
        }
        return a;
    }

    // HTML auto spacing leaves no gap between list items, as between the items of an HTML list
    static boolean listed(Para p) {
        return p != null && p.level != null && p.pp.numId != null && p.pp.numId > 0;
    }

    private static boolean sameBox(Para a, Para b) {
        if (a == null || b == null) {
            return false;
        }
        ParaProps x = a.pp;
        ParaProps y = b.pp;
        if (!x.hasBorders() && x.shadingColor() == null) {
            return false;
        }
        return Objects.equals(x.bdrTop, y.bdrTop) && Objects.equals(x.bdrBottom, y.bdrBottom)
                && Objects.equals(x.bdrLeft, y.bdrLeft) && Objects.equals(x.bdrRight, y.bdrRight)
                && Objects.equals(x.bdrBetween, y.bdrBetween) && Objects.equals(x.shadingColor(), y.shadingColor())
                && Math.abs(x.left() - y.left()) < 0.1f && Math.abs(x.right() - y.right()) < 0.1f;
    }

    Box box(Para p, Para prev, Block next, float width) {
        ParaProps pp = p.pp;
        boolean joinPrev = sameBox(prev, p);
        boolean joinNext = next instanceof Para n && sameBox(p, n);
        Border top = pp.bdrTop;
        Border bottom = pp.bdrBottom;
        Border between = pp.bdrBetween;
        float topExtra = 0;
        if (joinPrev) {
            if (ParaProps.visible(between)) {
                topExtra = between.space() + between.width();
            }
        } else if (ParaProps.visible(top)) {
            topExtra = top.space() + top.width();
        }
        float bottomExtra = !joinNext && ParaProps.visible(bottom) ? bottom.space() + bottom.width() : 0;
        boolean rtl = Boolean.TRUE.equals(pp.bidi);
        float start = Math.min(pp.left(), pp.left() + pp.first());
        float x0 = rtl ? pp.right() : start;
        float x1 = width - (rtl ? start : pp.right());
        if (ParaProps.visible(pp.bdrLeft)) {
            x0 -= pp.bdrLeft.space() + pp.bdrLeft.width();
        }
        if (ParaProps.visible(pp.bdrRight)) {
            x1 += pp.bdrRight.space() + pp.bdrRight.width();
        }
        return new Box(x0, x1, top, bottom, pp.bdrLeft, pp.bdrRight, between, pp.shadingColor(), topExtra, bottomExtra,
                joinPrev, joinNext);
    }

    void place(Para p, Region r, Block next) {
        if (hidden(p)) {
            return;
        }
        if (p.sectionMark() && r.paginated()) {
            if (r instanceof PageFlow pf && pf.breaksToNewPage()) {
                // The space after the paragraph before it ends with the page and never makes a page of its own
                r.lastAfter = after(p, next, r);
                r.lastPara = p;
                r.lastBoxed = false;
            } else {
                // A mark shows nothing across a continuous break: its space after takes no room but still absorbs
                // the next space before
                r.lastAfter = after(p, next, r);
            }
            return;
        }
        ParaProps pp = p.pp;
        if (Boolean.TRUE.equals(pp.pageBreakBefore) && r.paginated() && !r.atTop()) {
            r.newFrame(true, true);
        } else if (r instanceof PageFlow pf && pf.breaksBefore(p) && !r.atTop()) {
            r.newFrame(true, false);
        }
        Para prev = r.lastPara;
        float before = before(p, r);
        float after = after(p, next, r);
        Box box = box(p, prev, next, r.width());
        if (!box.visible()) {
            box = null;
        }
        ParaItems pi = new ParaItems(ctx, p, box != null && box.fill() != null ? box.fill() : r.background,
                r.paginated());
        LineBreaker lb = breaker(pi, pp, r.width());
        float grid = gridPitch(r);
        boolean firstFrame = true;
        boolean reopened = false;
        boolean startedHere = true;
        List<Object> anchors = null;
        int guard = 0;
        while (true) {
            if (++guard > 100_000) {
                break;
            }
            boolean suppress = firstFrame && r.frameStart() && r.softTop();
            float gap = (firstFrame || reopened) && !suppress ? before : 0;
            reopened = false;
            float top = r.y + gap;
            if (firstFrame && startedHere) {
                anchors = registerAnchors(pi, r, r.y, pp);
                if (anchors != null && r.paginated() && !r.atTop() && !r.anchorsFit(anchors)) {
                    r.unanchor(anchors);
                    anchors = null;
                    r.newFrame(false, false);
                    continue;
                }
            }
            float topExtra = firstFrame && startedHere && box != null ? box.topExtra() : 0;
            List<Line> lines = new ArrayList<>();
            List<Float> tops = new ArrayList<>();
            int saveItem = lb.position();
            int saveOffset = lb.offset();
            boolean saveFirst = lb.first();
            float yy = top + topExtra;
            boolean complete = false;
            float estimate = pi.markLook.ascent() + pi.markLook.descent();
            while (true) {
                float pre = lines.isEmpty() ? gap + topExtra : 0;
                Line line = layoutLine(lb, pi, pp, r, grid, yy, pre, estimate);
                if (r.paginated() && breakOnly(line) && !lb.restEmpty()) {
                    // A page or column break that opens a paragraph takes no room in the frame it leaves
                    line.height = 0;
                    line.slack = 0;
                }
                yy = line.top;
                lines.add(line);
                tops.add(yy);
                yy += line.height;
                estimate = line.height;
                if ("clear".equals(line.breakType) && r.paginated()) {
                    yy = r.clearFloats(yy, 1, r.left(), r.left() + r.width());
                }
                if ("end".equals(line.breakType)) {
                    complete = true;
                    break;
                }
                if (r.paginated() && ("page".equals(line.breakType) || "column".equals(line.breakType))) {
                    complete = true;
                    break;
                }
                if (r.paginated() && yy > r.limit() && lines.size() > 2 && tops.get(lines.size() - 2) > r.limit()) {
                    break;
                }
            }
            int n = lines.size();
            int k = n;
            List<Inline.NoteRef> notes = new ArrayList<>();
            boolean noteSplit = false;
            List<List<Inline.NoteRef>> lineNotes = new ArrayList<>();
            List<Strip> strips = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                // Joined boxes run on through the spacing between their paragraphs
                float up = box != null && box.joinPrev() ? gap : 0;
                float down = box != null && box.joinNext() ? after : 0;
                Strip s = strip(lines.get(i), i, n, complete, box, firstFrame && startedHere, pi, up, down);
                strips.add(s);
                lineNotes.add(s.notes == null ? List.of() : s.notes);
            }
            if (r.paginated()) {
                k = 0;
                for (int i = 0; i < n; i++) {
                    float bottom = tops.get(i) + lines.get(i).height;
                    // The extra space of multiple line spacing may hang into the bottom margin, not over footnotes or
                    // past a balanced column's end
                    float slack = r.hardLimit() ? 0 : lines.get(i).slack;
                    boolean roomless = lines.get(i).height == 0 && breakOnly(lines.get(i));
                    if (bottom - slack > r.limit() + 0.01f && !roomless) {
                        break;
                    }
                    // Nor may the space after a paragraph's last line run over the footnotes below it
                    if (r.hardLimit() && complete && i == n - 1 && "end".equals(lines.get(i).breakType)
                            && bottom + after > r.limit() + 0.01f) {
                        break;
                    }
                    if (!lineNotes.get(i).isEmpty()) {
                        List<Inline.NoteRef> trial = new ArrayList<>(notes);
                        trial.addAll(lineNotes.get(i));
                        float need = bottom;
                        if (complete && i == n - 1 && "end".equals(lines.get(i).breakType)) {
                            need += after;
                        }
                        if (!r.notesFit(trial, need, true)) {
                            break;
                        }
                        noteSplit |= !r.notesFit(trial, need, false);
                        notes = trial;
                    } else if (!notes.isEmpty()) {
                        float need = bottom + (complete && i == n - 1 && "end".equals(lines.get(i).breakType)
                                ? after : 0);
                        // A note that fits whole stays whole; one that already continues may give later lines room
                        if (!r.notesFit(notes, need, noteSplit)) {
                            break;
                        }
                    }
                    k++;
                }
                if (k < n) {
                    boolean firstPart = firstFrame && startedHere;
                    if (Boolean.TRUE.equals(pp.keepLines) && firstPart && !r.atTop()) {
                        k = 0;
                    }
                    boolean widow = !Boolean.FALSE.equals(pp.widowControl);
                    if (widow && k > 0) {
                        if (firstPart && k == 1 && (n > 1 || !complete)) {
                            k = 0;
                        } else if (complete && n - k == 1 && !"page".equals(lines.get(n - 1).breakType)) {
                            k--;
                            if (firstPart && k == 1) {
                                k = 0;
                            }
                        }
                    }
                    if (k == 0 && r.atTop()) {
                        k = 1;
                    }
                }
            }
            if (k == 0) {
                if (anchors != null) {
                    r.unanchor(anchors);
                    anchors = null;
                }
                lb.reset(saveItem, saveOffset, saveFirst);
                r.newFrame(false, false);
                continue;
            }
            List<Inline.NoteRef> placedNotes = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                Strip s = strips.get(i);
                float stripTop = tops.get(i) - (i == 0 ? topExtra : 0);
                float g = i == 0 ? gap : 0;
                r.place(s, r.left(), stripTop, g);
                placedNotes.addAll(lineNotes.get(i));
                r.y = stripTop + s.height;
            }
            Line lastPlaced = lines.get(k - 1);
            boolean paragraphDone = k == n && complete && "end".equals(lastPlaced.breakType);
            if (!placedNotes.isEmpty()) {
                // The space after a paragraph that ends here stays clear of its notes, as the fit check assumed
                r.addNotes(placedNotes, paragraphDone ? after : 0);
            }
            if (paragraphDone) {
                break;
            }
            if (k < n) {
                Line resume = lines.get(k);
                lb.reset(resume.startItem, resume.startOffset, false);
            } else if (!complete) {
                continue;
            }
            boolean opening = firstFrame && startedHere && n == 1 && breakOnly(lastPlaced);
            firstFrame = false;
            startedHere = false;
            if (k == n && ("page".equals(lastPlaced.breakType) || "column".equals(lastPlaced.breakType))) {
                boolean page = "page".equals(lastPlaced.breakType);
                // Text after a break that opens the paragraph starts it again, with its space before
                reopened = opening;
                if (opening) {
                    lb.reset(lb.position(), lb.offset(), true);
                }
                r.newFrame(page, !(page && lb.restEmpty()));
                if (lb.restEmpty() && "page".equals(lastPlaced.breakType)) {
                    if (r instanceof PageFlow pf) {
                        pf.pageBreakTop();
                    }
                    after = 0;
                    break;
                }
                continue;
            }
            r.newFrame(false, false);
        }
        r.y += after;
        r.lastAfter = after;
        r.lastPara = p;
        r.lastBoxed = false;
    }

    private static boolean breakOnly(Line line) {
        if (!"page".equals(line.breakType) && !"column".equals(line.breakType)) {
            return false;
        }
        for (Line.Slice s : line.slices) {
            if (s.item.kind != Item.Kind.BREAK) {
                return false;
            }
        }
        return true;
    }

    private Line layoutLine(LineBreaker lb, ParaItems pi, ParaProps pp, Region r, float grid, float top, float pre,
            float estimate) {
        int item = lb.position();
        int offset = lb.offset();
        boolean first = lb.first();
        float left0 = pp.left() + (first ? pp.first() : 0);
        float right0 = r.width() - pp.right();
        boolean wraps = r.wraps();
        float word = wraps ? lb.peekWord() : 0;
        if (!lb.peekObject()) {
            // Only an object too wide for any line is set over side-wrapped floats; long text goes below them
            word = Math.min(word, right0 - left0);
        }
        float h = estimate;
        float[] span = wraps ? r.spans(top - pre, pre + h, r.left() + left0, r.left() + right0, word) : null;
        Line line = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            lb.reset(item, offset, first);
            float left = left0;
            float right = right0;
            float lineTop = top;
            if (span != null) {
                lineTop = span[0] + pre;
                left = span[1] - r.left();
                right = span[2] - r.left();
            }
            line = lb.next(left, Math.max(left + 1, right));
            // Right-to-left lines clear of floats are laid out from their start edge, indents and tabs included
            boolean mirrored = Boolean.TRUE.equals(pp.bidi) && (span == null || span.length == 3
                    && Math.abs(left - left0) < 0.01f && Math.abs(right - right0) < 0.01f);
            if (mirrored) {
                LinePainter.align(line, pp, ctx.settings, true);
                BidiLine.mirror(line, r.width());
            } else {
                LinePainter.align(line, pp, ctx.settings);
                BidiLine.reorder(line, Boolean.TRUE.equals(pp.bidi));
            }
            for (int seg = 3; span != null && seg + 1 < span.length && !lb.done()
                    && line.breakType == null; seg += 2) {
                Line more = lb.next(span[seg] - r.left(), Math.max(span[seg] - r.left() + 1,
                        span[seg + 1] - r.left()));
                LinePainter.align(more, pp, ctx.settings);
                BidiLine.reorder(more, Boolean.TRUE.equals(pp.bidi));
                line.append(more);
            }
            LineMetrics.measure(line, pi, pp, grid);
            line.top = lineTop;
            if (span == null || line.height <= h + 0.01f) {
                break;
            }
            h = line.height;
            float[] next = r.spans(top - pre, pre + h, r.left() + left0, r.left() + right0, word);
            if (java.util.Arrays.equals(next, span)) {
                break;
            }
            span = next;
        }
        return line;
    }

    static final float SHRINK = 0.25f;

    private LineBreaker breaker(ParaItems pi, ParaProps pp, float width) {
        ShapeRules.fit(pi, width - pp.left() - pp.right());
        LineBreaker lb = new LineBreaker(pi, pp, ctx.settings.defaultTabStop, width);
        if (ctx.settings.compatibilityMode >= 15 && LinePainter.justified(pp)) {
            lb.shrink(SHRINK);
        }
        if (ctx.settings.autoHyphenation && !Boolean.TRUE.equals(pp.suppressAutoHyphens)) {
            lb.hyphenate(ctx.settings);
        }
        return lb;
    }

    private static float gridPitch(Region r) {
        return r.gridPitch();
    }

    private List<Object> registerAnchors(ParaItems pi, Region r, float top, ParaProps pp) {
        List<Object> handles = null;
        for (Item it : pi.items) {
            if (it.kind == Item.Kind.ANCHOR && it.drawing != null) {
                if (handles == null) {
                    handles = new ArrayList<>();
                }
                handles.addAll(r.anchor(it.drawing, pi.para, pp.left(), top));
            }
        }
        return handles;
    }

    Strip strip(Line line, int index, int count, boolean complete, Box box, boolean paraStart, ParaItems pi,
            float up, float down) {
        Strip s = new Strip();
        s.line = paraStart ? index : -1;
        s.lines = complete && paraStart ? count : 0;
        s.widow = !Boolean.FALSE.equals(pi.para.pp.widowControl);
        s.keepLines = Boolean.TRUE.equals(pi.para.pp.keepLines);
        s.keepNext = Boolean.TRUE.equals(pi.para.pp.keepNext);
        boolean firstLine = index == 0 && paraStart;
        boolean lastLine = complete && index == count - 1 && line.last();
        float topExtra = firstLine && box != null ? box.topExtra() : 0;
        float bottomExtra = lastLine && box != null ? box.bottomExtra() : 0;
        s.height = topExtra + line.height + bottomExtra;
        s.baseline = topExtra + line.baseline;
        s.numbered = !Boolean.TRUE.equals(pi.para.pp.suppressLineNumbers);
        if (box != null) {
            decorate(s, box, firstLine, lastLine, firstLine ? up : 0, lastLine ? down : 0);
        }
        List<Op> lineOps = new ArrayList<>();
        List<Inline.NoteRef> notes = new ArrayList<>();
        LinePainter.paint(line, lineOps, ctx, notes);
        if (pi.para.pp.tabs != null) {
            for (TabStop t : pi.para.pp.tabs) {
                if (t.kind() == TabStop.Kind.BAR) {
                    lineOps.add(new Op.Line(t.pos(), 0, t.pos(), line.height, Stroke.solid(0.5f, java.awt.Color.BLACK)));
                }
            }
        }
        if (topExtra != 0) {
            s.ops.add(new Op.Group(0, topExtra, null, null, lineOps));
        } else {
            s.ops.addAll(lineOps);
        }
        for (Inline.NoteRef n : notes) {
            s.note(n);
        }
        return s;
    }

    private static void decorate(Strip s, Box box, boolean firstLine, boolean lastLine, float up, float down) {
        float h = s.height;
        float y0 = -up;
        float y1 = h + down;
        if (box.fill() != null) {
            s.ops.add(new Op.Rect(box.x0(), y0, box.x1() - box.x0(), y1 - y0, Fill.solid(box.fill()), null));
        }
        Border l = box.left();
        if (ParaProps.visible(l)) {
            float x = box.x0() + l.width() / 2;
            border(s.ops, l, x, y0, x, y1, true);
        }
        Border r = box.right();
        if (ParaProps.visible(r)) {
            float x = box.x1() - r.width() / 2;
            border(s.ops, r, x, y0, x, y1, false);
        }
        if (firstLine) {
            Border t = box.joinPrev() ? box.between() : box.top();
            if (ParaProps.visible(t)) {
                float y = t.width() / 2;
                border(s.ops, t, box.x0(), y, box.x1(), y, true);
            }
        }
        if (lastLine && !box.joinNext() && ParaProps.visible(box.bottom())) {
            Border b = box.bottom();
            float y = h - b.width() / 2;
            border(s.ops, b, box.x0(), y, box.x1(), y, false);
        }
    }

    // outerLow: the border's outer edge faces the top or left, as on a top or left border
    static void border(List<Op> ops, Border b, float x1, float y1, float x2, float y2, boolean outerLow) {
        float w = b.width();
        Stroke stroke = Stroke.solid(Math.max(0.25f, w), b.color());
        boolean horizontal = Math.abs(y1 - y2) < 0.01f;
        switch (b.style()) {
            case "dotted" -> stroke = stroke.dash(0, w, w);
            case "dashed", "dashSmallGap" -> stroke = stroke.dash(0, w * 4, w * 3);
            case "dotDash", "dotDotDash" -> stroke = stroke.dash(0, w * 4, w * 2, w, w * 2);
            case "dashDotStroked" -> stroke = stroke.dash(0, w * 6, w * 2, w * 2, w * 2);
            case "wave", "doubleWave" -> {
                waves(ops, b, x1, y1, x2, y2, horizontal);
                return;
            }
            default -> {
            }
        }
        float[] stripes = b.stripes();
        if (stripes.length == 1) {
            ops.add(new Op.Line(x1, y1, x2, y2, stroke));
            return;
        }
        boolean engrave = b.style().equals("threeDEngrave");
        boolean relief = engrave || b.style().equals("threeDEmboss");
        float out = outerLow ? -1 : 1;
        float edge = b.total() / 2;
        for (int k = 0; k < stripes.length; k += 2) {
            float sw = stripes[k];
            float off = out * (edge - sw / 2);
            edge -= sw + (k + 1 < stripes.length ? stripes[k + 1] : 0);
            Color c = b.color();
            if (relief && (k == 0) == (outerLow != engrave)) {
                c = light(c);
            }
            Stroke line = Stroke.solid(Math.max(0.25f, sw), c);
            if (horizontal) {
                ops.add(new Op.Line(x1, y1 + off, x2, y2 + off, line));
            } else {
                ops.add(new Op.Line(x1 + off, y1, x2 + off, y2, line));
            }
        }
    }

    // The light half of an embossed or engraved border, as Word draws it: three quarters of the way to white, black to BFBFBF
    private static Color light(Color c) {
        return new Color(c.getRed() + (255 - c.getRed()) * 3 / 4, c.getGreen() + (255 - c.getGreen()) * 3 / 4,
                c.getBlue() + (255 - c.getBlue()) * 3 / 4, c.getAlpha());
    }

    // A wavy line along the border, two for doubleWave, swinging as far as the border is wide
    private static void waves(List<Op> ops, Border b, float x1, float y1, float x2, float y2, boolean horizontal) {
        float amp = Math.max(0.75f, b.width());
        Stroke stroke = Stroke.solid(Math.max(0.25f, b.width() / 2), b.color());
        float[] offsets = b.style().equals("doubleWave") ? new float[] {-amp, amp} : new float[] {0};
        float length = horizontal ? Math.abs(x2 - x1) : Math.abs(y2 - y1);
        int halves = Math.max(1, Math.min(10_000, Math.round(length / (amp * 2))));
        float step = length / halves;
        for (float o : offsets) {
            java.awt.geom.Path2D.Float path = new java.awt.geom.Path2D.Float();
            for (int i = 0; i <= halves; i++) {
                float along = i * step;
                float px = horizontal ? Math.min(x1, x2) + along : x1 + o;
                float py = horizontal ? y1 + o : Math.min(y1, y2) + along;
                if (i == 0) {
                    path.moveTo(px, py);
                    continue;
                }
                float mid = along - step / 2;
                float swing = o + (i % 2 == 0 ? -amp : amp);
                float cx = horizontal ? Math.min(x1, x2) + mid : x1 + swing;
                float cy = horizontal ? y1 + swing : Math.min(y1, y2) + mid;
                path.quadTo(cx, cy, px, py);
            }
            ops.add(new Op.Path(path, null, stroke));
        }
    }

    float[] measure(Para p, float width, Block next, Region r) {
        if (hidden(p)) {
            return new float[] {0, 0, 0, 0};
        }
        ParaItems pi = new ParaItems(ctx, p, null, r.paginated());
        LineBreaker lb = breaker(pi, p.pp, width);
        float total = 0;
        float first = 0;
        float firstTwo = 0;
        int count = 0;
        float grid = gridPitch(r);
        while (true) {
            float left = p.pp.left() + (lb.first() ? p.pp.first() : 0);
            Line line = lb.next(left, Math.max(left + 1, width - p.pp.right()));
            LineMetrics.measure(line, pi, p.pp, grid);
            total += line.height;
            // The last kept line may reach into the bottom margin by the extra space of its line spacing
            if (count == 0) {
                first = line.height - line.slack;
            }
            if (count < 2) {
                firstTwo += line.height - (count == 0 ? 0 : line.slack);
            }
            count++;
            // Lines after a page or column break go to the next frame anyway, so they cannot be kept here
            if ("end".equals(line.breakType) || "page".equals(line.breakType) || "column".equals(line.breakType)
                    || count > 5000) {
                break;
            }
        }
        float before = p.pp.before == null ? 0 : p.pp.before;
        float after = after(p, next, r);
        boolean widow = !Boolean.FALSE.equals(p.pp.widowControl) && count > 1;
        // Widow control splits a three-line paragraph nowhere, so it keeps with the paragraph before only whole
        return new float[] {before, total, after, !widow ? first : count == 3 ? total : firstTwo};
    }
}
