package stirling.software.officeconvert.topdf.vsdx;

final class Visits {

    private long left;

    Visits(long left) {
        this.left = left;
    }

    boolean take() {
        if (spent()) {
            return false;
        }
        left--;
        return true;
    }

    boolean spent() {
        return left == 0;
    }
}
