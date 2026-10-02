package stirling.software.officeconvert.topdf.rtf;

final class Budget {

    private final long limit;

    private long used;

    private boolean spent;

    Budget(long limit) {
        this.limit = limit;
    }

    void charge(long chars) {
        used += chars;
        if (used > limit && !spent) {
            spent = true;
            throw new RtfPackage.TooLarge();
        }
    }

    boolean spent() {
        return spent;
    }
}
