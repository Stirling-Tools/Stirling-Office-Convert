package stirling.software.officeconvert.jpx;

record ColourSpec(int method, int approx, int enumerated, byte[] icc, long[] lab) {

    static final int SRGB = 16;
    static final int GREY = 17;
    static final int SYCC = 18;
    static final int CMYK = 12;
    static final int YCCK = 13;
    static final int LAB = 14;
    static final int ESRGB = 20;
    static final int ROMM = 21;
    static final int ESYCC = 24;

    static ColourSpec read(Bytes b) throws JpxException {
        int method = b.u8();
        b.u8();
        int approx = b.u8();
        if (method == 1) {
            int space = (int) b.u32();
            long[] lab = null;
            if (space == LAB && b.remaining() >= 24) {
                lab = new long[6];
                for (int i = 0; i < 6; i++) {
                    lab[i] = b.u32();
                }
            }
            return new ColourSpec(method, approx, space, null, lab);
        }
        if (method == 2 || method == 3) {
            byte[] icc = new byte[b.remaining()];
            for (int i = 0; i < icc.length; i++) {
                icc[i] = (byte) b.u8();
            }
            return new ColourSpec(method, approx, -1, icc, null);
        }
        return new ColourSpec(method, approx, -1, null, null);
    }
}
