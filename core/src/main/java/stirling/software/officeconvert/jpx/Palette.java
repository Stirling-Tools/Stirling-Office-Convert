package stirling.software.officeconvert.jpx;

record Palette(int entries, int[] depth, int[][] values) {

    static Palette read(Bytes b) throws JpxException {
        int entries = b.u16();
        int columns = b.u8();
        if (entries < 1 || entries > 1024 || columns < 1) {
            throw new JpxException("Invalid JPEG 2000 palette");
        }
        int[] depth = new int[columns];
        for (int i = 0; i < columns; i++) {
            depth[i] = Math.min(16, (b.u8() & 0x7F) + 1);
        }
        int[][] values = new int[columns][entries];
        for (int e = 0; e < entries; e++) {
            for (int i = 0; i < columns; i++) {
                int bytes = (depth[i] + 7) / 8;
                int v = 0;
                for (int k = 0; k < bytes; k++) {
                    v = v << 8 | b.u8();
                }
                values[i][e] = v & (1 << depth[i]) - 1;
            }
        }
        return new Palette(entries, depth, values);
    }
}
