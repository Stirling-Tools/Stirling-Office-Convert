package stirling.software.officeconvert.optimize;

final class Scratch {

    char[] pairs = new char[0];

    int[] start = new int[0];

    double[] cost = new double[0];

    char[] length = new char[0];

    char[] dist = new char[0];

    char[] same = new char[0];

    void fit(int n) {
        if (cost.length < n) {
            cost = new double[n];
            length = new char[n];
            dist = new char[n];
        }
    }
}
