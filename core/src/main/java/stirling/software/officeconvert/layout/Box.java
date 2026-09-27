package stirling.software.officeconvert.layout;

public record Box(float x, float top, float right, float bottom) {

    public float width() {
        return right - x;
    }

    public float height() {
        return bottom - top;
    }

    public float area() {
        return Math.max(0, width()) * Math.max(0, height());
    }

    public float centreX() {
        return (x + right) / 2f;
    }

    public float centreY() {
        return (top + bottom) / 2f;
    }

    public boolean contains(float px, float py) {
        return px >= x && px <= right && py >= top && py <= bottom;
    }

    public Box union(Box o) {
        return new Box(
                Math.min(x, o.x), Math.min(top, o.top), Math.max(right, o.right), Math.max(bottom, o.bottom));
    }

    public float overlapArea(Box o) {
        float w = Math.min(right, o.right) - Math.max(x, o.x);
        float h = Math.min(bottom, o.bottom) - Math.max(top, o.top);
        return w > 0 && h > 0 ? w * h : 0;
    }

    public boolean near(Box o, float gap) {
        return o.x <= right + gap && o.right >= x - gap && o.top <= bottom + gap && o.bottom >= top - gap;
    }

    public Box grow(float d) {
        return new Box(x - d, top - d, right + d, bottom + d);
    }
}
