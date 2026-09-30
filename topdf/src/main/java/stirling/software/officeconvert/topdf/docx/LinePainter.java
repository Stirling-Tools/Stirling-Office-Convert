package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;
import java.util.List;

import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontMetrics;
import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.Stroke;
import stirling.software.officeconvert.topdf.pdf.TextStyle;

final class LinePainter {

    private LinePainter() {}

    static boolean justified(ParaProps pp) {
        String jc = pp.jc == null ? "left" : pp.jc;
        return switch (jc) {
            case "both", "lowKashida", "mediumKashida", "highKashida" -> true;
            default -> false;
        };
    }

    static void align(Line line, ParaProps pp, Settings settings) {
        align(line, pp, settings, false);
    }

    // A logical line runs from its start edge on the left and is mirrored afterwards, so start means left
    static void align(Line line, ParaProps pp, Settings settings, boolean logical) {
        String jc = pp.jc == null ? "left" : pp.jc;
        boolean rtl = Boolean.TRUE.equals(pp.bidi) && !logical;
        if (rtl) {
            jc = switch (jc) {
                case "left", "start" -> "right";
                case "right", "end" -> "left";
                default -> jc;
            };
        }
        float free = line.right - line.end;
        if (free < -0.01f && justified(pp)) {
            justify(line, free, false);
            return;
        }
        if (free <= 0.01f) {
            return;
        }
        switch (jc) {
            case "center" -> shift(line, free / 2);
            case "right", "end" -> shift(line, free);
            case "both", "distribute", "lowKashida", "mediumKashida", "highKashida", "thaiDistribute" -> {
                boolean distribute = jc.equals("distribute") || jc.equals("thaiDistribute");
                String b = line.breakType;
                boolean justify = distribute
                        || b == null
                        || (b.equals("textWrapping") || b.equals("clear")) && !settings.doNotExpandShiftReturn;
                if (justify && !line.last() || distribute) {
                    justify(line, free, distribute);
                } else if (rtl) {
                    shift(line, free);
                }
            }
            default -> {
            }
        }
    }

    private static void shift(Line line, float dx) {
        for (Line.Slice s : line.slices) {
            s.x += dx;
        }
        line.end += dx;
    }

    private static void justify(Line line, float free, boolean distribute) {
        List<Line.Slice> slices = line.slices;
        int from = 0;
        for (int i = 0; i < slices.size(); i++) {
            if (slices.get(i).item.kind == Item.Kind.TAB) {
                from = i + 1;
            }
        }
        Line.Slice lastTab = from > 0 ? slices.get(from - 1) : null;
        if (!distribute && lastTab != null && lastTab.tab != null && lastTab.tab.kind() != TabStop.Kind.LEFT
                && lastTab.w > 0) {
            float w = lastTab.w;
            lastTab.w = 0;
            for (int i = from; i < slices.size(); i++) {
                slices.get(i).x -= w;
            }
            line.end -= w;
            free += w;
        }
        int lastContent = -1;
        int lastContentChar = -1;
        for (int i = slices.size() - 1; i >= from && lastContent < 0; i--) {
            Line.Slice s = slices.get(i);
            if (s.item.kind == Item.Kind.OBJECT) {
                lastContent = i;
                lastContentChar = s.to;
            } else if (s.item.kind == Item.Kind.TEXT) {
                String t = s.text();
                int j = t.length();
                while (j > 0 && t.charAt(j - 1) == ' ') {
                    j--;
                }
                if (j > 0) {
                    lastContent = i;
                    lastContentChar = s.from + j;
                }
            }
        }
        if (lastContent < 0) {
            return;
        }
        int spaces = 0;
        int chars = 0;
        for (int i = from; i <= lastContent; i++) {
            Line.Slice s = slices.get(i);
            if (s.item.kind != Item.Kind.TEXT) {
                chars++;
                continue;
            }
            int end = i == lastContent ? lastContentChar : s.to;
            for (int k = s.from; k < end; k++) {
                char c = s.item.text.charAt(k);
                if (c == ' ') {
                    spaces++;
                } else if (!Character.isLowSurrogate(c)) {
                    chars++;
                }
            }
        }
        if (spaces > 0 && !distribute) {
            float extra = free / spaces;
            if (extra > 200 || free < 0 && spaces == 0) {
                return;
            }
            float acc = 0;
            for (int i = from; i < slices.size(); i++) {
                Line.Slice s = slices.get(i);
                s.x += acc;
                s.justified = true;
                s.wordExtra = extra;
                if (s.item.kind == Item.Kind.TEXT && i <= lastContent) {
                    int end = i == lastContent ? lastContentChar : s.to;
                    int n = 0;
                    for (int k = s.from; k < end; k++) {
                        if (s.item.text.charAt(k) == ' ') {
                            n++;
                        }
                    }
                    s.spaces = n;
                    s.w += n * extra;
                    acc += n * extra;
                }
            }
            line.end += free;
            return;
        }
        int gaps = spaces + chars - 1;
        if (gaps <= 0 || free < 0 || !distribute && !ideographic(slices, from, lastContent)) {
            return;
        }
        float extra = free / gaps;
        if (extra > 100) {
            return;
        }
        float acc = 0;
        for (int i = from; i < slices.size(); i++) {
            Line.Slice s = slices.get(i);
            s.x += acc;
            s.justified = true;
            s.charExtra = extra;
            if (i > lastContent) {
                continue;
            }
            int n = s.item.kind == Item.Kind.TEXT ? s.text().codePointCount(0, s.to - s.from) : 1;
            if (i == lastContent) {
                n -= 1;
            }
            s.w += Math.max(0, n) * extra;
            acc += Math.max(0, n) * extra;
        }
        line.end += free;
    }

    static void paint(Line line, List<Op> ops, Ctx ctx, List<Inline.NoteRef> notes) {
        float base = line.baseline;
        for (Line.Slice s : line.slices) {
            Item it = s.item;
            Look look = it.look;
            if (look == null || s.w <= 0) {
                continue;
            }
            Color bg = look.shading() != null ? look.shading() : look.highlight();
            if (bg != null && (it.kind == Item.Kind.TEXT || it.kind == Item.Kind.TAB)) {
                float top = base - look.ascent();
                ops.add(new Op.Rect(s.x, top, s.w, look.ascent() + look.descent(), Fill.solid(bg), null));
            }
        }
        StringBuilder run = new StringBuilder();
        Line.Slice runStart = null;
        Look runLook = null;
        float runEnd = 0;
        float runExtra = 0;
        for (Line.Slice s : line.slices) {
            Item it = s.item;
            if (it.kind == Item.Kind.TEXT && !it.shaped && s.to > s.from) {
                if (runStart != null && runLook == it.look && Math.abs(runEnd - s.x) < 0.01f && s.charExtra == 0
                        && runExtra == 0 && s.wordExtra == runStart.wordExtra) {
                    run.append(it.text, s.from, s.to);
                    runEnd = s.x + s.w;
                    runExtra = s.to == it.text.length() ? it.extra : 0;
                    continue;
                }
                flush(ops, run, runStart, runLook, base, line);
                run.setLength(0);
                run.append(it.text, s.from, s.to);
                runStart = s;
                runLook = it.look;
                runEnd = s.x + s.w;
                runExtra = s.to == it.text.length() ? it.extra : 0;
                continue;
            }
            flush(ops, run, runStart, runLook, base, line);
            run.setLength(0);
            runStart = null;
            runLook = null;
            if (it.kind == Item.Kind.TEXT && it.shaped && s.to > s.from) {
                String t = it.text.substring(s.from, s.to);
                ops.add(new Op.Glyphs(s.x, base - it.look.rise(), it.look.face().shape(t, it.rtl), it.look.style()));
            } else if (it.kind == Item.Kind.TAB && s.tab != null && s.tab.leader() != 0 && s.w > 2) {
                leader(ops, s, base);
            } else if (it.kind == Item.Kind.BREAK && s.w > 0) {
                float y = base - it.look.ascent() / 3;
                ops.add(new Op.Line(s.x, y, s.x + s.w, y, Stroke.solid(0.5f, Color.BLACK)));
            } else if (it.kind == Item.Kind.OBJECT && it.drawing != null) {
                float drop = it.look == null ? 0 : line.objectsOnBaseline ? -it.look.rise() : it.look.descent();
                if (it.drawing.baselineDepth >= 0) {
                    drop = it.drawing.baselineDepth - (line.objectsOnBaseline && it.look != null ? it.look.rise() : 0);
                }
                float top = base + drop - it.objectHeight;
                DrawingPainter.paint(it.drawing, s.x + it.drawing.effL, top + it.drawing.effT, ops, ctx);
            }
        }
        flush(ops, run, runStart, runLook, base, line);
        if (line.hyphen && !line.slices.isEmpty()) {
            Line.Slice last = line.slices.get(line.slices.size() - 1);
            if (last.item.look != null) {
                ops.add(new Op.Text(last.x + last.w, base - last.item.look.rise(), "-", last.item.look.style()));
            }
        }
        decorations(line, ops);
        Inline.Link open = null;
        float linkX0 = 0;
        float linkX1 = 0;
        for (Line.Slice s : line.slices) {
            Inline.Link link = s.w > 0 ? s.item.link : open;
            if (link != open) {
                if (open != null && linkX1 > linkX0) {
                    ops.add(new Op.Link(linkX0, 0, linkX1 - linkX0, line.height, open.url(), open.anchor()));
                }
                open = link;
                linkX0 = s.x;
            }
            if (open != null && s.w > 0) {
                linkX1 = s.x + s.w;
            }
            if (s.item.note != null && notes != null && !notes.contains(s.item.note)) {
                notes.add(s.item.note);
            }
        }
        if (open != null && linkX1 > linkX0) {
            ops.add(new Op.Link(linkX0, 0, linkX1 - linkX0, line.height, open.url(), open.anchor()));
        }
        for (Item z : line.zero) {
            if (z.kind == Item.Kind.BOOKMARK) {
                ops.add(new Op.Dest(z.bookmark, line.left, 0));
            } else if (z.kind == Item.Kind.NOTE && notes != null && z.note != null && !notes.contains(z.note)) {
                notes.add(z.note);
            }
        }
    }

    private static void flush(List<Op> ops, StringBuilder run, Line.Slice start, Look look, float base, Line line) {
        if (start == null || run.length() == 0 || !drawable(run)) {
            return;
        }
        TextStyle style = look.style();
        if (start.wordExtra != 0) {
            style = style.wordSpacing(start.wordExtra);
        }
        if (start.charExtra != 0) {
            style = style.charSpacing(style.charSpacing() + start.charExtra);
        }
        if (start.wordExtra > WIDE_SPACE * style.size() && start.charExtra == 0) {
            wideSpaces(ops, run.toString(), start.x, base - look.rise(), style);
            return;
        }
        ops.add(new Op.Text(start.x, base - look.rise(), run.toString(), style));
    }

    static final float WIDE_SPACE = 0.3f;

    // Wide justified gaps are drawn as stretched spaces, so text extraction still sees one line of words
    private static void wideSpaces(List<Op> ops, String text, float x, float y, TextStyle style) {
        TextStyle plain = style.wordSpacing(0);
        float space = plain.width(" ");
        TextStyle wide = space > 0 ? plain.horizontalScale(plain.horizontalScale() * (space + style.wordSpacing())
                / space) : plain;
        int i = 0;
        while (i < text.length()) {
            int j = i;
            boolean blank = text.charAt(i) == ' ';
            while (j < text.length() && (text.charAt(j) == ' ') == blank) {
                j++;
            }
            String part = text.substring(i, j);
            ops.add(new Op.Text(x, y, part, blank ? wide : plain));
            x += blank ? (space + style.wordSpacing()) * part.length() : plain.width(part);
            i = j;
        }
    }

    private static boolean ideographic(List<Line.Slice> slices, int from, int to) {
        for (int i = from; i <= to && i < slices.size(); i++) {
            Line.Slice s = slices.get(i);
            if (s.item.kind != Item.Kind.TEXT) {
                continue;
            }
            for (int k = s.from; k < s.to; k++) {
                if (Breaks.cjk(s.item.text.charAt(k))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean drawable(CharSequence s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\u00AD' && !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT) {
                return true;
            }
        }
        return false;
    }

    private static void leader(List<Op> ops, Line.Slice s, float base) {
        Look look = s.item.look;
        char c = s.tab.leader();
        TextStyle style = look.style().charSpacing(0).wordSpacing(0);
        float cw = style.width(String.valueOf(c));
        if (cw <= 0.1f) {
            return;
        }
        float gap = c == '_' ? 0 : cw * 0.5f;
        float start = s.x + gap;
        float end = s.x + s.w - gap;
        int n = (int) Math.floor((end - start) / cw);
        if (n <= 0) {
            return;
        }
        if (c == '_') {
            float thick = Math.max(0.5f, look.size() * 0.05f);
            float y = base + Math.max(0.5f, style.points(-look.face().metrics().underlinePosition()));
            ops.add(new Op.Rect(s.x, y, s.w, thick, Fill.solid(style.color()), null));
            return;
        }
        float x = end - n * cw;
        ops.add(new Op.Text(x, base, String.valueOf(c).repeat(n), style));
    }

    private static void decorations(Line line, List<Op> ops) {
        List<Line.Slice> slices = line.slices;
        float contentEnd = line.end;
        int i = 0;
        while (i < slices.size()) {
            Line.Slice s = slices.get(i);
            Look look = s.item.look;
            if (look == null || look.underline() == null || s.item.kind == Item.Kind.BREAK || s.item.label) {
                i++;
                continue;
            }
            String u = look.underline();
            Color color = look.underlineColor() != null ? look.underlineColor() : look.style().color();
            float x0 = s.x;
            float x1 = s.x + s.w;
            Look thickest = look;
            int j = i + 1;
            while (j < slices.size()) {
                Line.Slice n = slices.get(j);
                Look nl = n.item.look;
                if (nl == null || !u.equals(nl.underline()) || n.item.kind == Item.Kind.BREAK) {
                    break;
                }
                x1 = n.x + n.w;
                if (nl.size() > thickest.size()) {
                    thickest = nl;
                }
                j++;
            }
            x1 = Math.min(x1, Math.max(x0, contentEnd));
            if (x1 > x0) {
                underline(ops, x0, x1, line.baseline, thickest, u, color);
            }
            i = j;
        }
        for (Line.Slice s : slices) {
            Look look = s.item.look;
            if (look == null || s.item.kind != Item.Kind.TEXT || s.w <= 0) {
                continue;
            }
            if (look.strike() || look.dstrike()) {
                FontFace face = look.face();
                FontMetrics m = face.metrics();
                float size = look.size();
                float pos = m.strikeoutPosition() > 0 ? m.strikeoutPosition() * size / m.unitsPerEm() : size * 0.3f;
                float th = Math.max(0.4f, m.strikeoutSize() * size / m.unitsPerEm());
                float y = line.baseline - look.rise() - pos;
                float w = Math.min(s.w, Math.max(0, contentEnd - s.x));
                if (look.dstrike()) {
                    ops.add(new Op.Rect(s.x, y - th * 1.5f, w, th, Fill.solid(look.style().color()), null));
                    ops.add(new Op.Rect(s.x, y + th * 0.5f, w, th, Fill.solid(look.style().color()), null));
                } else {
                    ops.add(new Op.Rect(s.x, y - th / 2, w, th, Fill.solid(look.style().color()), null));
                }
            }
        }
    }

    private static void underline(List<Op> ops, float x0, float x1, float base, Look look, String kind, Color color) {
        FontMetrics m = look.face().metrics();
        float size = look.nominalSize();
        float pos = -m.underlinePosition() * size / m.unitsPerEm();
        if (pos <= 0) {
            pos = size * 0.1f;
        }
        float th = Math.max(0.3f, m.underlineThickness() * size / m.unitsPerEm());
        float y = base + pos;
        Fill fill = Fill.solid(color);
        switch (kind) {
            case "double" -> {
                ops.add(new Op.Rect(x0, y - th / 2, x1 - x0, th, fill, null));
                ops.add(new Op.Rect(x0, y + th * 1.5f, x1 - x0, th, fill, null));
            }
            case "thick" -> ops.add(new Op.Rect(x0, y - th, x1 - x0, th * 2, fill, null));
            case "dotted", "dottedHeavy" -> ops.add(new Op.Line(x0, y, x1, y,
                    Stroke.solid(th, color).dash(0, th, th * 2)));
            case "dash", "dashedHeavy", "dashLong", "dashLongHeavy", "dotDash", "dotDotDash", "dashDotHeavy",
                    "dashDotDotHeavy" -> ops.add(new Op.Line(x0, y, x1, y, Stroke.solid(th, color).dash(0, th * 4,
                    th * 3)));
            default -> ops.add(new Op.Rect(x0, y - th / 2, x1 - x0, th, fill, null));
        }
    }
}
