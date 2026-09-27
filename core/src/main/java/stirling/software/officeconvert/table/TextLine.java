package stirling.software.officeconvert.table;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import stirling.software.officeconvert.table.PageContent.PdfWord;

record TextLine(
        List<PdfWord> words,
        float x,
        float right,
        float top,
        float bottom,
        float baseline,
        float fontSize) {

    private static final float BASELINE_TOLERANCE = 0.35f;

    static TextLine of(List<PdfWord> words) {
        List<PdfWord> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingDouble(PdfWord::x));
        float x = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        float top = Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        float size = 0;
        double baselineSum = 0;
        int chars = 0;
        for (PdfWord w : sorted) {
            x = Math.min(x, w.x());
            right = Math.max(right, w.right());
            top = Math.min(top, w.top());
            bottom = Math.max(bottom, w.bottom());
            size = Math.max(size, w.fontSize());
            int n = Math.max(1, w.text().length());
            baselineSum += (double) w.baseline() * n;
            chars += n;
        }
        return new TextLine(sorted, x, right, top, bottom, (float) (baselineSum / chars), size);
    }

    static List<TextLine> group(List<PdfWord> words) {
        List<PdfWord> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingDouble(PdfWord::baseline));
        List<TextLine> lines = new ArrayList<>();
        List<PdfWord> current = new ArrayList<>();
        float currentBaseline = 0;
        float currentSize = 0;
        for (PdfWord w : sorted) {
            if (!current.isEmpty()) {
                float tolerance = BASELINE_TOLERANCE * Math.min(currentSize, w.fontSize());
                if (Math.abs(w.baseline() - currentBaseline) > Math.max(tolerance, 1f)) {
                    lines.add(of(current));
                    current = new ArrayList<>();
                }
            }
            if (current.isEmpty()) {
                currentBaseline = w.baseline();
                currentSize = w.fontSize();
            }
            current.add(w);
        }
        if (!current.isEmpty()) {
            lines.add(of(current));
        }
        return attachBullets(lines);
    }

    private static List<TextLine> attachBullets(List<TextLine> lines) {
        List<TextLine> result = new ArrayList<>(lines);
        for (TextLine line : lines) {
            if (!isBulletLine(line)) {
                continue;
            }
            TextLine best = null;
            float bestDistance = Float.MAX_VALUE;
            for (TextLine other : result) {
                if (other == line
                        || isBulletLine(other)
                        || other.x() < line.right() - 1f
                        || other.top() > line.bottom()
                        || other.bottom() < line.top()) {
                    continue;
                }
                float distance =
                        Math.abs((other.top() + other.bottom()) - (line.top() + line.bottom()));
                if (distance < bestDistance) {
                    best = other;
                    bestDistance = distance;
                }
            }
            if (best != null) {
                List<PdfWord> merged = new ArrayList<>(best.words());
                merged.addAll(line.words());
                TextLine joined = of(merged);
                joined =
                        new TextLine(
                                joined.words(),
                                joined.x(),
                                joined.right(),
                                joined.top(),
                                joined.bottom(),
                                best.baseline(),
                                best.fontSize());
                result.set(result.indexOf(best), joined);
                result.remove(line);
            }
        }
        return result;
    }

    private static boolean isBulletLine(TextLine line) {
        for (PdfWord w : line.words()) {
            if (!ListMarkers.isBullet(w.text())) {
                return false;
            }
        }
        return true;
    }

    String text() {
        return joinWords(words);
    }

    static String joinWords(List<PdfWord> words) {
        StringBuilder sb = new StringBuilder();
        PdfWord prev = null;
        for (PdfWord w : words) {
            if (prev != null) {
                float gap = w.x() - prev.right();
                float spaceGap =
                        Math.min(
                                Math.min(prev.spaceWidth(), w.spaceWidth()) * 0.4f,
                                prev.fontSize() * 0.12f);
                if (gap > spaceGap) {
                    sb.append(' ');
                }
            }
            sb.append(w.text());
            prev = w;
        }
        return sb.toString();
    }

    List<List<PdfWord>> chunks(float minGap) {
        List<List<PdfWord>> chunks = new ArrayList<>();
        List<PdfWord> current = new ArrayList<>();
        PdfWord prev = null;
        for (PdfWord w : words) {
            if (prev != null && w.x() - prev.right() > minGap) {
                chunks.add(current);
                current = new ArrayList<>();
            }
            current.add(w);
            prev = w;
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }
}
