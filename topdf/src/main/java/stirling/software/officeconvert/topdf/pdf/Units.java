package stirling.software.officeconvert.topdf.pdf;

public final class Units {

    public static final long EMU_PER_INCH = 914_400;

    public static final long EMU_PER_POINT = 12_700;

    public static final int TWIPS_PER_POINT = 20;

    public static final float SCREEN_DPI = 96f;

    private Units() {}

    public static float emu(long emu) {
        return emu / (float) EMU_PER_POINT;
    }

    public static float twips(long twips) {
        return twips / (float) TWIPS_PER_POINT;
    }

    public static float halfPoints(long halfPoints) {
        return halfPoints / 2f;
    }

    public static float eighthPoints(long eighths) {
        return eighths / 8f;
    }

    public static float hundredthsOfPoint(long hundredths) {
        return hundredths / 100f;
    }

    public static float inches(double inches) {
        return (float) (inches * 72);
    }

    public static float millimetres(double mm) {
        return (float) (mm * 72 / 25.4);
    }

    public static float pixels(double px) {
        return (float) (px * 72 / SCREEN_DPI);
    }

    public static float angle(long sixtyThousandths) {
        return sixtyThousandths / 60_000f;
    }

    public static float fraction(long thousandthsOfPercent) {
        return thousandthsOfPercent / 100_000f;
    }
}
