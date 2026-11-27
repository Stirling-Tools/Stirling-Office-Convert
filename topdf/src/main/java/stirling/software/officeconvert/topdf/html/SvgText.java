package stirling.software.officeconvert.topdf.html;

import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.GlyphRun;
import stirling.software.officeconvert.topdf.pdf.TextStyle;

final class SvgText {

    static final int MAX_CHARS = 200_000;

    private final SvgDraw draw;
    private final Map<String, FontFace> faces = new HashMap<>();

    SvgText(SvgDraw draw) {
        this.draw = draw;
    }

    private static final class Ch {
        int cp;
        SvgStyle style;
        Element owner;
        FontFace face;
        float size;
        float x;
        float y;
        float rotate;
        float advance;
        float shift;
        boolean hidden;
        boolean onPath;
        boolean skipped;
        float pathAngle;
        Element path;
        int anchorIndex;
        float scaleX = 1;
        GlyphRun shaped;
        boolean inUnit;
    }

    private record Span(Element element, SvgStyle style, int start, int end) {}

    private static final class Layout {
        final List<Ch> chars = new ArrayList<>();
        final List<Span> spans = new ArrayList<>();
    }

    private Layout layout(Element text, SvgStyle style) {
        Layout l = new Layout();
        collect(text, style, l, false, new int[] {0}, null);
        draw.charge(l.chars.size() * 8L);
        if (draw.spent()) {
            l.chars.clear();
            return l;
        }
        whitespace(l, text);
        if (l.chars.isEmpty()) {
            return l;
        }
        int n = l.chars.size();
        float[] xs = filled(n);
        float[] ys = filled(n);
        float[] dxs = new float[n];
        float[] dys = new float[n];
        float[] rot = filled(n);
        for (Span sp : l.spans) {
            int start = firstKept(l, sp.start(), sp.end());
            if (start < 0) {
                continue;
            }
            Element e = sp.element();
            SvgStyle s = sp.style();
            assign(xs, lengths(SvgDoc.attr(e, "x"), s, draw.vw()), l, start, sp.end(), false);
            assign(ys, lengths(SvgDoc.attr(e, "y"), s, draw.vh()), l, start, sp.end(), false);
            assign(dxs, lengths(SvgDoc.attr(e, "dx"), s, draw.vw()), l, start, sp.end(), false);
            assign(dys, lengths(SvgDoc.attr(e, "dy"), s, draw.vh()), l, start, sp.end(), false);
            assign(rot, SvgDraw.numbers(SvgDoc.attr(e, "rotate")), l, start, sp.end(), true);
        }
        fonts(l);
        if (vertical(style)) {
            vertical(l, xs, ys, dxs, dys, rot);
        } else {
            position(l, xs, ys, dxs, dys, rot, text, style);
        }
        return l;
    }

    private static int firstKept(Layout l, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!l.chars.get(i).skipped) {
                return i;
            }
        }
        return -1;
    }

    private static float[] filled(int n) {
        float[] a = new float[n];
        Arrays.fill(a, Float.NaN);
        return a;
    }

    private static void assign(float[] into, float[] values, Layout l, int start, int end, boolean repeatLast) {
        if (values.length == 0) {
            return;
        }
        int k = 0;
        for (int i = start; i < end; i++) {
            Ch c = l.chars.get(i);
            if (c.skipped) {
                continue;
            }
            if (k < values.length) {
                into[i] = values[k];
            } else if (repeatLast) {
                into[i] = values[values.length - 1];
            } else {
                break;
            }
            k++;
        }
    }

    private float[] lengths(String v, SvgStyle s, float percent) {
        if (v == null || v.isBlank()) {
            return new float[0];
        }
        String[] parts = v.strip().split("[\\s,]+");
        float[] out = new float[Math.min(parts.length, MAX_CHARS)];
        int n = 0;
        for (int i = 0; i < out.length; i++) {
            float f = SvgDraw.length(parts[i], s.fontSize, percent, Float.NaN);
            if (Float.isNaN(f)) {
                return Arrays.copyOf(out, n);
            }
            out[n++] = f;
        }
        return Arrays.copyOf(out, n);
    }

    private void collect(Element e, SvgStyle style, Layout l, boolean preserve, int[] depth, Element path) {
        if (depth[0]++ > 64 || l.chars.size() >= MAX_CHARS) {
            return;
        }
        String space = SvgDoc.attr(e, "xml:space");
        boolean keep = space.isEmpty() ? preserve : space.equalsIgnoreCase("preserve");
        String ws = SvgDoc.lower(style.get("white-space"));
        if (ws.equals("pre") || ws.equals("pre-wrap") || ws.equals("break-spaces")) {
            keep = true;
        }
        int start = l.chars.size();
        int slot = l.spans.size();
        l.spans.add(null);
        for (Node n : e.childNodes()) {
            if (n instanceof TextNode t) {
                String s = t.getWholeText();
                for (int i = 0; i < s.length() && l.chars.size() < MAX_CHARS; ) {
                    int cp = s.codePointAt(i);
                    i += Character.charCount(cp);
                    Ch c = new Ch();
                    c.cp = cp;
                    c.style = style;
                    c.owner = keep ? null : e;
                    c.hidden = !style.visible();
                    c.path = path;
                    l.chars.add(c);
                }
            } else if (n instanceof Element c) {
                String tag = SvgDoc.local(c);
                if (!SvgDraw.conditionsPass(c)) {
                    continue;
                }
                SvgStyle cs = style.child(draw.doc.specified(c));
                if (cs.own("display", "inline").equalsIgnoreCase("none")) {
                    continue;
                }
                switch (tag) {
                    case "tspan", "a" -> collect(c, cs, l, keep, depth, path);
                    case "textpath" -> {
                        if (path == null) {
                            collect(c, cs, l, keep, depth, c);
                        }
                    }
                    case "tref" -> {
                        Element ref = draw.doc.target(c);
                        if (ref != null && l.chars.size() < MAX_CHARS && !draw.spent()) {
                            String s = ref.text();
                            draw.charge(s.length());
                            if (s.length() > MAX_CHARS) {
                                s = s.substring(0, MAX_CHARS);
                            }
                            int st = l.chars.size();
                            for (int i = 0; i < s.length() && l.chars.size() < MAX_CHARS; ) {
                                int cp = s.codePointAt(i);
                                i += Character.charCount(cp);
                                Ch ch = new Ch();
                                ch.cp = cp;
                                ch.style = cs;
                                ch.owner = keep ? null : c;
                                ch.hidden = !cs.visible();
                                ch.path = path;
                                l.chars.add(ch);
                            }
                            l.spans.add(new Span(c, cs, st, l.chars.size()));
                        }
                    }
                    default -> { }
                }
            }
        }
        l.spans.set(slot, new Span(e, style, start, l.chars.size()));
    }

    private static void whitespace(Layout l, Element text) {
        boolean lastSpace = true;
        for (Ch c : l.chars) {
            if (c.owner == null) {
                if (c.cp == '\n' || c.cp == '\r' || c.cp == '\t') {
                    c.cp = ' ';
                }
                lastSpace = false;
                continue;
            }
            if (c.cp == '\n' || c.cp == '\r') {
                c.skipped = true;
                continue;
            }
            if (c.cp == '\t') {
                c.cp = ' ';
            }
            if (c.cp == ' ') {
                if (lastSpace) {
                    c.skipped = true;
                    continue;
                }
                lastSpace = true;
            } else {
                lastSpace = false;
            }
        }
        for (int i = l.chars.size() - 1; i >= 0; i--) {
            Ch c = l.chars.get(i);
            if (c.skipped) {
                continue;
            }
            if (c.owner != null && c.cp == ' ') {
                c.skipped = true;
                continue;
            }
            break;
        }
    }

    private static int weight(String v) {
        return switch (v) {
            case "bold", "bolder" -> 700;
            case "lighter" -> 300;
            case "", "normal" -> 400;
            default -> {
                float f = SvgDraw.number(v, 400);
                yield f >= 1 && f <= 1000 ? Math.round(f / 100f) * 100 : 400;
            }
        };
    }

    private static List<String> weightNames(int w) {
        return switch (w) {
            case 100 -> List.of("Thin", "Hairline");
            case 200 -> List.of("ExtraLight", "Extra Light", "UltraLight");
            case 300 -> List.of("Light");
            case 500 -> List.of("Medium");
            case 600 -> List.of("SemiBold", "Semi Bold", "DemiBold");
            case 800 -> List.of("ExtraBold", "Extra Bold", "UltraBold");
            case 900 -> List.of("Black", "Heavy");
            default -> List.of();
        };
    }

    FontFace face(SvgStyle s) {
        String family = s.get("font-family");
        String weight = SvgDoc.lower(s.get("font-weight"));
        int numeric = weight(weight);
        boolean bold = numeric >= 600;
        String style = SvgDoc.lower(s.get("font-style"));
        boolean italic = style.startsWith("italic") || style.startsWith("oblique");
        String key = family + "|" + numeric + "|" + italic;
        FontFace known = faces.get(key);
        if (known != null) {
            return known;
        }
        FontFace found = null;
        String first = null;
        for (String raw : Css.splitTopLevel(family, ',')) {
            String f = raw.strip().replaceAll("^[\"']|[\"']$", "").strip();
            if (f.isEmpty()) {
                continue;
            }
            if (first == null) {
                first = f;
            }
            for (String candidate : candidates(f)) {
                for (String w : weightNames(numeric)) {
                    FontFace face = draw.fonts.exact(candidate + " " + w, false, italic);
                    if (face != null) {
                        found = face;
                        break;
                    }
                }
                if (found != null) {
                    break;
                }
                FontFace face = draw.fonts.exact(candidate, bold, italic);
                if (face != null) {
                    found = face;
                    break;
                }
            }
            if (found != null) {
                break;
            }
        }
        if (found == null) {
            for (String candidate : candidates("serif")) {
                FontFace face = draw.fonts.exact(candidate, bold, italic);
                if (face != null) {
                    found = face;
                    break;
                }
            }
        }
        if (found == null) {
            found = draw.fonts.find(first == null ? "Times New Roman" : first, bold, italic);
        }
        faces.put(key, found);
        return found;
    }

    private static List<String> candidates(String family) {
        String l = family.toLowerCase(Locale.ROOT);
        return switch (l) {
            case "serif" -> List.of("Noto Serif", "Times New Roman", "DejaVu Serif", "Liberation Serif", "Times");
            case "sans-serif" -> List.of("Noto Sans", "Arial", "Helvetica", "DejaVu Sans", "Liberation Sans");
            case "monospace" -> List.of("Noto Mono", "Noto Sans Mono", "Courier New", "DejaVu Sans Mono",
                    "Liberation Mono");
            case "cursive" -> List.of("Yellowtail", "Comic Sans MS", "URW Chancery L");
            case "fantasy" -> List.of("Sedgwick Ave Display", "Impact", "Papyrus");
            default -> List.of(family);
        };
    }

    private void fonts(Layout l) {
        for (Ch c : l.chars) {
            if (c.skipped) {
                continue;
            }
            FontFace face = face(c.style);
            if (!face.covers(c.cp) && !Character.isWhitespace(c.cp) && !Character.isISOControl(c.cp)) {
                FontFace f = draw.fonts.fallback(c.cp, face);
                if (f != null) {
                    face = f;
                }
            }
            c.face = face;
            c.size = c.style.fontSize;
        }
    }

    private float spacing(SvgStyle s, String name) {
        String v = s.get(name).strip();
        if (v.isEmpty() || v.equalsIgnoreCase("normal")) {
            return 0;
        }
        return SvgDraw.length(v, s.fontSize, s.fontSize, 0);
    }

    private static float baseline(Ch c, String mode) {
        FontFace f = c.face;
        var m = f.metrics();
        float upem = f.unitsPerEm();
        float asc = (m.useTypoMetrics() ? m.typoAscender() : m.hheaAscender()) / upem * c.size;
        float desc = (m.useTypoMetrics() ? m.typoDescender() : m.hheaDescender()) / upem * c.size;
        float xh = (m.xHeight() > 0 ? m.xHeight() / upem : 0.5f) * c.size;
        return switch (mode) {
            case "middle" -> xh / 2;
            case "central" -> (asc + desc) / 2;
            case "hanging" -> asc * 0.8f;
            case "text-before-edge", "text-top", "before-edge" -> asc;
            case "text-after-edge", "text-bottom", "after-edge", "ideographic" -> desc;
            case "mathematical" -> xh / 2 + 0.05f * c.size;
            default -> 0;
        };
    }

    private void position(Layout l, float[] xs, float[] ys, float[] dxs, float[] dys, float[] rot, Element text,
            SvgStyle style) {
        float px = 0;
        float py = 0;
        int chunk = -1;
        int prev = -1;
        List<int[]> chunks = new ArrayList<>();
        for (int i = 0; i < l.chars.size(); i++) {
            Ch c = l.chars.get(i);
            if (c.skipped) {
                continue;
            }
            boolean starts = chunk < 0;
            if (!Float.isNaN(xs[i])) {
                px = xs[i];
                starts = true;
            }
            if (!Float.isNaN(ys[i]) && c.path == null) {
                py = ys[i];
                starts = true;
            }
            Element before = prev >= 0 ? l.chars.get(prev).path : null;
            if (prev >= 0 ? c.path != before : c.path != null) {
                starts = true;
                if (c.path != null) {
                    if (flat(c.path) == null) {
                        c.skipped = true;
                        continue;
                    }
                    if (Float.isNaN(xs[i])) {
                        px = 0;
                    }
                    py = 0;
                } else if (before != null) {
                    float[] end = pathEnd(before, px);
                    if (end != null) {
                        if (Float.isNaN(xs[i])) {
                            px = end[0];
                        }
                        if (Float.isNaN(ys[i])) {
                            py = end[1];
                        }
                    }
                }
            }
            if (starts) {
                if (chunk >= 0) {
                    chunks.add(new int[] {chunk, prev});
                }
                chunk = i;
                int end = chunkEnd(l, i, xs, ys);
                if (c.path == null && shapeable(l, i, end, dxs, dys, rot)) {
                    px += dxs[i];
                    py += dys[i];
                    px = shapeChunk(l, i, end, px, py, Float.isNaN(rot[i]) ? 0 : rot[i]);
                    for (int k = i; k < end; k++) {
                        if (!l.chars.get(k).skipped) {
                            prev = k;
                        }
                    }
                    i = end - 1;
                    continue;
                }
            }
            px += dxs[i];
            py += dys[i];
            c.x = px;
            c.y = py;
            c.rotate = Float.isNaN(rot[i]) ? 0 : rot[i];
            float adv = c.face.advance(c.cp, c.size);
            if (Character.isISOControl(c.cp)) {
                adv = 0;
            }
            if (prev >= 0 && l.chars.get(prev).face == c.face && !SvgDoc.lower(c.style.get("font-kerning")).equals("none")
                    && !SvgDoc.lower(c.style.get("kerning")).equals("0")) {
                int k = c.face.pairKerning(l.chars.get(prev).cp, c.cp);
                if (k != 0) {
                    float kx = k * c.size / c.face.unitsPerEm();
                    px += kx;
                    c.x += kx;
                }
            }
            float ls = spacing(c.style, "letter-spacing");
            float ws = c.cp == ' ' ? spacing(c.style, "word-spacing") : 0;
            c.advance = adv;
            px += adv + ls + ws;
            prev = i;
        }
        if (chunk >= 0) {
            chunks.add(new int[] {chunk, prev});
        }
        textLength(l, text, style);
        for (int[] ch : chunks) {
            anchor(l, ch[0], ch[1]);
        }
        for (int i = 0; i < l.chars.size(); i++) {
            Ch c = l.chars.get(i);
            if (c.skipped) {
                continue;
            }
            float shift = 0;
            for (Span sp : l.spans) {
                if (i >= sp.start() && i < sp.end() && sp.element() != text) {
                    shift += shiftOf(sp.style());
                }
            }
            String db = SvgDoc.lower(c.style.own("alignment-baseline", ""));
            if (db.isEmpty() || db.equals("auto") || db.equals("baseline")) {
                db = SvgDoc.lower(c.style.get("dominant-baseline"));
            }
            c.shift = shift + baseline(c, db);
        }
        paths(l);
    }

    static boolean vertical(SvgStyle s) {
        String m = SvgDoc.lower(s.get("writing-mode"));
        return m.equals("tb") || m.equals("tb-rl") || m.equals("vertical-rl") || m.equals("vertical-lr");
    }

    private static boolean upright(int cp) {
        Character.UnicodeScript s = Character.UnicodeScript.of(cp);
        return switch (s) {
            case HAN, HIRAGANA, KATAKANA, HANGUL, BOPOMOFO, YI -> true;
            default -> cp >= 0x3000 && cp <= 0x303F || cp >= 0xFF00 && cp <= 0xFFEF;
        };
    }

    private void vertical(Layout l, float[] xs, float[] ys, float[] dxs, float[] dys, float[] rot) {
        float column = 0;
        float pen = 0;
        int chunk = -1;
        List<int[]> chunks = new ArrayList<>();
        int prev = -1;
        for (int i = 0; i < l.chars.size(); i++) {
            Ch c = l.chars.get(i);
            if (c.skipped) {
                continue;
            }
            if (c.path != null) {
                c.skipped = true;
                continue;
            }
            boolean starts = chunk < 0;
            if (!Float.isNaN(xs[i])) {
                column = xs[i];
                starts = true;
            }
            if (!Float.isNaN(ys[i])) {
                pen = ys[i];
                starts = true;
            }
            if (starts) {
                if (chunk >= 0) {
                    chunks.add(new int[] {chunk, prev});
                }
                chunk = i;
            }
            column += dxs[i];
            pen += dys[i];
            var m = c.face.metrics();
            float upem = c.face.unitsPerEm();
            float asc = (m.useTypoMetrics() ? m.typoAscender() : m.hheaAscender()) / upem * c.size;
            float desc = (m.useTypoMetrics() ? m.typoDescender() : m.hheaDescender()) / upem * c.size;
            float h = Character.isISOControl(c.cp) ? 0 : c.face.advance(c.cp, c.size);
            float extra = Float.isNaN(rot[i]) ? 0 : rot[i];
            if (upright(c.cp)) {
                c.x = column - h / 2;
                c.y = pen + (asc + desc) / 2 + c.size / 2;
                c.rotate = extra;
                c.advance = h;
                pen += c.size;
            } else {
                if (prev >= 0 && l.chars.get(prev).face == c.face && !upright(l.chars.get(prev).cp)
                        && !SvgDoc.lower(c.style.get("font-kerning")).equals("none")) {
                    pen += c.face.pairKerning(l.chars.get(prev).cp, c.cp) * c.size / c.face.unitsPerEm();
                }
                float b = (asc + desc) / 2;
                c.x = column - b;
                c.y = pen;
                c.rotate = 90 + extra;
                c.advance = h;
                pen += h;
            }
            c.anchorIndex = Math.round(pen * 1000);
            pen += spacing(c.style, "letter-spacing") + (c.cp == ' ' ? spacing(c.style, "word-spacing") : 0);
            prev = i;
        }
        if (chunk >= 0) {
            chunks.add(new int[] {chunk, prev});
        }
        for (int[] ch : chunks) {
            Ch first = l.chars.get(ch[0]);
            String a = SvgDoc.lower(first.style.get("text-anchor"));
            if (!a.equals("middle") && !a.equals("end")) {
                continue;
            }
            float start = Float.NaN;
            float end = Float.NaN;
            for (int k = ch[0]; k <= ch[1]; k++) {
                Ch c = l.chars.get(k);
                if (c.skipped) {
                    continue;
                }
                float top = upright(c.cp) ? c.y - c.size : c.y;
                float bottom = c.anchorIndex / 1000f;
                start = Float.isNaN(start) ? top : Math.min(start, top);
                end = Float.isNaN(end) ? bottom : Math.max(end, bottom);
            }
            if (Float.isNaN(start)) {
                continue;
            }
            float shift = a.equals("middle") ? -(end - start) / 2 : -(end - start);
            for (int k = ch[0]; k <= ch[1]; k++) {
                l.chars.get(k).y += shift;
            }
        }
    }

    private static int chunkEnd(Layout l, int from, float[] xs, float[] ys) {
        Element path = l.chars.get(from).path;
        for (int k = from + 1; k < l.chars.size(); k++) {
            Ch c = l.chars.get(k);
            if (c.skipped) {
                continue;
            }
            if (!Float.isNaN(xs[k]) || !Float.isNaN(ys[k]) || c.path != path) {
                return k;
            }
        }
        return l.chars.size();
    }

    private static boolean complex(int cp) {
        Character.UnicodeScript s = Character.UnicodeScript.of(cp);
        return switch (s) {
            case ARABIC, HEBREW, SYRIAC, THAANA, NKO, DEVANAGARI, BENGALI, GURMUKHI, GUJARATI, ORIYA, TAMIL, TELUGU,
                    KANNADA, MALAYALAM, SINHALA, THAI, LAO, TIBETAN, MYANMAR, KHMER -> true;
            default -> false;
        };
    }

    private static boolean shapeable(Layout l, int from, int to, float[] dxs, float[] dys, float[] rot) {
        boolean any = false;
        for (int k = from; k < to; k++) {
            Ch c = l.chars.get(k);
            if (c.skipped) {
                continue;
            }
            if (k > from && (dxs[k] != 0 || dys[k] != 0 || !Float.isNaN(rot[k]) && rot[k] != rot[from])) {
                return false;
            }
            if (complex(c.cp)) {
                any = true;
            }
        }
        return any;
    }

    private float shapeChunk(Layout l, int from, int to, float px, float py, float rotate) {
        List<Integer> idx = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        List<Integer> offsets = new ArrayList<>();
        for (int k = from; k < to; k++) {
            Ch c = l.chars.get(k);
            if (!c.skipped) {
                idx.add(k);
                offsets.add(sb.length());
                sb.appendCodePoint(c.cp);
            }
        }
        offsets.add(sb.length());
        Ch head = l.chars.get(idx.get(0));
        boolean rtlBase = SvgDoc.lower(head.style.get("direction")).equals("rtl");
        java.text.Bidi bidi = new java.text.Bidi(sb.toString(), rtlBase ? java.text.Bidi.DIRECTION_RIGHT_TO_LEFT
                : java.text.Bidi.DIRECTION_LEFT_TO_RIGHT);
        int runs = bidi.getRunCount();
        byte[] levels = new byte[runs];
        Integer[] order = new Integer[runs];
        for (int r = 0; r < runs; r++) {
            levels[r] = (byte) bidi.getRunLevel(r);
            order[r] = r;
        }
        java.text.Bidi.reorderVisually(levels, 0, order, 0, runs);
        float pen = px;
        for (Integer r : order) {
            int start = bidi.getRunStart(r);
            int limit = bidi.getRunLimit(r);
            boolean rtl = (bidi.getRunLevel(r) & 1) == 1;
            List<int[]> segments = new ArrayList<>();
            int segStart = -1;
            for (int k = 0; k < idx.size(); k++) {
                int off = offsets.get(k);
                if (off < start || off >= limit) {
                    continue;
                }
                Ch c = l.chars.get(idx.get(k));
                if (segStart >= 0) {
                    Ch s0 = l.chars.get(idx.get(segStart));
                    if (s0.face != c.face || s0.style != c.style) {
                        segments.add(new int[] {segStart, k});
                        segStart = k;
                    }
                } else {
                    segStart = k;
                }
            }
            if (segStart >= 0) {
                int last = segStart;
                for (int k = segStart; k < idx.size() && offsets.get(k) < limit; k++) {
                    last = k;
                }
                segments.add(new int[] {segStart, last + 1});
            }
            if (rtl) {
                java.util.Collections.reverse(segments);
            }
            for (int[] seg : segments) {
                Ch first = l.chars.get(idx.get(seg[0]));
                StringBuilder t = new StringBuilder();
                boolean hard = rtl;
                for (int k = seg[0]; k < seg[1]; k++) {
                    int cp = l.chars.get(idx.get(k)).cp;
                    t.appendCodePoint(cp);
                    hard |= complex(cp);
                }
                if (hard && first.face.shapeable()) {
                    GlyphRun g = first.face.shape(t.toString(), rtl);
                    float w = g.width(first.size);
                    for (int k = seg[0]; k < seg[1]; k++) {
                        Ch c = l.chars.get(idx.get(k));
                        c.x = pen;
                        c.y = py;
                        c.rotate = rotate;
                        c.advance = 0;
                        c.inUnit = k != seg[0];
                    }
                    first.shaped = g;
                    first.advance = w;
                    pen += w;
                    continue;
                }
                for (int k = seg[0]; k < seg[1]; k++) {
                    Ch c = l.chars.get(idx.get(rtl ? seg[1] - 1 - (k - seg[0]) : k));
                    c.x = pen;
                    c.y = py;
                    c.rotate = rotate;
                    c.advance = Character.isISOControl(c.cp) ? 0 : c.face.advance(c.cp, c.size);
                    pen += c.advance + spacing(c.style, "letter-spacing")
                            + (c.cp == ' ' ? spacing(c.style, "word-spacing") : 0);
                }
            }
        }
        return pen;
    }

    private static float shiftOf(SvgStyle s) {
        String v = SvgDoc.lower(s.own("baseline-shift", "0"));
        float fs = s.fontSize;
        return switch (v) {
            case "sub" -> 0.2f * fs;
            case "super" -> -0.4f * fs;
            case "baseline", "0", "" -> 0;
            default -> -SvgDraw.length(v, fs, fs, 0);
        };
    }

    private void textLength(Layout l, Element text, SvgStyle style) {
        float want = SvgDraw.length(SvgDoc.attr(text, "textLength"), style.fontSize, draw.vw(), Float.NaN);
        if (!(want > 0)) {
            return;
        }
        int first = -1;
        int last = -1;
        int count = 0;
        for (int i = 0; i < l.chars.size(); i++) {
            if (!l.chars.get(i).skipped) {
                if (first < 0) {
                    first = i;
                }
                last = i;
                count++;
            }
        }
        if (first < 0) {
            return;
        }
        Ch a = l.chars.get(first);
        Ch b = l.chars.get(last);
        float have = b.x + b.advance - a.x;
        if (!(have > 0)) {
            return;
        }
        boolean glyphs = SvgDoc.lower(SvgDoc.attr(text, "lengthAdjust")).equals("spacingandglyphs");
        if (glyphs) {
            float k = want / have;
            for (int i = first; i <= last; i++) {
                Ch c = l.chars.get(i);
                if (!c.skipped) {
                    c.x = a.x + (c.x - a.x) * k;
                    c.scaleX = k;
                    c.advance *= k;
                }
            }
        } else if (count > 1) {
            float extra = (want - have) / (count - 1);
            int k = 0;
            for (int i = first; i <= last; i++) {
                Ch c = l.chars.get(i);
                if (!c.skipped) {
                    c.x += extra * k++;
                }
            }
        }
    }

    private void anchor(Layout l, int from, int to) {
        Ch first = l.chars.get(from);
        String a = SvgDoc.lower(first.style.get("text-anchor"));
        if (SvgDoc.lower(first.style.get("direction")).equals("rtl")) {
            a = a.equals("end") ? "start" : a.equals("start") || a.isEmpty() ? "end" : a;
        }
        if (!a.equals("middle") && !a.equals("end")) {
            return;
        }
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = from; i <= to; i++) {
            Ch c = l.chars.get(i);
            if (c.skipped) {
                continue;
            }
            min = Math.min(min, c.x);
            max = Math.max(max, c.x + c.advance);
        }
        if (min > max) {
            return;
        }
        float w = max - min;
        float shift = a.equals("middle") ? -w / 2 : -w;
        float origin = first.x;
        shift += origin - min;
        for (int i = from; i <= to; i++) {
            l.chars.get(i).x += shift;
        }
    }

    private record Flat(float[] xs, float[] ys, float[] lens, float total) {}

    private final Map<Element, Flat> flats = new HashMap<>();

    private Flat flat(Element textPath) {
        if (flats.containsKey(textPath)) {
            return flats.get(textPath);
        }
        Shape shape = null;
        String d = SvgDoc.attr(textPath, "path");
        draw.charge(d.length());
        if (d.length() > SvgDraw.MAX_PATH_CHARS) {
            flats.put(textPath, null);
            return null;
        }
        if (!d.isBlank()) {
            shape = SvgPath.parse(d);
        } else {
            Element ref = draw.doc.target(textPath);
            if (ref != null) {
                String tag = SvgDoc.local(ref);
                SvgStyle rs = draw.domStyle(ref);
                shape = draw.geometry(ref, rs, tag);
                AffineTransform t = SvgDraw.transform(rs, ref);
                if (shape != null && t != null) {
                    shape = t.createTransformedShape(shape);
                }
            }
        }
        if (shape == null) {
            flats.put(textPath, null);
            return null;
        }
        List<float[]> pts = new ArrayList<>();
        Rectangle2D sb = shape.getBounds2D();
        double tolerance = Math.max(1e-6, Math.max(sb.getWidth(), sb.getHeight()) / 2000);
        PathIterator it = shape.getPathIterator(null, tolerance);
        float[] c = new float[6];
        boolean started = false;
        while (!it.isDone() && pts.size() < 200_000) {
            int type = it.currentSegment(c);
            if (type == PathIterator.SEG_MOVETO) {
                if (started) {
                    pts.add(new float[] {c[0], c[1], 1});
                } else {
                    pts.add(new float[] {c[0], c[1]});
                }
                started = true;
            } else if (type == PathIterator.SEG_LINETO) {
                pts.add(new float[] {c[0], c[1]});
            } else if (type == PathIterator.SEG_CLOSE && !pts.isEmpty()) {
                pts.add(pts.get(0).clone());
            }
            it.next();
        }
        if (pts.size() < 2) {
            flats.put(textPath, null);
            return null;
        }
        float[] xs = new float[pts.size()];
        float[] ys = new float[pts.size()];
        float[] lens = new float[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            xs[i] = pts.get(i)[0];
            ys[i] = pts.get(i)[1];
            if (i > 0) {
                boolean jump = pts.get(i).length > 2;
                lens[i] = lens[i - 1] + (jump ? 1e-4f : (float) Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]));
            }
        }
        Flat f = new Flat(xs, ys, lens, lens[lens.length - 1]);
        flats.put(textPath, f);
        return f;
    }

    private float[] pathEnd(Element textPath, float distance) {
        Flat f = flat(textPath);
        if (f == null) {
            return null;
        }
        float d = Math.max(0, Math.min(f.total(), offset(textPath, f) + distance));
        int k = Arrays.binarySearch(f.lens(), d);
        if (k < 0) {
            k = -k - 1;
        }
        k = Math.max(1, Math.min(f.lens().length - 1, k));
        float seg = f.lens()[k] - f.lens()[k - 1];
        float t = seg > 0 ? (d - f.lens()[k - 1]) / seg : 0;
        return new float[] {f.xs()[k - 1] + (f.xs()[k] - f.xs()[k - 1]) * t,
            f.ys()[k - 1] + (f.ys()[k] - f.ys()[k - 1]) * t};
    }

    private float offset(Element textPath, Flat f) {
        SvgStyle ps = draw.domStyle(textPath);
        String so = SvgDoc.attr(textPath, "startOffset").strip();
        if (so.endsWith("%")) {
            return SvgDraw.number(so.substring(0, so.length() - 1), 0) / 100f * f.total();
        }
        float v = SvgDraw.length(so, ps.fontSize, f.total(), 0);
        Element ref = SvgDoc.attr(textPath, "path").isBlank() ? draw.doc.target(textPath) : null;
        float declared = ref == null ? Float.NaN : SvgDraw.number(SvgDoc.attr(ref, "pathLength"), Float.NaN);
        return declared > 0 ? v * f.total() / declared : v;
    }

    private void paths(Layout l) {
        for (int i = 0; i < l.chars.size(); i++) {
            Ch c = l.chars.get(i);
            if (c.skipped || c.path == null) {
                continue;
            }
            Flat f = flat(c.path);
            if (f == null) {
                c.skipped = true;
                continue;
            }
            float mid = offset(c.path, f) + c.x + c.advance / 2;
            if (mid < 0 || mid > f.total()) {
                c.skipped = true;
                continue;
            }
            int k = Arrays.binarySearch(f.lens(), mid);
            if (k < 0) {
                k = -k - 1;
            }
            k = Math.max(1, Math.min(f.lens().length - 1, k));
            float seg = f.lens()[k] - f.lens()[k - 1];
            float t = seg > 0 ? (mid - f.lens()[k - 1]) / seg : 0;
            float x0 = f.xs()[k - 1];
            float y0 = f.ys()[k - 1];
            float x1 = f.xs()[k];
            float y1 = f.ys()[k];
            float px = x0 + (x1 - x0) * t;
            float py = y0 + (y1 - y0) * t;
            float angle = (float) Math.atan2(y1 - y0, x1 - x0);
            c.onPath = true;
            c.pathAngle = angle;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            float half = c.advance / 2;
            float normal = c.y + c.shift;
            c.shift = 0;
            c.x = (float) (px - cos * half - sin * normal);
            c.y = (float) (py - sin * half + cos * normal);
        }
    }


    void draw(Element text, SvgStyle style, float alpha) throws IOException {
        Layout l = layout(text, style);
        if (l.chars.isEmpty()) {
            return;
        }
        Rectangle2D bbox = bounds(l);
        List<Integer> runStarts = new ArrayList<>();
        int i = 0;
        int n = l.chars.size();
        while (i < n) {
            Ch c = l.chars.get(i);
            if (c.skipped || c.inUnit) {
                i++;
                continue;
            }
            if (c.shaped != null) {
                shapedRun(c, bbox, alpha);
                i++;
                continue;
            }
            int j = i + 1;
            float end = c.x + c.advance + extra(c);
            while (j < n) {
                Ch d = l.chars.get(j);
                if (d.skipped) {
                    j++;
                    continue;
                }
                if (d.inUnit || d.shaped != null || d.face != c.face || d.style != c.style || d.size != c.size || d.rotate != 0 || c.rotate != 0
                        || d.onPath || c.onPath || Math.abs(d.x - end) > 0.01f || d.y != c.y || d.shift != c.shift
                        || d.scaleX != c.scaleX || d.hidden != c.hidden) {
                    break;
                }
                end = d.x + d.advance + extra(d);
                j++;
            }
            run(l, i, j, bbox, alpha);
            i = j;
        }
        decorations(l, text, style, bbox, alpha);
    }

    private float extra(Ch c) {
        return spacing(c.style, "letter-spacing") + (c.cp == ' ' ? spacing(c.style, "word-spacing") : 0);
    }

    private void run(Layout l, int from, int to, Rectangle2D bbox, float alpha) throws IOException {
        Ch c = l.chars.get(from);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            Ch d = l.chars.get(i);
            if (!d.skipped) {
                sb.appendCodePoint(d.cp);
            }
        }
        String s = sb.toString();
        if (s.isBlank() && !s.contains(" ")) {
            return;
        }
        SvgStyle st = c.style;
        SvgDraw.Paint fill = draw.paint(st.get("fill"), st);
        SvgDraw.Paint stroke = draw.paint(st.get("stroke"), st);
        float sw = draw.strokeWidth(st);
        boolean visible = !c.hidden;
        float fo = alpha * Css.opacity(st.get("fill-opacity"));
        float so = alpha * Css.opacity(st.get("stroke-opacity"));
        boolean plain = fill.server() == null && (stroke.none() || sw <= 0);
        var canvas = draw.canvas;
        canvas.save();
        try {
            canvas.translate(c.x, c.y + c.shift);
            if (c.onPath) {
                canvas.transform(AffineTransform.getRotateInstance(c.pathAngle));
            }
            if (c.rotate != 0) {
                canvas.transform(AffineTransform.getRotateInstance(Math.toRadians(c.rotate)));
            }
            if (c.scaleX != 1) {
                canvas.scale(c.scaleX, 1);
            }
            float ls = spacing(st, "letter-spacing");
            float ws = spacing(st, "word-spacing");
            TextStyle ts = new TextStyle(c.face, Math.max(0.01f, Math.min(10_000, c.size)), Color.BLACK, ls, 100, ws,
                    false);
            if (plain && visible && !fill.none()) {
                float a = fo * fill.alpha();
                if (a <= 0) {
                    return;
                }
                if (a < 1) {
                    PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                    gs.setNonStrokingAlphaConstant(a);
                    canvas.stream().setGraphicsStateParameters(gs);
                }
                canvas.text(s, 0, 0, ts.color(new Color(fill.rgb())));
                return;
            }
            boolean hide = canvas.hideText(true);
            canvas.text(s, 0, 0, ts);
            canvas.hideText(hide);
            if (!visible) {
                return;
            }
            Path2D outline = outline(l, from, to, c);
            if (outline == null) {
                return;
            }
            AffineTransform local = new AffineTransform();
            Rectangle2D box = bbox;
            try {
                AffineTransform inv = AffineTransform.getTranslateInstance(c.x, c.y + c.shift);
                if (c.onPath) {
                    inv.rotate(c.pathAngle);
                }
                if (c.rotate != 0) {
                    inv.rotate(Math.toRadians(c.rotate));
                }
                if (c.scaleX != 1) {
                    inv.scale(c.scaleX, 1);
                }
                local = inv.createInverse();
                box = local.createTransformedShape(bbox).getBounds2D();
            } catch (java.awt.geom.NoninvertibleTransformException e) {
                return;
            }
            for (String step : SvgDraw.paintOrder(st.get("paint-order"))) {
                if (step.equals("fill") && !fill.none()) {
                    draw.fill(outline, fill, fo, box, st);
                } else if (step.equals("stroke") && !stroke.none() && sw > 0) {
                    draw.stroke(outline, stroke, so, box, st, sw);
                }
            }
        } finally {
            canvas.restore();
        }
    }

    private void shapedRun(Ch c, Rectangle2D bbox, float alpha) throws IOException {
        SvgStyle st = c.style;
        if (c.hidden) {
            return;
        }
        SvgDraw.Paint fill = draw.paint(st.get("fill"), st);
        SvgDraw.Paint stroke = draw.paint(st.get("stroke"), st);
        float sw = draw.strokeWidth(st);
        float fo = alpha * Css.opacity(st.get("fill-opacity"));
        float so = alpha * Css.opacity(st.get("stroke-opacity"));
        boolean plain = fill.server() == null && (stroke.none() || sw <= 0);
        var canvas = draw.canvas;
        canvas.save();
        try {
            canvas.translate(c.x, c.y + c.shift);
            if (c.rotate != 0) {
                canvas.transform(AffineTransform.getRotateInstance(Math.toRadians(c.rotate)));
            }
            TextStyle ts = new TextStyle(c.face, Math.max(0.01f, Math.min(10_000, c.size)), Color.BLACK, 0, 100, 0,
                    false);
            if (plain && !fill.none()) {
                float a = fo * fill.alpha();
                if (a <= 0) {
                    return;
                }
                if (a < 1) {
                    PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                    gs.setNonStrokingAlphaConstant(a);
                    canvas.stream().setGraphicsStateParameters(gs);
                }
                canvas.drawGlyphs(c.shaped, 0, 0, ts.color(new Color(fill.rgb())));
                return;
            }
            boolean hide = canvas.hideText(true);
            canvas.drawGlyphs(c.shaped, 0, 0, ts);
            canvas.hideText(hide);
            Path2D outline = outline(c);
            Rectangle2D box;
            try {
                AffineTransform inv = AffineTransform.getTranslateInstance(c.x, c.y + c.shift);
                if (c.rotate != 0) {
                    inv.rotate(Math.toRadians(c.rotate));
                }
                box = inv.createInverse().createTransformedShape(bbox).getBounds2D();
            } catch (java.awt.geom.NoninvertibleTransformException e) {
                return;
            }
            for (String step : SvgDraw.paintOrder(st.get("paint-order"))) {
                if (step.equals("fill") && !fill.none()) {
                    draw.fill(outline, fill, fo, box, st);
                } else if (step.equals("stroke") && !stroke.none() && sw > 0) {
                    draw.stroke(outline, stroke, so, box, st, sw);
                }
            }
        } finally {
            canvas.restore();
        }
    }

    private static Path2D outline(Ch c) {
        Path2D.Float path = new Path2D.Float(Path2D.WIND_NON_ZERO);
        float k = c.size / c.face.unitsPerEm();
        for (int g = 0; g < c.shaped.size(); g++) {
            Shape s = c.face.glyphOutline(c.shaped.glyph(g));
            if (s == null) {
                continue;
            }
            AffineTransform t = AffineTransform.getTranslateInstance(c.shaped.x(g) * k, c.shaped.y(g) * k);
            t.scale(k, k);
            path.append(t.createTransformedShape(s), false);
        }
        return path;
    }

    private Path2D outline(Layout l, int from, int to, Ch origin) {
        Path2D.Float path = new Path2D.Float(Path2D.WIND_NON_ZERO);
        float x0 = origin.x;
        for (int i = from; i < to; i++) {
            Ch c = l.chars.get(i);
            if (c.skipped) {
                continue;
            }
            Shape g = c.face.glyphOutline(c.face.glyph(c.cp));
            if (g == null) {
                continue;
            }
            float k = c.size / c.face.unitsPerEm();
            AffineTransform t = AffineTransform.getTranslateInstance((c.x - x0) / origin.scaleX, 0);
            t.scale(k, k);
            path.append(t.createTransformedShape(g), false);
        }
        return path;
    }

    private void decorations(Layout l, Element text, SvgStyle style, Rectangle2D bbox, float alpha)
            throws IOException {
        for (Span sp : l.spans) {
            String deco = SvgDoc.lower(sp.style().own("text-decoration", sp.style().own("text-decoration-line", "")));
            if (deco.isEmpty() || deco.equals("none")) {
                continue;
            }
            SvgStyle ds = sp.style();
            for (int i = sp.start(); i < sp.end(); ) {
                Ch c = l.chars.get(i);
                if (c.skipped || c.onPath) {
                    i++;
                    continue;
                }
                int j = i + 1;
                float end = c.x + c.advance;
                while (j < sp.end()) {
                    Ch d = l.chars.get(j);
                    if (d.skipped) {
                        j++;
                        continue;
                    }
                    if (d.y != c.y || d.onPath || d.rotate != 0) {
                        break;
                    }
                    end = Math.max(end, d.x + d.advance);
                    j++;
                }
                var m = c.face.metrics();
                float upem = c.face.unitsPerEm();
                float size = ds.fontSize;
                float thick = Math.max(size / 20, m.underlineThickness() / upem * size);
                List<Float> ys = new ArrayList<>();
                if (deco.contains("underline")) {
                    ys.add(c.y - m.underlinePosition() / upem * size);
                }
                if (deco.contains("overline")) {
                    float asc = (m.useTypoMetrics() ? m.typoAscender() : m.hheaAscender()) / upem * size;
                    ys.add(c.y - asc);
                }
                if (deco.contains("line-through")) {
                    float pos = m.strikeoutPosition() > 0 ? m.strikeoutPosition() / upem * size : size * 0.3f;
                    ys.add(c.y - pos);
                }
                for (float y : ys) {
                    Path2D.Float r = new Path2D.Float();
                    float x0 = c.x;
                    r.moveTo(x0, y - thick / 2);
                    r.lineTo(end, y - thick / 2);
                    r.lineTo(end, y + thick / 2);
                    r.lineTo(x0, y + thick / 2);
                    r.closePath();
                    if (ds.visible()) {
                        draw.paintShape(r, sp.element(), ds, alpha, false);
                    }
                }
                i = j;
            }
        }
    }

    private static Rectangle2D bounds(Layout l) {
        Rectangle2D out = null;
        for (Ch c : l.chars) {
            if (c.skipped || c.face == null) {
                continue;
            }
            var m = c.face.metrics();
            float upem = c.face.unitsPerEm();
            float asc = (m.useTypoMetrics() ? m.typoAscender() : m.hheaAscender()) / upem * c.size;
            float desc = (m.useTypoMetrics() ? m.typoDescender() : m.hheaDescender()) / upem * c.size;
            Rectangle2D r = new Rectangle2D.Float(c.x, c.y + c.shift - asc, Math.max(c.advance, 0.001f), asc - desc);
            if (c.onPath || c.rotate != 0) {
                AffineTransform t = AffineTransform.getTranslateInstance(c.x, c.y + c.shift);
                t.rotate(c.onPath ? c.pathAngle : Math.toRadians(c.rotate));
                t.translate(-c.x, -(c.y + c.shift));
                r = t.createTransformedShape(r).getBounds2D();
            }
            out = out == null ? r : out.createUnion(r);
        }
        return out;
    }

    Rectangle2D bbox(Element text, SvgStyle style) {
        Layout l = layout(text, style);
        return bounds(l);
    }

    Shape outline(Element text, SvgStyle style) {
        Layout l = layout(text, style);
        Path2D.Float all = new Path2D.Float(Path2D.WIND_NON_ZERO);
        for (Ch c : l.chars) {
            if (c.skipped || c.inUnit || c.face == null) {
                continue;
            }
            if (c.shaped != null) {
                AffineTransform t = AffineTransform.getTranslateInstance(c.x, c.y + c.shift);
                if (c.rotate != 0) {
                    t.rotate(Math.toRadians(c.rotate));
                }
                all.append(t.createTransformedShape(outline(c)), false);
                continue;
            }
            Shape g = c.face.glyphOutline(c.face.glyph(c.cp));
            if (g == null) {
                continue;
            }
            float k = c.size / c.face.unitsPerEm();
            AffineTransform t = AffineTransform.getTranslateInstance(c.x, c.y + c.shift);
            if (c.onPath) {
                t.rotate(c.pathAngle);
            }
            if (c.rotate != 0) {
                t.rotate(Math.toRadians(c.rotate));
            }
            t.scale(k * c.scaleX, k);
            all.append(t.createTransformedShape(g), false);
        }
        return all;
    }
}
