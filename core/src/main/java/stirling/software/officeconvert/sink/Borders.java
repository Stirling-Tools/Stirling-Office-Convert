package stirling.software.officeconvert.sink;

public final class Borders {

    private Borders() {}

    public static float width(float pt) {
        return Math.max(2, Math.min(96, Math.round(pt * 8f))) / 8f;
    }
}
