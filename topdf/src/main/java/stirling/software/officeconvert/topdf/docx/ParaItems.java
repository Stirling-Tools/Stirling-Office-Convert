package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import stirling.software.officeconvert.topdf.font.BidiRuns;
import stirling.software.officeconvert.topdf.font.CloudFonts;
import stirling.software.officeconvert.topdf.font.FontFace;

final class ParaItems {

    final Para para;

    final List<Item> items = new ArrayList<>();

    final Look markLook;

    String text = "";

    boolean[] breaks;

    int labelStart;

    private final Ctx ctx;

    private final Color background;

    private final StringBuilder all = new StringBuilder();

    ParaItems(Ctx ctx, Para para, Color background) {
        this(ctx, para, background, false);
    }

    ParaItems(Ctx ctx, Para para, Color background, boolean paginated) {
        this.ctx = ctx;
        this.para = para;
        this.background = background;
        this.markLook = look(para.mark, primaryFace(para.mark, ""), null, para.mark.fontSize());
        build(paginated);
    }

    private Color fallbackColor() {
        return Colors.dark(background) ? Color.WHITE : Color.BLACK;
    }

    private void build(boolean paginated) {
        int lead = paginated ? leadingBreaks() : 0;
        for (int i = 0; i < lead; i++) {
            addInline(para.items.get(i));
        }
        labelStart = items.size();
        // A paragraph that holds only a page or column break shows no number
        if (lead == 0 || !blank(lead)) {
            addLabel();
        }
        for (int i = lead; i < para.items.size(); i++) {
            addInline(para.items.get(i));
        }
        text = all.toString();
        breaks = Breaks.compute(text);
        autoSpace();
    }

    // Word treats a page or column break that opens a paragraph as a break before it: the number follows it
    private int leadingBreaks() {
        int lead = 0;
        for (int i = 0; i < para.items.size(); i++) {
            Inline in = para.items.get(i);
            if (in instanceof Inline.Break b && !b.rp().hidden()
                    && ("page".equals(b.type()) || "column".equals(b.type()))) {
                lead = i + 1;
            } else if (!empty(in)) {
                break;
            }
        }
        return lead;
    }

    private boolean blank(int from) {
        for (int i = from; i < para.items.size(); i++) {
            if (!empty(para.items.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean empty(Inline in) {
        return in instanceof Inline.Bookmark || in instanceof Inline.Text t && (t.text().isEmpty() || t.rp().hidden());
    }

    private void addLabel() {
        if (para.label != null && !para.label.isEmpty() && para.level != null) {
            int before = items.size();
            addText(para.label, para.labelProps, null, true);
            for (int i = before; i < items.size(); i++) {
                items.get(i).label = true;
            }
            String suffix = para.level.suffix;
            if ("tab".equals(suffix)) {
                Item tab = new Item(Item.Kind.TAB);
                tab.text = "\t";
                tab.look = look(para.labelProps, primaryFace(para.labelProps, " "), null,
                        para.labelProps.fontSize());
                tab.label = true;
                add(tab);
            } else if ("space".equals(suffix)) {
                int b = items.size();
                addText(" ", para.labelProps, null);
                for (int i = b; i < items.size(); i++) {
                    items.get(i).label = true;
                }
            }
        }
    }

    private void addInline(Inline in) {
        switch (in) {
            case Inline.Text t -> {
                if (!t.rp().hidden()) {
                    addText(t.text(), t.rp(), t.link());
                }
            }
            case Inline.Tab t -> {
                if (!t.rp().hidden()) {
                    Item tab = new Item(Item.Kind.TAB);
                    tab.text = "\t";
                    tab.look = look(t.rp(), primaryFace(t.rp(), " "), t.link(), t.rp().fontSize());
                    tab.link = t.link();
                    add(tab);
                }
            }
            case Inline.PTab t -> {
                Item tab = new Item(Item.Kind.TAB);
                tab.text = "\t";
                tab.look = look(t.rp(), primaryFace(t.rp(), " "), null, t.rp().fontSize());
                tab.ptab = t;
                add(tab);
            }
            case Inline.Break b -> {
                if (b.rp().hidden()) {
                    return;
                }
                Item br = new Item(Item.Kind.BREAK);
                br.text = "\n";
                br.breakType = b.type();
                br.look = look(b.rp(), primaryFace(b.rp(), " "), null, b.rp().fontSize());
                add(br);
            }
            case Inline.Field f -> {
                if (!f.rp().hidden()) {
                    if (ctx.headerDepth == 0 && (f.name().equals("NUMPAGES") || f.name().equals("SECTIONPAGES"))) {
                        ctx.bodyTotals = true;
                    }
                    String value = switch (f.name()) {
                        case "PAGE" -> f.format() == null ? ctx.numbers.page()
                                : NumberFormat.format(ctx.numbers.pageNumber(), f.format());
                        case "NUMPAGES" -> formatted(ctx.numbers.pages(), f.format());
                        case "SECTIONPAGES" -> formatted(ctx.numbers.sectionPages(), f.format());
                        default -> f.cached();
                    };
                    int b = items.size();
                    addText(value, f.rp(), f.link());
                    for (int i = b; i < items.size(); i++) {
                        items.get(i).field = f.name();
                    }
                }
            }
            case Inline.NoteRef n -> {
                String mark = n.customMark() != null ? n.customMark() : ctx.noteNumber(n);
                int b = items.size();
                if (!mark.isEmpty()) {
                    addText(mark, n.rp(), null);
                }
                if (b < items.size()) {
                    items.get(b).note = n;
                } else {
                    Item z = new Item(Item.Kind.NOTE);
                    z.note = n;
                    z.look = markLook;
                    add(z);
                }
            }
            case Inline.NoteMark m -> {
                String mark = ctx.currentNoteMark;
                if (mark != null && !mark.isEmpty()) {
                    addText(mark, m.rp(), null);
                }
            }
            case Inline.Obj o -> {
                Drawing d = o.drawing();
                if (d.inline) {
                    Item obj = new Item(Item.Kind.OBJECT);
                    obj.text = "\uFFFC";
                    obj.drawing = d;
                    obj.objectWidth = Math.max(0, d.width + d.effL + d.effR);
                    obj.objectHeight = Math.max(0, d.height + d.effT + d.effB);
                    obj.look = look(o.rp(), primaryFace(o.rp(), " "), o.link(), o.rp().fontSize());
                    obj.link = o.link();
                    add(obj);
                } else {
                    Item a = new Item(Item.Kind.ANCHOR);
                    a.drawing = d;
                    a.look = markLook;
                    add(a);
                }
            }
            case Inline.Bookmark b -> {
                Item bm = new Item(Item.Kind.BOOKMARK);
                bm.bookmark = b.name();
                bm.look = markLook;
                add(bm);
            }
        }
    }

    private void autoSpace() {
        boolean letters = !Boolean.FALSE.equals(para.pp.autoSpaceDE);
        boolean digits = !Boolean.FALSE.equals(para.pp.autoSpaceDN);
        if (!letters && !digits) {
            return;
        }
        for (int i = 0; i + 1 < items.size(); i++) {
            Item a = items.get(i);
            Item b = items.get(i + 1);
            if (a.kind != Item.Kind.TEXT || b.kind != Item.Kind.TEXT || a.text.isEmpty() || b.text.isEmpty()) {
                continue;
            }
            int last = a.text.codePointBefore(a.text.length());
            int first = b.text.codePointAt(0);
            Item asian = null;
            if (ideograph(last) && latin(first, letters, digits)) {
                asian = a;
            } else if (latin(last, letters, digits) && ideograph(first)) {
                asian = b;
            }
            if (asian != null) {
                a.extra += asian.look.size() / 4f;
            }
        }
    }

    private static boolean ideograph(int cp) {
        if (!Fonts.eastAsian(cp)) {
            return false;
        }
        int t = Character.getType(cp);
        return t == Character.OTHER_LETTER || t == Character.MODIFIER_LETTER || t == Character.LETTER_NUMBER;
    }

    private static boolean latin(int cp, boolean letters, boolean digits) {
        if (cp < 0x30 || cp > 0x24F) {
            return false;
        }
        if (Character.isDigit(cp)) {
            return digits;
        }
        return letters && Character.isLetter(cp);
    }

    private void add(Item item) {
        item.start = all.length();
        all.append(item.text);
        items.add(item);
    }

    private void addText(String raw, RunProps rp, Inline.Link link) {
        addText(raw, rp, link, false);
    }

    private void addText(String raw, RunProps rp, Inline.Link link, boolean label) {
        if (raw.isEmpty()) {
            return;
        }
        String s = raw;
        if (Boolean.TRUE.equals(rp.caps)) {
            s = s.toUpperCase(Locale.ROOT);
        }
        boolean small = Boolean.TRUE.equals(rp.smallCaps) && !Boolean.TRUE.equals(rp.caps);
        int i = 0;
        int n = s.length();
        while (i < n) {
            int cp = s.codePointAt(i);
            Fonts.Slot slot = Fonts.slot(cp, rp);
            boolean lower = small && Character.isLowerCase(cp);
            int j = i + Character.charCount(cp);
            while (j < n) {
                int c2 = s.codePointAt(j);
                if (Fonts.slot(c2, rp) != slot && !(neutral(c2) && slot != Fonts.Slot.COMPLEX)) {
                    break;
                }
                if (small && Character.isLowerCase(c2) != lower && !neutral(c2)) {
                    break;
                }
                j += Character.charCount(c2);
            }
            String piece = s.substring(i, j);
            float nominal = slot == Fonts.Slot.COMPLEX ? rp.fontSizeCs() : rp.fontSize();
            float full = nominal;
            if (lower) {
                piece = piece.toUpperCase(Locale.ROOT);
                // Word sets small capitals at 80 % of the size, rounded to the nearest half point
                nominal = Math.max(0.5f, Math.round(nominal * 1.6f) / 2f);
            }
            FontFace face = face(rp, slot);
            piece = SymbolChars.remap(piece, face, label, c -> covers(faceFor(face, c), c));
            addCovered(piece, face, rp, nominal, full, link);
            i = j;
        }
    }

    private static boolean neutral(int cp) {
        return cp == ' ' || cp == 0x00A0;
    }

    private void addCovered(String piece, FontFace face, RunProps rp, float nominal, float full, Inline.Link link) {
        int i = 0;
        int n = piece.length();
        while (i < n) {
            int cp = piece.codePointAt(i);
            FontFace f = faceFor(face, cp);
            int j = i + Character.charCount(cp);
            while (j < n) {
                int c2 = piece.codePointAt(j);
                if (!faceFor(face, c2).equals(f) && !(covers(f, c2) && neutral(c2))) {
                    break;
                }
                j += Character.charCount(c2);
            }
            Item it = new Item(Item.Kind.TEXT);
            it.text = piece.substring(i, j);
            it.look = f == face ? look(rp, f, link, nominal) : fallbackLook(rp, f, face, link, nominal);
            if (full > nominal) {
                // Small capitals keep the line height of the full size
                it.look = it.look.scaledMetrics(full / nominal);
            }
            it.link = link;
            it.lang = rp.lang;
            it.shaped = FontFace.needsShaping(it.text) && f.shapeable();
            it.rtl = BidiRuns.needed(it.text) && BidiRuns.baseRightToLeft(it.text);
            add(it);
            i = j;
        }
    }

    private FontFace faceFor(FontFace face, int cp) {
        return covers(face, cp) ? face : ctx.fonts.fallback(cp, face);
    }

    private static boolean covers(FontFace f, int cp) {
        return f.covers(cp) || cp == ' ' || cp == 0x00AD || cp == 0x200B || Character.getType(cp) == Character.FORMAT
                || cp == 0x2011 && f.covers('-');
    }

    FontFace face(RunProps rp, Fonts.Slot slot) {
        boolean cs = slot == Fonts.Slot.COMPLEX;
        boolean bold = cs ? Boolean.TRUE.equals(rp.boldCs != null ? rp.boldCs : rp.bold) : rp.isBold();
        boolean italic = cs ? Boolean.TRUE.equals(rp.italicCs != null ? rp.italicCs : rp.italic) : rp.isItalic();
        return ctx.fonts.face(ctx.fonts.family(rp, slot), bold, italic);
    }

    private FontFace primaryFace(RunProps rp, String sample) {
        int cp = sample.isEmpty() ? 'x' : sample.codePointAt(0);
        return face(rp, Fonts.slot(cp, rp));
    }

    private Look look(RunProps rp, FontFace face, Inline.Link link, float nominal) {
        Color bg = rp.shadingColor() != null ? rp.shadingColor() : rp.highlightColor();
        Color fallback = bg != null ? (Colors.dark(bg) ? Color.WHITE : Color.BLACK) : fallbackColor();
        return Look.of(face, rp, nominal, link, fallback, ctx.fonts.emulation(face));
    }

    // A character the stand-in for a missing font lacks still takes that font's line height
    private Look fallbackLook(RunProps rp, FontFace f, FontFace requested, Inline.Link link, float nominal) {
        CloudFonts.Emulation own = ctx.fonts.emulation(f);
        if (own == null && !Look.eastAsianGlyphs(f)) {
            CloudFonts.Emulation wanted = ctx.fonts.emulation(requested);
            if (wanted != null && wanted.vertical() != null && wanted.scale() == 100) {
                own = wanted;
            }
        }
        Color bg = rp.shadingColor() != null ? rp.shadingColor() : rp.highlightColor();
        Color fallback = bg != null ? (Colors.dark(bg) ? Color.WHITE : Color.BLACK) : fallbackColor();
        return Look.of(f, rp, nominal, link, fallback, own);
    }

    // A right or centred list label ends or centres on the number position instead of starting there
    float labelShift() {
        String jc = para.level == null ? null : para.level.jc;
        if (jc == null || jc.equals("left") || jc.equals("start")) {
            return 0;
        }
        float w = 0;
        for (Item it : items) {
            if (!it.label) {
                break;
            }
            if (it.kind == Item.Kind.TEXT) {
                w += it.width();
            }
        }
        return jc.equals("center") ? w / 2 : jc.equals("right") || jc.equals("end") ? w : 0;
    }

    private static String formatted(String number, String format) {
        if (format == null) {
            return number;
        }
        try {
            return NumberFormat.format(Integer.parseInt(number.strip()), format);
        } catch (NumberFormatException e) {
            return number;
        }
    }

    boolean breakBefore(int pos) {
        return pos >= breaks.length || breaks[pos];
    }
}
