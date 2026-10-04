package stirling.software.officeconvert.layout;

import java.util.Arrays;

import stirling.software.officeconvert.extract.Glyph;

final class LineTraits {

    static final float EDGE = 1.8f;

    private LineTraits() {}

    static boolean rtl(Line l) {
        return LogicalOrder.rtlBase(l);
    }

    static boolean ideographic(Line l) {
        int ideographs = 0;
        int letters = 0;
        for (Word w : l.words) {
            for (int i = 0; i < w.text.length(); ) {
                int cp = w.text.codePointAt(i);
                i += Character.charCount(cp);
                if (Character.isLetter(cp)) {
                    letters++;
                    if (cp < 0x1100) {
                        continue;
                    }
                    Character.UnicodeScript s = Character.UnicodeScript.of(cp);
                    ideographs += s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA
                            || s == Character.UnicodeScript.KATAKANA ? 1 : 0;
                }
            }
        }
        return letters >= 10 && ideographs * 2 > letters;
    }

    static boolean unspaced(Line l) {
        int unspaced = 0;
        int letters = 0;
        for (Word w : l.words) {
            for (int i = 0; i < w.text.length(); ) {
                int cp = w.text.codePointAt(i);
                i += Character.charCount(cp);
                if (!Character.isLetter(cp)) {
                    continue;
                }
                letters++;
                Character.UnicodeScript s = Character.UnicodeScript.of(cp);
                if (s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA || s == Character.UnicodeScript.KATAKANA
                        || s == Character.UnicodeScript.THAI || s == Character.UnicodeScript.LAO || s == Character.UnicodeScript.KHMER
                        || s == Character.UnicodeScript.MYANMAR || s == Character.UnicodeScript.TIBETAN) {
                    unspaced++;
                }
            }
        }
        return letters >= 10 && unspaced * 2 > letters;
    }

    static boolean isFillLine(Line l) {
        for (Word w : l.words) {
            for (int i = 0; i < w.text.length(); i++) {
                if (w.text.charAt(i) != '_') {
                    return false;
                }
            }
        }
        return l.words.getFirst().text.length() >= 5;
    }

    static boolean allBold(Line l) {
        for (Word w : l.words) {
            for (Glyph g : w.glyphs) {
                if (!g.bold) {
                    return false;
                }
            }
        }
        return true;
    }

    static boolean noneBold(Line l) {
        for (Word w : l.words) {
            for (Glyph g : w.glyphs) {
                if (g.bold) {
                    return false;
                }
            }
        }
        return true;
    }

    static float spaceWidth(Line l) {
        if (!Float.isNaN(l.drawnSpace)) {
            return l.drawnSpace;
        }
        Glyph g = l.words.getLast().last();
        return Math.max(g.spaceWidth, g.size * 0.2f);
    }

    static boolean continuesNumbering(Line opening, Line cur) {
        Marker a = leadingMarker(opening);
        Marker b = leadingMarker(cur);
        return a != null && b != null && !a.isBullet() && a.kind() == b.kind() && b.value() == a.value() + 1
                && a.prefix().equals(b.prefix()) && a.suffix().equals(b.suffix()) && !a.suffix().isEmpty();
    }

    private static Marker leadingMarker(Line line) {
        return Marker.leading(line);
    }

    static boolean startsListItem(Line line) {
        Marker m = Marker.leading(line);
        if (m == null) {
            return false;
        }
        float gap = Marker.gapAfter(line);
        float size = line.size;
        boolean tab = Marker.tabAfter(line);
        if (m.isBullet()) {
            return gap > 0.15f * size || tab;
        }
        return tab || gap > 0.35f * size && gap > 1.3f * medianWordGap(line);
    }

    private static float medianWordGap(Line line) {
        if (line.words.size() < 5) {
            return 0f;
        }
        int from = Marker.startIndex(line) == 0 ? 2 : 1;
        float[] gaps = new float[line.words.size() - 2];
        for (int i = from; i < from + gaps.length; i++) {
            gaps[i - from] = line.words.get(i).x - line.words.get(i - 1).right;
        }
        Arrays.sort(gaps);
        return gaps[gaps.length / 2];
    }

    static boolean endsWithHyphen(Line l) {
        String t = l.words.getLast().text;
        if (t.length() < 2) {
            return false;
        }
        char c = t.charAt(t.length() - 1);
        char before = t.charAt(t.length() - 2);
        return (c == '-' || c == '­' || c == '‐') && Character.isLetter(before);
    }

    static boolean isCentred(Line l, float colLeft, float colRight) {
        float colCentre = (colLeft + colRight) / 2f;
        float width = colRight - colLeft;
        return Math.abs(l.centre() - colCentre) <= Math.max(2.5f, width * 0.01f)
                && l.x > colLeft + 3 * EDGE
                && l.right < colRight - 3 * EDGE;
    }

    static boolean looksStretched(Line l) {
        if (l.words.size() < 3) {
            return false;
        }
        float total = 0;
        int n = 0;
        for (int i = 1; i < l.words.size(); i++) {
            if (l.gaps[i] == Line.SPACE) {
                total += l.words.get(i).x - l.words.get(i - 1).right;
                n++;
            }
        }
        if (n == 0) {
            return false;
        }
        return total / n > spaceWidth(l) * 1.12f;
    }
}
