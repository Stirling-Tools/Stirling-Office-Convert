package stirling.software.officeconvert.build;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.IconShape;
import stirling.software.officeconvert.model.Picture;

final class IconPictures {

    private static final float SCALE = 600f / 72f;

    private final DocSink sink;

    IconPictures(DocSink sink) {
        this.sink = sink;
    }

    Picture picture(Glyph g, float advance) {
        IconShape icon = g.icon;
        float top = -g.ascent;
        float bottom = g.descent;
        float width = Math.max(Math.max(g.width, Math.min(advance, 2 * g.width)), 0.5f);
        Rectangle2D b = icon.outline().getBounds2D();
        top = Math.min(top, (float) b.getMinY());
        bottom = Math.max(bottom, (float) b.getMaxY());
        float height = bottom - top;
        if (!(height > 0) || !(width > 0)) {
            return null;
        }
        try {
            String key = icon.key() + "|" + Math.round(width * 4);
            Picture.MediaRef ref = sink.media(key);
            if (ref == null) {
                int pw = Math.max(1, Math.round(width * SCALE));
                int ph = Math.max(1, Math.round(height * SCALE));
                BufferedImage img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
                Graphics2D gr = img.createGraphics();
                try {
                    gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    gr.setColor(new Color(icon.rgb()));
                    AffineTransform t = AffineTransform.getScaleInstance(SCALE, SCALE);
                    t.translate(0, -top);
                    gr.fill(t.createTransformedShape(icon.outline()));
                } finally {
                    gr.dispose();
                }
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                ImageIO.write(img, "png", png);
                ref = sink.media(png.toByteArray(), "png", pw, ph, key);
            }
            Picture pic = new Picture(ref, width, height);
            pic.baselineShift = -bottom;
            return pic;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
