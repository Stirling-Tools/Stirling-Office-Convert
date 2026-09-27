package stirling.software.officeconvert.model;

public final class Picture {

    public enum Wrap {
        INLINE,
        SQUARE,
        TOP_BOTTOM,
        NONE
    }

    public final MediaRef media;
    public float width;
    public float height;
    public Wrap wrap = Wrap.INLINE;
    public float x;
    public float y;
    public boolean behind;
    public boolean backdrop;
    public int paintOrder = -1;
    public int z;
    public boolean fromParagraph;
    public float wrapGap;
    public float cropLeft;
    public float cropTop;
    public float cropRight;
    public float cropBottom;
    public int rotation;
    public boolean flipH;
    public String description = "";
    public float baselineShift;

    public Picture(MediaRef media, float width, float height) {
        this.media = media;
        this.width = width;
        this.height = height;
    }

    public record MediaRef(String name, String contentType, int pixelWidth, int pixelHeight) {}
}
