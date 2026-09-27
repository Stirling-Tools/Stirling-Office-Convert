package stirling.software.officeconvert.sheet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.ParaDraft;

final class ColumnSplit {

    record Piece(String text, float x, float right) {}

    private static final float AGREE = 0.6f;

    private ColumnSplit() {}

    static List<Piece> pieces(List<ParaDraft> paras) {
        if (paras.size() != 1 || paras.getFirst().lines.size() != 1) {
            return null;
        }
        Line line = paras.getFirst().lines.getFirst();
        if (line.words.isEmpty() || line.words.stream().anyMatch(w -> w.glyphs.stream().anyMatch(LogicalText::isRtl))) {
            return null;
        }
        List<Piece> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        float x = line.words.getFirst().x;
        float right = x;
        for (int i = 0; i < line.words.size(); i++) {
            if (i > 0 && line.gaps[i] != Line.SPACE) {
                out.add(new Piece(sb.toString(), x, right));
                sb.setLength(0);
                x = line.words.get(i).x;
            }
            sb.append(sb.isEmpty() ? "" : " ").append(line.words.get(i).text);
            right = line.words.get(i).right;
        }
        out.add(new Piece(sb.toString(), x, right));
        return out;
    }

    static List<float[]> plan(int cols, List<Integer> colOf, List<Integer> spanOf, List<List<Piece>> pieces) {
        List<float[]> out = new ArrayList<>();
        for (int c = 0; c < cols; c++) {
            Map<Integer, Integer> counts = new HashMap<>();
            int filled = 0;
            for (int i = 0; i < pieces.size(); i++) {
                if (colOf.get(i) == c && spanOf.get(i) == 1 && pieces.get(i) != null) {
                    filled++;
                    counts.merge(pieces.get(i).size(), 1, Integer::sum);
                }
            }
            int n = 1;
            int best = 0;
            for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
                if (e.getKey() >= 2 && (e.getValue() > best || e.getValue() == best && e.getKey() < n)) {
                    n = e.getKey();
                    best = e.getValue();
                }
            }
            out.add(n < 2 || best < 2 || best < AGREE * filled ? new float[0] : cuts(c, n, colOf, spanOf, pieces));
        }
        return out;
    }

    private static float[] cuts(int c, int n, List<Integer> colOf, List<Integer> spanOf, List<List<Piece>> pieces) {
        float[] cut = new float[n - 1];
        for (int k = 0; k + 1 < n; k++) {
            float end = -Float.MAX_VALUE;
            float start = Float.MAX_VALUE;
            for (int i = 0; i < pieces.size(); i++) {
                List<Piece> p = pieces.get(i);
                if (colOf.get(i) == c && spanOf.get(i) == 1 && p != null && p.size() == n) {
                    end = Math.max(end, p.get(k).right());
                    start = Math.min(start, p.get(k + 1).x());
                }
            }
            if (end >= start - 1f) {
                return new float[0];
            }
            cut[k] = (end + start) / 2f;
        }
        return cut;
    }

    static int part(float[] cuts, float x) {
        int k = 0;
        while (k < cuts.length && x >= cuts[k]) {
            k++;
        }
        return k;
    }
}
