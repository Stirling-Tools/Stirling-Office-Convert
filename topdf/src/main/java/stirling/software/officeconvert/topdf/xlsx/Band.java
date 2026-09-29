package stirling.software.officeconvert.topdf.xlsx;

import java.util.function.IntToDoubleFunction;

final class Band {

    final int first;

    final int last;

    private final double[] starts;

    private final double[] sizes;

    private final IntToDoubleFunction size;

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
    }

    double length() {
        return starts[starts.length - 1];
    }

    double start(int index) {
        if (index >= first && index <= last + 1) {
            return starts[index - first];
        }
        if (index < first) {
            double at = 0;
            for (int i = first - 1; i >= index && first - i < 4096; i--) {
                at -= size.applyAsDouble(i);
            }
            return at;
        }
        double at = length();
        for (int i = last + 1; i < index && i - last < 4096; i++) {
            at += size.applyAsDouble(i);
        }
        return at;
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
