package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.PageGraphics.Rule;

final class Footnotes {

    record Note(String marker, List<Line> lines, List<Glyph> reference) {}

    record Found(List<Note> notes, List<Line> continuation, List<Line> consumed, Rule separator) {}

    private Footnotes() {}

    static Found detect(List<Line> flow, List<Rule> rules, float colLeft, float colRight, float pageHeight, float bodySize) {
        Rule sep = separator(rules, colLeft, colRight, pageHeight);
        if (sep == null) {
            return null;
        }
        List<Line> below = new ArrayList<>();
        List<Line> above = new ArrayList<>();
        float noteStartLimit = Math.max(sep.end(), colLeft + 72);
        for (Line l : flow) {
            (l.top >= sep.pos() - 1 && l.x < noteStartLimit ? below : above).add(l);
        }
        if (below.isEmpty()) {
            return null;
        }
        below.sort((a, b) -> Float.compare(a.top, b.top));
        float blockBottom = sep.pos();
        for (int i = 0; i < below.size(); i++) {
            Line l = below.get(i);
            if (l.top - blockBottom > 1.2f * l.size + 2f) {
                above.addAll(below.subList(i, below.size()));
                below = new ArrayList<>(below.subList(0, i));
                break;
            }
            blockBottom = Math.max(blockBottom, l.bottom);
        }
        if (below.isEmpty()) {
            return null;
        }
        float noteRight = 0;
        for (Line l : below) {
            noteRight = Math.max(noteRight, l.right + l.size);
        }
        for (Line l : new ArrayList<>(above)) {
            if (l.x < noteRight && below.stream().anyMatch(b -> Math.abs(b.baseline - l.baseline) < 1.5f)) {
                above.remove(l);
                below.add(l);
            }
        }
        float notesTop = Float.MAX_VALUE;
        float notesBottom = -Float.MAX_VALUE;
        for (Line l : below) {
            notesTop = Math.min(notesTop, l.top);
            notesBottom = Math.max(notesBottom, l.bottom);
        }
        for (Line l : above) {
            if (l.x >= noteRight && l.top < notesBottom && l.bottom > notesTop) {
                return null;
            }
        }
        List<Line> rows = raisedMarkers(LineGroups.joinRows(new ArrayList<>(below)));
        List<String> markers = new ArrayList<>();
        List<List<Line>> bodies = new ArrayList<>();
        List<Line> continuation = new ArrayList<>();
        for (Line l : rows) {
            if (l.size > bodySize * 1.05f) {
                return null;
            }
            String marker = leadingMarker(l);
            if (marker != null) {
                markers.add(marker.endsWith(".") ? marker.substring(0, marker.length() - 1) : marker);
                List<Line> body = new ArrayList<>();
                Line rest = withoutMarker(l, marker);
                if (rest != null) {
                    body.add(rest);
                }
                bodies.add(body);
            } else if (!bodies.isEmpty()) {
                bodies.getLast().add(l);
            } else {
                continuation.add(l);
            }
        }
        List<Note> notes = new ArrayList<>();
        List<Glyph> taken = new ArrayList<>();
        for (int i = 0; i < markers.size(); i++) {
            List<Glyph> ref = findReference(above, markers.get(i), taken);
            if (ref == null) {
                return null;
            }
            taken.addAll(ref);
            notes.add(new Note(markers.get(i), bodies.get(i), ref));
        }
        if (notes.isEmpty() && continuation.isEmpty()) {
            return null;
        }
        if (notes.isEmpty()) {
            for (Line l : continuation) {
                if (l.size > bodySize * 0.95f) {
                    return null;
                }
            }
        }
        flow.removeAll(below);
        rules.remove(sep);
        return new Found(notes, continuation, below, sep);
    }

    private static List<Line> raisedMarkers(List<Line> rows) {
        rows.sort((a, b) -> Float.compare(a.baseline, b.baseline));
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Line m = rows.get(i);
            Line next = i + 1 < rows.size() ? rows.get(i + 1) : null;
            boolean raised = next != null && m.words.size() == 1 && m.size < next.size
                    && m.words.getFirst().text.matches("[0-9]{1,3}|[*†‡§¶]{1,3}")
                    && next.baseline - m.baseline > 0 && next.baseline - m.baseline < 0.6f * next.size
                    && next.x >= m.right - 1 && next.x - m.right < 2 * next.size;
            if (raised) {
                for (Glyph g : m.words.getFirst().glyphs) {
                    g.vertAlign = 1;
                }
                List<Word> words = new ArrayList<>(m.words);
                words.addAll(next.words);
                out.add(LineGroups.fromWords(words));
                i++;
            } else {
                out.add(m);
            }
        }
        return out;
    }

    private static Rule separator(List<Rule> rules, float colLeft, float colRight, float pageHeight) {
        float width = colRight - colLeft;
        Rule best = null;
        for (Rule r : rules) {
            if (!r.horizontal() || r.thickness() > 1.6f || r.pos() < pageHeight * 0.45f
                    || r.length() < 15 || r.length() > width * 0.5f || Math.abs(r.start() - colLeft) > 8) {
                continue;
            }
            if (best == null || r.pos() > best.pos()) {
                best = r;
            }
        }
        return best;
    }

    private static String leadingMarker(Line l) {
        Word first = l.words.getFirst();
        StringBuilder sb = new StringBuilder();
        for (Glyph g : first.glyphs) {
            if (g.vertAlign != 1) {
                break;
            }
            sb.append(g.text);
        }
        if (!sb.isEmpty()) {
            return sb.toString();
        }
        String t = first.text;
        if (l.words.size() > 1 && t.matches("[0-9]{1,3}[.]?|[*†‡§¶]{1,3}")) {
            return t;
        }
        return null;
    }

    private static Line withoutMarker(Line l, String marker) {
        Word first = l.words.getFirst();
        if (first.text.equals(marker)) {
            return l.words.size() > 1 ? l.slice(1, l.words.size()) : null;
        }
        List<Glyph> rest = new ArrayList<>(first.glyphs.subList(countGlyphs(first, marker), first.glyphs.size()));
        List<Word> words = new ArrayList<>();
        if (!rest.isEmpty()) {
            words.add(new Word(rest));
        }
        words.addAll(l.words.subList(1, l.words.size()));
        if (words.isEmpty()) {
            return null;
        }
        return LineGroups.fromWords(words);
    }

    private static int countGlyphs(Word w, String marker) {
        int chars = 0;
        int n = 0;
        for (Glyph g : w.glyphs) {
            if (chars >= marker.length()) {
                break;
            }
            chars += g.text.length();
            n++;
        }
        return n;
    }

    private static List<Glyph> findReference(List<Line> body, String marker, List<Glyph> taken) {
        List<Line> ordered = new ArrayList<>(body);
        ordered.sort((a, b) -> Float.compare(a.baseline, b.baseline));
        for (Line l : ordered) {
            List<Glyph> run = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            for (Word w : l.words) {
                Iterator<Glyph> it = w.glyphs.iterator();
                while (it.hasNext()) {
                    Glyph g = it.next();
                    if (g.vertAlign == 1 && !taken.contains(g)) {
                        run.add(g);
                        sb.append(g.text);
                        if (sb.toString().equals(marker)) {
                            return run;
                        }
                        if (!marker.startsWith(sb.toString())) {
                            run = new ArrayList<>();
                            sb.setLength(0);
                        }
                    } else {
                        run = new ArrayList<>();
                        sb.setLength(0);
                    }
                }
            }
        }
        return null;
    }
}
