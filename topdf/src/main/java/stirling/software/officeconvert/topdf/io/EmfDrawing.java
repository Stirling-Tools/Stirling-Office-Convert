package stirling.software.officeconvert.topdf.io;

import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Dimension2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.apache.poi.hemf.draw.HemfGraphics;
import org.apache.poi.hemf.record.emf.HemfRecord;
import org.apache.poi.hemf.usermodel.HemfPicture;
import org.apache.poi.hwmf.record.HwmfFont;
import org.apache.poi.hwmf.record.HwmfText;
import org.apache.poi.sl.draw.Drawable;

final class EmfDrawing {

    private EmfDrawing() {}

    static void draw(HemfPicture picture, Graphics2D g, Rectangle2D target) {
        Shape clip = g.getClip();
        AffineTransform transform = g.getTransform();
        try {
            Rectangle2D bounds = bounds(picture, g);
            g.translate(target.getCenterX(), target.getCenterY());
            g.scale(target.getWidth() / bounds.getWidth(), target.getHeight() / bounds.getHeight());
            g.translate(-bounds.getCenterX(), -bounds.getCenterY());
            HemfGraphics context = new Spaced(g, bounds);
            for (HemfRecord record : picture.getRecords()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("Conversion interrupted");
                }
                context.draw(record);
            }
        } finally {
            g.setTransform(transform);
            g.setClip(clip);
        }
    }

    private static Rectangle2D bounds(HemfPicture picture, Graphics2D g) {
        Rectangle2D header = picture.getHeader().getBoundsRectangle();
        if (Boolean.TRUE.equals(g.getRenderingHint(Drawable.EMF_FORCE_HEADER_BOUNDS))) {
            return header;
        }
        Rectangle2D window = new FirstBounds();
        Rectangle2D viewport = new FirstBounds();
        Rectangle2D drawing = new Rectangle2D.Double();
        picture.getInnerBounds(window, viewport, drawing);
        if (drawing.isEmpty()) {
            return !viewport.isEmpty() ? viewport : !window.isEmpty() ? window : header;
        }
        return Stream.of(header, window, viewport).min(Comparator.comparingDouble(b -> difference(b, drawing)))
                .orElse(header);
    }

    private static double difference(Rectangle2D a, Rectangle2D b) {
        return java.awt.geom.Point2D.distanceSq(a.getMinX(), a.getMinY(), b.getMinX(), b.getMinY())
                + java.awt.geom.Point2D.distanceSq(a.getMinX(), a.getMaxY(), b.getMinX(), b.getMaxY())
                + java.awt.geom.Point2D.distanceSq(a.getMaxX(), a.getMinY(), b.getMaxX(), b.getMinY())
                + java.awt.geom.Point2D.distanceSq(a.getMaxX(), a.getMaxY(), b.getMaxX(), b.getMaxY());
    }

    private static final class FirstBounds extends Rectangle2D.Double {
        private boolean offset;
        private boolean range;

        FirstBounds() {
            super(-1, -1, 0, 0);
        }

        @Override
        public void setRect(double x, double y, double w, double h) {
            if (offset && range) {
                return;
            }
            super.setRect(offset ? this.x : x, offset ? this.y : y,
                    range ? width : w, range ? height : h);
            offset |= x != -1 || y != -1;
            range |= w != 0 || h != 0;
        }

        @Override
        public boolean isEmpty() {
            double w = Math.rint(width);
            double h = Math.rint(height);
            return w <= 0 || h <= 0 || x == -1 && y == -1 || w == 1 && h == 1;
        }
    }

    private static final class Spaced extends HemfGraphics {

        Spaced(Graphics2D g, Rectangle2D bounds) {
            super(g, bounds);
        }

        @Override
        public void drawString(byte[] text, int length, Point2D reference, Dimension2D scale, Rectangle2D clip,
                HwmfText.WmfExtTextOutOptions opts, List<Integer> dx, boolean isUnicode) {
            int n = text == null ? 0 : Math.min(length, text.length / 2);
            if (!placeable(text, n, reference, opts, dx, isUnicode)) {
                super.drawString(text, length, reference, scale, clip, opts, dx, isUnicode);
                return;
            }
            double offset = 0;
            int start = 0;
            for (int i = 0; i < n; i++) {
                if (i + 1 < n && (unit(text, i) != ' ' || unit(text, i + 1) == ' ')) {
                    continue;
                }
                Point2D at = new Point2D.Double(reference.getX() + offset, reference.getY());
                super.drawString(Arrays.copyOfRange(text, 2 * start, 2 * (i + 1)), i + 1 - start, at, scale, clip,
                        opts, dx.subList(start, i + 1), true);
                for (int k = start; k <= i; k++) {
                    offset += dx.get(k);
                }
                start = i + 1;
            }
        }

        private boolean placeable(byte[] text, int n, Point2D reference, HwmfText.WmfExtTextOutOptions opts,
                List<Integer> dx, boolean isUnicode) {
            HwmfFont font = getProperties().getFont();
            if (!isUnicode || n < 2 || dx == null || dx.size() < n || reference.distance(0, 0) == 0 || font == null
                    || font.getEscapement() != 0 || opts != null && (opts.isYDisplaced() || opts.isOpaque())
                    || getProperties().getTextAlignLatin() != HwmfText.HwmfTextAlignment.LEFT
                    || graphicsCtx.getTransform().getScaleX() < 0) {
                return false;
            }
            for (int i = 0; i < n; i++) {
                if (Character.isSurrogate(unit(text, i))) {
                    return false;
                }
            }
            return true;
        }

        private static char unit(byte[] text, int i) {
            return (char) (text[2 * i] & 0xFF | (text[2 * i + 1] & 0xFF) << 8);
        }
    }
}
