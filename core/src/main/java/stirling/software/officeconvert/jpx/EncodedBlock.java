package stirling.software.officeconvert.jpx;

import java.util.Arrays;

final class EncodedBlock {

    final int planes;

    final byte[] data;

    final int[] lengths;

    final double[] gains;

    double initial;

    int[] hull;

    double[] slopes;

    int[] layerPasses;

    int zeroPlanes;

    EncodedBlock(int planes, byte[] data, int[] lengths, double[] gains, double initial) {
        this.planes = planes;
        this.data = data;
        this.lengths = lengths;
        this.gains = gains;
        this.initial = initial;
    }

    int passes() {
        return lengths.length;
    }

    int length(int passes) {
        return passes == 0 ? 0 : lengths[passes - 1];
    }

    double gain(int passes) {
        return passes == 0 ? 0 : gains[passes - 1];
    }

    void weigh(double weight) {
        initial *= weight;
        for (int i = 0; i < gains.length; i++) {
            gains[i] *= weight;
        }
        int n = lengths.length;
        int[] points = new int[n + 1];
        double[] s = new double[n + 1];
        int count = 0;
        int lastLength = 0;
        double lastGain = 0;
        for (int p = 1; p <= n; p++) {
            double dg = gains[p - 1] - lastGain;
            int dl = lengths[p - 1] - lastLength;
            if (dg <= 0) {
                continue;
            }
            double slope = dl == 0 ? Double.POSITIVE_INFINITY : dg / dl;
            while (count > 0 && slope >= s[count - 1]) {
                count--;
                int prev = count == 0 ? 0 : points[count - 1];
                lastLength = length(prev);
                lastGain = gain(prev);
                dg = gains[p - 1] - lastGain;
                dl = lengths[p - 1] - lastLength;
                slope = dl == 0 ? Double.POSITIVE_INFINITY : dg / dl;
            }
            points[count] = p;
            s[count] = slope;
            count++;
            lastLength = lengths[p - 1];
            lastGain = gains[p - 1];
        }
        hull = Arrays.copyOf(points, count);
        slopes = Arrays.copyOf(s, count);
    }

    int passesAt(double lambda) {
        int lo = 0;
        int hi = slopes.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (slopes[mid] > lambda) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo == 0 ? 0 : hull[lo - 1];
    }
}
