package stirling.software.officeconvert.topdf.docx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Footnotes at the foot of each page; a note too long for its page continues on the next ones, as in Word
final class FootnoteFlow {

    private static final float MIN_BODY = 36;

    private final Ctx ctx;

    private final DocxPackage pkg;

    private final Map<String, StackLayout.Result> noteCache = new HashMap<>();

    private final List<PageBox.NotePart> carry = new ArrayList<>();

    private StackLayout.Result separator;

    private StackLayout.Result continuation;

    private float notice = -1;

    FootnoteFlow(Ctx ctx) {
        this.ctx = ctx;
        this.pkg = ctx.pkg;
    }

    void layout(PageBox p) {
        if (p.noteParts.isEmpty()) {
            return;
        }
        float width = noteWidth(p.sect);
        float yy = p.bodyBottom - p.noteHeight;
        StackLayout.Result sep = p.noteParts.get(0).continued() ? continuation(width) : separator(width);
        float x = p.sect.bodyLeft() + p.shift;
        p.noteOps.add(new Op.Group(x, yy, null, null, sep.ops()));
        yy += sep.height();
        for (PageBox.NotePart part : p.noteParts) {
            List<Op> ops = new ArrayList<>();
            for (Placed pl : part.strips()) {
                if (!pl.strip.ops.isEmpty()) {
                    ops.add(new Op.Group(pl.x, pl.y, null, null, pl.strip.ops));
                }
            }
            p.noteOps.add(new Op.Group(x, yy, null, null, ops));
            yy += part.height();
        }
    }

    private static float noteWidth(SectionProps s) {
        return s.cols > 1 ? s.columnWidths()[0] : s.textWidth();
    }

    private StackLayout.Result continuation(float width) {
        if (continuation != null) {
            return continuation;
        }
        for (DocxPackage.Note n : pkg.footnotes.values()) {
            if ("continuationSeparator".equals(n.type())) {
                continuation = StackLayout.layout(n.blocks(), width, ctx);
                return continuation;
            }
        }
        continuation = new StackLayout.Result(List.of(new Op.Line(0, 6, width, 6,
                stirling.software.officeconvert.topdf.pdf.Stroke.solid(0.5f, java.awt.Color.BLACK))), 12, List.of(),
                List.of(), List.of());
        return continuation;
    }

    // Word keeps a footnote's first lines with its reference and continues the rest on the next pages
    void continueOn(PageBox p) {
        if (carry.isEmpty()) {
            return;
        }
        List<PageBox.NotePart> parts = new ArrayList<>(carry);
        carry.clear();
        float width = noteWidth(p.sect);
        float sep = (parts.get(0).continued() ? continuation(width) : separator(width)).height() + notice(width);
        float room = Math.max(12, p.bodyBottom - p.bodyTop - MIN_BODY) - sep;
        float used = 0;
        for (PageBox.NotePart part : parts) {
            if (p.splitNote) {
                carry.add(part);
                continue;
            }
            if (used + part.height() <= room + 0.01f) {
                p.noteParts.add(part);
                used += part.height();
                continue;
            }
            PageBox.NotePart[] pieces = splitNote(part, room - used, p.noteParts.isEmpty());
            if (pieces[0] != null) {
                p.noteParts.add(pieces[0]);
                used += pieces[0].height();
            }
            if (pieces[1] != null) {
                carry.add(pieces[1]);
                p.splitNote = true;
            }
        }
        if (!p.noteParts.isEmpty()) {
            p.noteHeight = sep + used;
        }
    }

    // A note may break between its paragraphs, or inside one where widow control leaves two lines on each side
    // (any line when the paragraph turns it off), but never with only empty lines to carry on
    private static boolean breakable(List<Placed> strips, int k) {
        boolean rest = false;
        for (int i = k; i < strips.size() && !rest; i++) {
            rest = !strips.get(i).strip.ops.isEmpty();
        }
        Strip s = strips.get(k).strip;
        if (!rest) {
            return false;
        }
        if (s.line <= 0 || !s.widow) {
            return true;
        }
        return s.line >= 2 && (s.lines <= 0 || s.lines - s.line >= 2);
    }

    private static PageBox.NotePart[] splitNote(PageBox.NotePart part, float room, boolean force) {
        List<Placed> strips = part.strips();
        int fit = 0;
        while (fit < strips.size() && strips.get(fit).y + strips.get(fit).strip.height <= room + 0.01f) {
            fit++;
        }
        int k = fit;
        while (k > 0 && !breakable(strips, k)) {
            k--;
        }
        if (k <= 0 && force && !strips.isEmpty()) {
            k = Math.max(1, Math.min(fit, strips.size() - 1));
        }
        if (k >= strips.size()) {
            return new PageBox.NotePart[] {part, null};
        }
        if (k <= 0) {
            return new PageBox.NotePart[] {null, part};
        }
        Placed last = strips.get(k - 1);
        float head = last.y + last.strip.height;
        float shift = strips.get(k).y;
        List<Placed> rest = new ArrayList<>();
        for (int i = k; i < strips.size(); i++) {
            Placed s = strips.get(i);
            rest.add(new Placed(s.strip, s.x, s.y - shift, 0, s.section, s.gap));
        }
        return new PageBox.NotePart[] {new PageBox.NotePart(part.ref(), new ArrayList<>(strips.subList(0, k)), head,
                part.continued()), new PageBox.NotePart(part.ref(), rest, Math.max(0, part.height() - shift), true)};
    }

    // The height a note needs on the reference's page: all of it, or its lines up to the first place it may break
    private static float firstPart(StackLayout.Result r) {
        List<Placed> strips = r.placed();
        for (int k = 1; k < strips.size(); k++) {
            if (breakable(strips, k)) {
                return strips.get(k - 1).y + strips.get(k - 1).strip.height;
            }
        }
        return r.height();
    }

    // Word 2010 layout keeps room for the continuation notice below the notes of every page
    private float notice(float width) {
        if (notice < 0) {
            notice = 0;
            if (ctx.settings.compatibilityMode < 15) {
                for (DocxPackage.Note n : pkg.footnotes.values()) {
                    if ("continuationNotice".equals(n.type())) {
                        notice = StackLayout.layout(n.blocks(), width, ctx).height();
                        break;
                    }
                }
            }
        }
        return notice;
    }

    private StackLayout.Result separator(float width) {
        if (separator != null) {
            return separator;
        }
        for (DocxPackage.Note n : pkg.footnotes.values()) {
            if ("separator".equals(n.type())) {
                separator = StackLayout.layout(n.blocks(), width, ctx);
                return separator;
            }
        }
        separator = new StackLayout.Result(List.of(new Op.Line(0, 6, 144, 6,
                stirling.software.officeconvert.topdf.pdf.Stroke.solid(0.5f, java.awt.Color.BLACK))), 12, List.of(),
                List.of(), List.of());
        return separator;
    }

    StackLayout.Result note(Inline.NoteRef ref, float width) {
        String key = (ref.endnote() ? "e" : "f") + ref.id() + "|" + width;
        StackLayout.Result cached = noteCache.get(key);
        if (cached != null) {
            return cached;
        }
        DocxPackage.Note n = (ref.endnote() ? pkg.endnotes : pkg.footnotes).get(ref.id());
        if (n == null) {
            return null;
        }
        String saved = ctx.currentNoteMark;
        ctx.currentNoteMark = ref.customMark() != null ? "" : ctx.noteNumber(ref);
        try {
            StackLayout.Result r = StackLayout.note(n.blocks(), width, ctx);
            noteCache.put(key, r);
            return r;
        } finally {
            ctx.currentNoteMark = saved;
        }
    }

    boolean fits(PageBox page, SectionProps sect, List<Inline.NoteRef> refs, float bottom, boolean split) {
        float extra = 0;
        float lastFull = 0;
        float lastFirst = 0;
        boolean any = !page.noteParts.isEmpty();
        boolean fresh = false;
        float width = noteWidth(sect);
        for (Inline.NoteRef n : refs) {
            if (n.endnote() || page.notes.contains(n)) {
                continue;
            }
            if (!any) {
                extra += separator(width).height() + notice(width);
                any = true;
            }
            StackLayout.Result r = note(n, width);
            lastFull = r == null ? 0 : r.height();
            lastFirst = r == null ? 0 : firstPart(r);
            extra += lastFull;
            fresh = true;
        }
        if (bottom + page.noteHeight + extra <= page.bodyBottom + 0.01f) {
            return true;
        }
        return split && fresh && !page.splitNote
                && bottom + page.noteHeight + extra - lastFull + lastFirst <= page.bodyBottom + 0.01f;
    }

    void add(PageBox page, SectionProps sect, Inline.NoteRef n, float bodyBottom) {
        if (page.notes.contains(n)) {
            return;
        }
        float width = noteWidth(sect);
        page.notes.add(n);
        StackLayout.Result r = note(n, width);
        if (r == null) {
            return;
        }
        PageBox.NotePart whole = new PageBox.NotePart(n, r.placed(), r.height(), false);
        if (page.splitNote) {
            carry.add(whole);
            return;
        }
        float sep = page.noteParts.isEmpty() ? separator(width).height() + notice(width) : 0;
        float room = page.bodyBottom - bodyBottom - page.noteHeight - sep;
        if (r.height() <= room + 0.01f) {
            page.noteParts.add(whole);
            page.noteHeight += sep + r.height();
            return;
        }
        PageBox.NotePart[] pieces = splitNote(whole, room, false);
        if (pieces[0] != null) {
            page.noteParts.add(pieces[0]);
            page.noteHeight += sep + pieces[0].height();
        }
        if (pieces[1] != null) {
            carry.add(pieces[1]);
            page.splitNote = true;
        }
    }

    boolean carrying() {
        return !carry.isEmpty();
    }
}
