package stirling.software.officeconvert.jpx;

record ImageGrid(int x0, int y0, int width, int height) {

    static ImageGrid of(Siz siz, int reduce) {
        boolean uniform = siz.uniform();
        int dx = uniform ? siz.dx()[0] : 1;
        int dy = uniform ? siz.dy()[0] : 1;
        long x0 = Siz.ceilShift(Siz.ceilDiv(siz.x0(), dx), reduce);
        long y0 = Siz.ceilShift(Siz.ceilDiv(siz.y0(), dy), reduce);
        long x1 = Siz.ceilShift(Siz.ceilDiv(siz.width(), dx), reduce);
        long y1 = Siz.ceilShift(Siz.ceilDiv(siz.height(), dy), reduce);
        return new ImageGrid((int) x0, (int) y0, (int) (x1 - x0), (int) (y1 - y0));
    }
}
