package stirling.software.officeconvert.slides;

public record Frame(float x, float y, float width, float height, int rotation) {

    public Frame(float x, float y, float width, float height) {
        this(x, y, width, height, 0);
    }

    public float right() {
        return x + width;
    }

    public float bottom() {
        return y + height;
    }

    public float area() {
        return Math.max(0, width) * Math.max(0, height);
    }
}
