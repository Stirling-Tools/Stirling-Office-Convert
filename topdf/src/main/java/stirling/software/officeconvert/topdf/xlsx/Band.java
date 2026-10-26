package stirling.software.officeconvert.topdf.xlsx;

import java.util.Arrays;
import java.util.function.IntToDoubleFunction;

final class Band {

    private static final int REACH = 4095;

    final int first;

    final int last;

    private final double[] starts;

    private final double[] sizes;

    private final IntToDoubleFunction size;

    private double[] after;

    private int afterCount = 1;

    private double[] before = new double[1];

    private int beforeCount = 1;

    Band(int first, int last, IntToDoubleFunction size) {
        this.first = first;
        this.last = last;
        this.size = size;
        this.starts = new double[last - first + 2];
        this.sizes = new double[last - first + 1];
        double at = 0;
        for (int i = first; i <= last; i++) {
            starts[i - first] = at;
            sizes[i - first] = size.applyAsDouble(i);
            at += sizes[i - first];
        }
        starts[last - first + 1] = at;
        after = new double[] {at};
    }

    double length() {
        return starts[starts.length - 1];
    }

    double start(int index) {
        if (index >= first && index <= last + 1) {
            return starts[index - first];
        }
        if (index < first) {
            int terms = (int) Math.min((long) first - index, REACH);
            while (beforeCount <= terms) {
                if (beforeCount == before.length) {
                    before = Arrays.copyOf(before, Math.min(REACH + 1, before.length * 2));
                }
                before[beforeCount] = before[beforeCount - 1] - size.applyAsDouble(first - beforeCount);
                beforeCount++;
            }
            return before[terms];
        }
        int terms = (int) Math.min((long) index - last - 1, REACH);
        while (afterCount <= terms) {
            if (afterCount == after.length) {
                after = Arrays.copyOf(after, Math.min(REACH + 1, after.length * 2));
            }
            after[afterCount] = after[afterCount - 1] + size.applyAsDouble(last + afterCount);
            afterCount++;
        }
        return after[terms];
    }

    double end(int index) {
        return start(index + 1);
    }

    double size(int index) {
        if (index >= first && index <= last) {
            return sizes[index - first];
        }
        return size.applyAsDouble(index);
    }
}
