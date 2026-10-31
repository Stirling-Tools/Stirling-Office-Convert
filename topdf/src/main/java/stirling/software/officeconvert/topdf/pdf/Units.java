package stirling.software.officeconvert.topdf.pdf;

public final class Units {

    public static final long EMU_PER_POINT = 12_700;

    private Units() {}

    public static float emu(long emu) {
        return emu / (float) EMU_PER_POINT;
    }
}
