package stirling.software.officeconvert.jpx;

final class Band {

    static final int LL = 0;
    static final int HL = 1;
    static final int LH = 2;
    static final int HH = 3;

    final int orient;

    final int x0;

    final int y0;

    final int x1;

    final int y1;

    final int blockW;

    final int blockH;

    final int magnitudeBits;

    final float step;

    Band(int orient, int x0, int y0, int x1, int y1, int blockW, int blockH, int magnitudeBits, float step) {
        this.orient = orient;
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
        this.blockW = blockW;
        this.blockH = blockH;
        this.magnitudeBits = magnitudeBits;
        this.step = step;
    }

    int width() {
        return x1 - x0;
    }

    int height() {
        return y1 - y0;
    }
}
