package stirling.software.officeconvert.topdf.xlsx;

import java.util.Arrays;

final class BandOffsets {

    private static final int REACH = 4095;

    private final Band band;

    private double[] after;

    private int afterCount = 1;

    private double[] before = new double[1];

    private int beforeCount = 1;

    BandOffsets(Band band) {
        this.band = band;
        this.after = new double[] {band.length()};
    }

    double start(int index) {
        if (index >= band.first && index <= band.last + 1) {
            return band.start(index);
        }
        if (index < band.first) {
            int terms = (int) Math.min((long) band.first - index, REACH);
            while (beforeCount <= terms) {
                if (beforeCount == before.length) {
                    before = Arrays.copyOf(before, Math.min(REACH + 1, before.length * 2));
                }
                before[beforeCount] = before[beforeCount - 1] - band.size(band.first - beforeCount);
                beforeCount++;
            }
            return before[terms];
        }
        int terms = (int) Math.min((long) index - band.last - 1, REACH);
        while (afterCount <= terms) {
            if (afterCount == after.length) {
                after = Arrays.copyOf(after, Math.min(REACH + 1, after.length * 2));
            }
            after[afterCount] = after[afterCount - 1] + band.size(band.last + afterCount);
            afterCount++;
        }
        return after[terms];
    }
}
