package stirling.software.officeconvert.topdf.pdf;

public record PageSize(float width, float height) {

    public static final float MAX_SIDE = 14_400f;

    public static final PageSize LETTER = new PageSize(612, 792);

    public static final PageSize LEGAL = new PageSize(612, 1008);

    public static final PageSize TABLOID = new PageSize(792, 1224);

    public static final PageSize EXECUTIVE = new PageSize(522, 756);

    public static final PageSize A3 = new PageSize(841.89f, 1190.55f);

    public static final PageSize A4 = new PageSize(595.28f, 841.89f);

    public static final PageSize A5 = new PageSize(419.53f, 595.28f);

    public PageSize {
        if (!(width >= 1 && width <= MAX_SIDE && height >= 1 && height <= MAX_SIDE)) {
            throw new IllegalArgumentException("A page side must be 1 to " + MAX_SIDE + " pt, was " + width + "x" + height);
        }
    }

    public boolean landscape() {
        return width > height;
    }

    public PageSize toLandscape() {
        return width >= height ? this : new PageSize(height, width);
    }

    public PageSize toPortrait() {
        return height >= width ? this : new PageSize(height, width);
    }
}
