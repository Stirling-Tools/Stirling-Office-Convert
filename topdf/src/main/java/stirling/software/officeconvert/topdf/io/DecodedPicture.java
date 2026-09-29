package stirling.software.officeconvert.topdf.io;

import java.util.Objects;

import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

public final class DecodedPicture {

    private final PictureDecoder.Kind kind;

    private final PDImageXObject image;

    private final PDFormXObject form;

    private final float naturalWidth;

    private final float naturalHeight;

    private DecodedPicture(PictureDecoder.Kind kind, PDImageXObject image, PDFormXObject form, float naturalWidth,
            float naturalHeight) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.image = image;
        this.form = form;
        this.naturalWidth = naturalWidth;
        this.naturalHeight = naturalHeight;
    }

    static DecodedPicture raster(PictureDecoder.Kind kind, PDImageXObject image, float naturalWidth,
            float naturalHeight) {
        return new DecodedPicture(kind, Objects.requireNonNull(image, "image"), null, naturalWidth, naturalHeight);
    }

    static DecodedPicture vector(PictureDecoder.Kind kind, PDFormXObject form, float naturalWidth,
            float naturalHeight) {
        return new DecodedPicture(kind, null, Objects.requireNonNull(form, "form"), naturalWidth, naturalHeight);
    }

    public PictureDecoder.Kind kind() {
        return kind;
    }

    public boolean vector() {
        return form != null;
    }

    public PDImageXObject image() {
        return image;
    }

    public PDFormXObject form() {
        return form;
    }

    public int pixelWidth() {
        return image == null ? 0 : image.getWidth();
    }

    public int pixelHeight() {
        return image == null ? 0 : image.getHeight();
    }

    public float naturalWidth() {
        return naturalWidth;
    }

    public float naturalHeight() {
        return naturalHeight;
    }

    @Override
    public String toString() {
        return "DecodedPicture[" + kind + (vector() ? ", vector" : ", " + pixelWidth() + "x" + pixelHeight() + " px")
                + ", " + naturalWidth + "x" + naturalHeight + " pt]";
    }
}
