package stirling.software.officeconvert.topdf.html;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jsoup.nodes.Element;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;

final class TextFit {

    private static final int BOLD_LABEL_CHARS = 120;

    private TextFit() {}

    static List<List<Inline>> lines(List<Inline> inlines) {
        List<List<Inline>> lines = new ArrayList<>();
        List<Inline> line = new ArrayList<>();
        for (Inline in : inlines) {
            if (in instanceof Inline.Break) {
                lines.add(line);
                line = new ArrayList<>();
            } else {
                line.add(in);
            }
        }
        lines.add(line);
        while (!lines.isEmpty() && blank(lines.getLast())) {
            lines.removeLast();
        }
        while (!lines.isEmpty() && blank(lines.getFirst())) {
            lines.removeFirst();
        }
        for (List<Inline> l : lines) {
            trimEnd(l);
        }
        return lines;
    }

    static boolean blank(List<Inline> line) {
        for (Inline in : line) {
            if (!(in instanceof Inline.Text t) || !t.text().isBlank()) {
                return false;
            }
        }
        return true;
    }

    static boolean boldLabel(Paragraph p) {
        int length = 0;
        for (Inline i : p.inlines) {
            if (!(i instanceof Inline.Text t)) {
                return false;
            }
            if (!t.text().isBlank() && !t.style().bold()) {
                return false;
            }
            length += t.text().strip().length();
        }
        return length > 0 && length <= BOLD_LABEL_CHARS;
    }

    // Preformatted and no-wrap lines run as wide as they are, past the page if need be
    static void measureUnwrapped(HtmlContext ctx, Paragraph p, String ws) {
        if (!ws.equals("pre") && !ws.equals("nowrap")) {
            return;
        }
        float line = 0;
        float widest = 0;
        int column = 0;
        for (Inline i : p.inlines) {
            if (i instanceof Inline.Text t) {
                line += t.text().length() * t.style().size() * charWidth(t.style().font());
                column += t.text().length();
            } else if (i instanceof Inline.Tab t) {
                // a tab moves to the next stop eight characters along
                int step = 8 - column % 8;
                line += step * t.style().size() * charWidth(t.style().font());
                column += step;
            } else if (i instanceof Inline.Break) {
                widest = Math.max(widest, line);
                line = 0;
                column = 0;
            }
        }
        widest = Math.max(widest, line);
        // the paragraph's indent already counts from the text area's left edge
        ctx.wants(p.indentLeft + widest);
    }

    // An average character's advance in ems: fixed-pitch faces are wider
    private static float charWidth(String font) {
        String f = font == null ? "" : font.toLowerCase(Locale.ROOT);
        boolean mono = f.contains("mono") || f.contains("courier") || f.contains("consol") || f.contains("menlo")
                || f.contains("code");
        return mono ? 0.6f : 0.5f;
    }

    // A block given a width wider than its frame pushes past the page in a browser
    static void measureFixed(HtmlContext ctx, Element e, Map<String, String> d, Look look, Flow.Frame frame,
            float left, float right) {
        String position = d.getOrDefault("position", "").strip().toLowerCase(Locale.ROOT);
        if (position.equals("absolute") || position.equals("fixed") || shrinksInRow(ctx, e, d, look)) {
            return;
        }
        Float w = BoxCss.absolute(d.get("min-width"), look);
        Float width = BoxCss.absolute(d.get("width"), look);
        if (width != null && (w == null || width > w)) {
            Float max = BoxCss.absolute(d.get("max-width"), look);
            boolean capped = max != null || d.getOrDefault("max-width", "none").contains("%");
            w = capped ? (max == null ? null : Math.min(width, max)) : width;
        }
        if (w != null && w > frame.width() - left - right) {
            ctx.wants(frame.left() + left + w + right);
        }
    }

    // A flex item gives up width to fit its row unless it is told not to shrink
    private static boolean shrinksInRow(HtmlContext ctx, Element e, Map<String, String> d, Look look) {
        Element parent = e.parent();
        if (parent == null) {
            return false;
        }
        String display = ctx.declared(parent, look).getOrDefault("display", "").strip().toLowerCase(Locale.ROOT);
        if (!display.endsWith("flex")) {
            return false;
        }
        String shrink = d.getOrDefault("flex-shrink", "").strip();
        String flex = d.getOrDefault("flex", "").strip().toLowerCase(Locale.ROOT);
        return !shrink.equals("0") && !flex.equals("none") && !flex.matches("[0-9.]+\\s+0(\\s.*)?");
    }

    // Keeps the first chars characters of a paragraph's text, ending at a word
    static void cut(Paragraph p, int chars) {
        int left = chars;
        for (int i = 0; i < p.inlines.size(); i++) {
            if (!(p.inlines.get(i) instanceof Inline.Text t)) {
                continue;
            }
            if (t.text().length() <= left) {
                left -= t.text().length();
                continue;
            }
            int at = t.text().lastIndexOf(' ', left);
            String kept = t.text().substring(0, Math.max(0, at > 0 ? at : left)).stripTrailing();
            p.inlines.set(i, new Inline.Text(kept + "\u2026", t.style(), t.link(), t.anchorPage()));
            p.inlines.subList(i + 1, p.inlines.size()).clear();
            return;
        }
    }

    static void trimStart(List<Inline> inlines) {
        while (!inlines.isEmpty() && inlines.getFirst() instanceof Inline.Text t && t.text().isBlank()) {
            inlines.removeFirst();
        }
        if (!inlines.isEmpty() && inlines.getFirst() instanceof Inline.Text t && t.text().startsWith(" ")) {
            inlines.set(0, new Inline.Text(t.text().stripLeading(), t.style(), t.link(), t.anchorPage()));
        }
    }

    static void trimEnd(List<Inline> inlines) {
        while (!inlines.isEmpty()) {
            Inline last = inlines.getLast();
            if (last instanceof Inline.Break) {
                inlines.removeLast();
                continue;
            }
            if (last instanceof Inline.Text t) {
                String s = t.text().stripTrailing();
                if (s.length() != t.text().length() && !t.text().endsWith("\u00a0")) {
                    inlines.removeLast();
                    if (!s.isEmpty()) {
                        inlines.add(new Inline.Text(s, t.style(), t.link(), t.anchorPage()));
                        return;
                    }
                    continue;
                }
            }
            return;
        }
    }
}
