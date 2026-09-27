package stirling.software.officeconvert.table;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import stirling.software.officeconvert.table.PageContent.Ruling;

final class Rulings {

    static final float POSITION_TOLERANCE = 1.2f;

    static final float GAP_TOLERANCE = 3.5f;

    private Rulings() {}

    static List<Ruling> merge(List<Ruling> input) {
        List<Ruling> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparingDouble(Ruling::pos).thenComparingDouble(Ruling::start));
        List<Ruling> result = new ArrayList<>();
        int i = 0;
        while (i < sorted.size()) {
            int j = i;
            float base = sorted.get(i).pos();
            while (j + 1 < sorted.size()
                    && sorted.get(j + 1).pos() - sorted.get(j).pos() <= POSITION_TOLERANCE
                    && sorted.get(j + 1).pos() - base <= POSITION_TOLERANCE * 2) {
                j++;
            }
            result.addAll(joinCollinear(sorted.subList(i, j + 1)));
            i = j + 1;
        }
        return result;
    }

    private static List<Ruling> joinCollinear(List<Ruling> cluster) {
        List<Ruling> byStart = new ArrayList<>(cluster);
        byStart.sort(Comparator.comparingDouble(Ruling::start));
        List<Ruling> out = new ArrayList<>();
        float start = byStart.getFirst().start();
        float end = byStart.getFirst().end();
        double weightedPos = 0;
        double weight = 0;
        float thickness = 0;
        int rgb = byStart.getFirst().rgb();
        for (Ruling r : byStart) {
            if (r.start() > end + GAP_TOLERANCE) {
                out.add(new Ruling((float) (weightedPos / weight), start, end, thickness, rgb));
                start = r.start();
                end = r.end();
                weightedPos = 0;
                weight = 0;
                thickness = 0;
                rgb = r.rgb();
            }
            end = Math.max(end, r.end());
            double len = Math.max(r.length(), 0.1);
            weightedPos += r.pos() * len;
            weight += len;
            thickness = Math.max(thickness, r.thickness());
        }
        out.add(new Ruling((float) (weightedPos / weight), start, end, thickness, rgb));
        return out;
    }

    static float[] cluster(List<Float> values, float tolerance) {
        List<Float> sorted = new ArrayList<>(values);
        sorted.sort(Float::compare);
        List<Float> out = new ArrayList<>();
        int i = 0;
        while (i < sorted.size()) {
            int j = i;
            double sum = sorted.get(i);
            while (j + 1 < sorted.size() && sorted.get(j + 1) - sorted.get(j) <= tolerance) {
                j++;
                sum += sorted.get(j);
            }
            out.add((float) (sum / (j - i + 1)));
            i = j + 1;
        }
        float[] result = new float[out.size()];
        for (int k = 0; k < result.length; k++) {
            result[k] = out.get(k);
        }
        return result;
    }

    static float coverage(List<Ruling> rulings, float pos, float from, float to, float tolerance) {
        float span = to - from;
        if (span <= 0) {
            return 1f;
        }
        List<float[]> pieces = new ArrayList<>();
        for (Ruling r : rulings) {
            if (Math.abs(r.pos() - pos) <= tolerance) {
                float s = Math.max(from, r.start());
                float e = Math.min(to, r.end());
                if (e > s) {
                    pieces.add(new float[] {s, e});
                }
            }
        }
        pieces.sort((a, b) -> Float.compare(a[0], b[0]));
        float covered = 0;
        float cursor = from;
        for (float[] p : pieces) {
            float s = Math.max(p[0], cursor);
            if (p[1] > s) {
                covered += p[1] - s;
                cursor = p[1];
            }
        }
        return covered / span;
    }
}
