package stirling.software.officeconvert.topdf.pdf;

public record Crop(float left, float top, float right, float bottom) {

    public static final Crop NONE = new Crop(0, 0, 0, 0);

    public Crop {
        if (!Float.isFinite(left) || !Float.isFinite(top) || !Float.isFinite(right) || !Float.isFinite(bottom)) {
            throw new IllegalArgumentException("Crop fractions must be finite");
        }
        if (!(left + right < 1 && top + bottom < 1)) {
            throw new IllegalArgumentException("A crop must leave part of the picture: " + left + ", " + top + ", "
                    + right + ", " + bottom);
        }
    }

    public static Crop fractions(float left, float top, float right, float bottom) {
        return new Crop(left, top, right, bottom);
    }

    public boolean clips() {
        return left > 0 || top > 0 || right > 0 || bottom > 0;
    }
}
