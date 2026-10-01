package stirling.software.officeconvert.topdf.io;

import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.geom.Dimension2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import org.apache.poi.sl.draw.ImageRenderer;

final class PictRenderer implements ImageRenderer {

    private final Pict pict;

    PictRenderer(Pict pict) {
        this.pict = pict;
    }

    @Override
    public boolean canRender(String contentType) {
        return true;
    }

    @Override
    public void loadImage(InputStream data, String contentType) throws IOException {
        throw new IOException("A decoded picture cannot be reloaded");
    }

    @Override
    public void loadImage(byte[] data, String contentType) throws IOException {
        throw new IOException("A decoded picture cannot be reloaded");
    }

    @Override
    public Rectangle2D getNativeBounds() {
        return pict.bounds();
    }

    @Override
    public Rectangle2D getBounds() {
        return pict.bounds();
    }

    @Override
    public void setAlpha(double alpha) {}

    @Override
    public BufferedImage getImage() {
        return null;
    }

    @Override
    public BufferedImage getImage(Dimension2D dimension) {
        return null;
    }

    @Override
    public boolean drawImage(Graphics2D graphics, Rectangle2D anchor) {
        pict.draw(graphics, anchor);
        return true;
    }

    @Override
    public boolean drawImage(Graphics2D graphics, Rectangle2D anchor, Insets clip) {
        pict.draw(graphics, anchor);
        return true;
    }
}
