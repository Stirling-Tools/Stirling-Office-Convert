package stirling.software.officeconvert.lineart;

final class Tint {

    private final boolean[] rows;

    private boolean any;

    Tint(int height) {
        this.rows = new boolean[height];
    }

    void mark(int y) {
        rows[y] = true;
        any = true;
    }

    boolean row(int y) {
        return rows[y];
    }

    boolean any() {
        return any;
    }
}
