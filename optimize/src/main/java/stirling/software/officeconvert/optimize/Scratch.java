package stirling.software.officeconvert.optimize;

final class Scratch {

    int[] pairs = new int[0];

    int[] start = new int[0];

    double[] cost = new double[0];

    int[] length = new int[0];

    int[] dist = new int[0];

    char[] same = new char[0];

    void fit(int n) {
        if (cost.length < n) {
            cost = new double[n];
            length = new int[n];
            dist = new int[n];
        }
    }
}
