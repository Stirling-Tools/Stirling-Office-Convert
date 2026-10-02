package stirling.software.officeconvert.jpx;

record CodingDefaults(boolean sop, boolean eph, int order, int layers, boolean mct, ComponentStyle style) {

    static CodingDefaults read(Bytes b) throws JpxException {
        int flags = b.u8();
        int order = b.u8();
        int layers = b.u16();
        int mct = b.u8();
        if (order > 4 || layers == 0) {
            throw new JpxException("Invalid JPEG 2000 coding defaults");
        }
        ComponentStyle style = ComponentStyle.read(b, (flags & 1) != 0);
        return new CodingDefaults((flags & 2) != 0, (flags & 4) != 0, order, layers, mct != 0, style);
    }
}
