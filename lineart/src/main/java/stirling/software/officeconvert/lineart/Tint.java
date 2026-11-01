package stirling.software.officeconvert.lineart;

final class Tint {

    static final int BLOCK = 8;

    private final int words;

    private final long[] blocks;

    private final boolean[] rows;

    private boolean any;

    Tint(int width, int height) {
        this.words = Math.ceilDiv(Math.ceilDiv(width, BLOCK), 64);
        this.blocks = new long[Math.multiplyExact(words, height)];
        this.rows = new boolean[height];
    }

    void mark(int y, int block) {
        blocks[y * words + (block >>> 6)] |= 1L << block;
        rows[y] = true;
        any = true;
    }

    boolean row(int y) {
        return rows[y];
    }

    boolean block(int y, int block) {
        return (blocks[y * words + (block >>> 6)] & 1L << block) != 0;
    }

    long word(int y, int index) {
        return blocks[y * words + index];
    }

    int words() {
        return words;
    }

    boolean any() {
        return any;
    }
}
