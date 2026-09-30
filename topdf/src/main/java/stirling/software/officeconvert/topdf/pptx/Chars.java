package stirling.software.officeconvert.topdf.pptx;

import java.util.List;

import stirling.software.officeconvert.topdf.font.BidiRuns;
import stirling.software.officeconvert.topdf.font.Clusters;
import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.GlyphRun;
import stirling.software.officeconvert.topdf.pdf.TextStyle;

final class Chars {

    static final float DEVICE_UNITS_PER_POINT = 576f / 72f;

    final List<Piece> pieces;

    final int length;

    final int[] codePoints;

    final float[] advances;

    final int[] piece;

    final int[] offset;

    final boolean[] breakAfter;

    final boolean[] joined;

    final boolean[] wordStart;

    Chars(List<Piece> pieces) {
        this.pieces = pieces;
        int n = 0;
        for (Piece p : pieces) {
            n += p.text().codePointCount(0, p.text().length());
        }
        length = n;
        codePoints = new int[n];
        advances = new float[n];
        piece = new int[n];
        offset = new int[n];
        breakAfter = new boolean[n];
        joined = new boolean[n];
        wordStart = new boolean[n];
        StringBuilder text = new StringBuilder();
        for (Piece p : pieces) {
            text.append(p.text());
        }
        String all = text.toString();
        boolean[] words = Clusters.dictionaryBreaks(all);
        int at = 0;
        for (int m = 0; m < n; m++) {
            joined[m] = at > 0 && Clusters.joined(all, at);
            wordStart[m] = words[at];
            at += Character.charCount(all.codePointAt(at));
        }
        int k = 0;
        for (int pi = 0; pi < pieces.size(); pi++) {
            Piece p = pieces.get(pi);
            TextStyle s = p.style();
            FontFace face = s.face();
            float scale = s.size() / face.unitsPerEm() * (s.horizontalScale() / 100f);
            Advances metrics = p.metrics();
            String t = p.text();
            int previous = -1;
            for (int i = 0; i < t.length(); ) {
                int cp = t.codePointAt(i);
                codePoints[k] = cp;
                piece[k] = pi;
                offset[k] = i;
                float a;
                if (cp == '\n' || cp == '\t' || Character.isISOControl(cp) || cp == FontFace.SOFT_HYPHEN) {
                    a = 0;
                    previous = -1;
                } else {
                    float real = metrics == null ? Float.NaN : metrics.advance(cp);
                    a = device(Float.isNaN(real) ? face.advance(cp) * scale : real * s.size()) + s.charSpacing();
                    if (s.kerning() && previous >= 0 && metrics == null) {
                        a += face.kerning(previous, cp) * scale;
                    }
                    previous = cp;
                }
                advances[k] = a;
                i += Character.charCount(cp);
                k++;
            }
            if (FontFace.needsShaping(t)) {
                shapeWords(k - t.codePointCount(0, t.length()), k, face, s);
            }
        }
        for (int i = 0; i < n; i++) {
            breakAfter[i] = breakable(i);
        }
    }

    static float device(float points) {
        return Math.round(points * DEVICE_UNITS_PER_POINT) / DEVICE_UNITS_PER_POINT;
    }

    static float floorDevice(float points) {
        return (float) Math.floor(points * DEVICE_UNITS_PER_POINT + 1e-3f) / DEVICE_UNITS_PER_POINT;
    }

    // Each word (a Thai word as the dictionary finds it) is shaped whole; its width is shared out over its
    // clusters as their own advances share it, so a line can still break between clusters
    private void shapeWords(int from, int to, FontFace face, TextStyle s) {
        int i = from;
        while (i < to) {
            if (space(codePoints[i]) || codePoints[i] == '\n') {
                i++;
                continue;
            }
            int j = wordEnd(i, to);
            String word = text(i, j);
            GlyphRun run = face.shape(word, BidiRuns.baseRightToLeft(word));
            float shaped = run.width(s.size()) * (s.horizontalScale() / 100f) + (j - i) * s.charSpacing();
            float natural = 0;
            for (int m = i; m < j; m++) {
                natural += advances[m];
            }
            int cluster = i;
            for (int m = i; m < j; m++) {
                if (m > i && joined[m]) {
                    advances[cluster] += advances[m];
                    advances[m] = 0;
                } else {
                    cluster = m;
                }
            }
            for (int m = i; m < j; m++) {
                advances[m] = natural > 0 ? advances[m] * shaped / natural : m == i ? shaped : 0;
            }
            i = j;
        }
    }

    int wordEnd(int i, int to) {
        int j = i + 1;
        while (j < to && !space(codePoints[j]) && codePoints[j] != '\n' && !wordStart[j]) {
            j++;
        }
        return j;
    }

    static boolean space(int cp) {
        return cp == ' ' || cp == '\u3000' || cp == '\t';
    }

    private boolean breakable(int i) {
        int cp = codePoints[i];
        int next = i + 1 < length ? codePoints[i + 1] : -1;
        if (cp == '\n') {
            return true;
        }
        if (cp == FontFace.SOFT_HYPHEN) {
            return next >= 0 && !space(next) && next != '\n';
        }
        if (cp == '\t') {
            return false;
        }
        if (space(cp) || cp == 0x200B) {
            return next < 0 || !space(next);
        }
        if (next < 0 || space(next) || next == '\n') {
            return false;
        }
        if (joined[i + 1]) {
            return false;
        }
        if (wordStart[i + 1]) {
            return true;
        }
        if ((cp == '-' || cp == 0x2010 || cp == 0x2013 || cp == 0x2014) && i > 0
                && Character.isLetterOrDigit(codePoints[i - 1]) && Character.isLetterOrDigit(next)) {
            return true;
        }
        if (cjk(cp) || cjk(next)) {
            return !closing(next) && !opening(cp);
        }
        return false;
    }

    static boolean cjk(int cp) {
        return cp >= 0x2E80 && cp <= 0x9FFF || cp >= 0xAC00 && cp <= 0xD7AF || cp >= 0xF900 && cp <= 0xFAFF
                || cp >= 0xFF00 && cp <= 0xFF60 || cp >= 0x20000 && cp <= 0x3FFFF || cp >= 0x3040 && cp <= 0x30FF;
    }

    private static boolean closing(int cp) {
        return ("\u3001\u3002\uFF0C\uFF0E\uFF09\u300D\u300F\u3011"
                + "\u3009\u300B\uFF01\uFF1F\uFF1A\uFF1B\u30FC\u3005)]}!?,.:;%")
                .indexOf(cp) >= 0 || cp >= 0x3041 && cp <= 0x3049 && (cp & 1) == 1;
    }

    private static boolean opening(int cp) {
        return "\uFF08\u300C\u300E\u3010\u3008\u300A([{".indexOf(cp) >= 0;
    }

    String text(int from, int to) {
        if (from >= to) {
            return "";
        }
        Piece p = pieces.get(piece[from]);
        int end = offset[to - 1] + Character.charCount(codePoints[to - 1]);
        return p.text().substring(offset[from], end);
    }

    String text(int from, int to, boolean across) {
        if (!across) {
            return text(from, to);
        }
        StringBuilder b = new StringBuilder();
        for (int i = from; i < to; i++) {
            b.appendCodePoint(codePoints[i]);
        }
        return b.toString();
    }

    float hyphen(int i) {
        Piece p = pieces.get(piece[i]);
        TextStyle s = p.style();
        float real = p.metrics() == null ? Float.NaN : p.metrics().advance('-');
        float a = Float.isNaN(real)
                ? s.face().advance('-') * s.size() / s.face().unitsPerEm() * (s.horizontalScale() / 100f)
                : real * s.size();
        return device(a) + s.charSpacing();
    }

    float width(int from, int to) {
        float w = 0;
        for (int i = from; i < to; i++) {
            w += advances[i];
        }
        return w;
    }
}
