package stirling.software.officeconvert.sink;

public final class WrapGap {

    private static final float SLACK = 0.5f;

    private WrapGap() {}

    public static float clear(float gap) {
        return Math.max(0, gap - SLACK);
    }
}
