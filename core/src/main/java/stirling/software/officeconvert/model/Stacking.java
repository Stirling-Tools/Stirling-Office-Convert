package stirling.software.officeconvert.model;

public final class Stacking {

    public static final int LIMIT = 0x1E000000;
    public static final int BACKDROP = 252_000_000;
    public static final int STACKED = 260_000_000;
    public static final int PICTURES = 460_000_000;
    public static final int BOXES = 480_000_000;
    public static final float CORNER = 6f;

    private Stacking() {}

    public static int stacked(int step) {
        return STACKED + Math.min(Math.max(0, step), PICTURES - STACKED - 1);
    }

    public static int shape(Inline.Shape s, int seq) {
        return s.z() > 0 ? s.z() : stacked(seq);
    }

    public static int picture(Picture p, int seq) {
        if (p.z > 0) {
            return p.z;
        }
        return p.backdrop ? Math.min(BACKDROP + seq, STACKED - 1) : Math.min(PICTURES + seq, BOXES - 1);
    }

    public static int box(int seq) {
        return Math.min(BOXES + seq, LIMIT);
    }
}
