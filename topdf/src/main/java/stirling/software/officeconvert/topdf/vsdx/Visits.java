package stirling.software.officeconvert.topdf.vsdx;

final class Visits {

    private long left;

    Visits(long left) {
        this.left = left;
    }

    boolean take() {
        return left-- > 0;
    }
}
