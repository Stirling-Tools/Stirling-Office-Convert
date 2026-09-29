package stirling.software.officeconvert.topdf.io;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.awt.image.RenderedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.util.IdentityHashMap;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.hemf.usermodel.HemfPicture;
import org.apache.poi.hwmf.usermodel.HwmfPicture;

import de.rototor.pdfbox.graphics2d.IPdfBoxGraphics2DImageEncoder;
import de.rototor.pdfbox.graphics2d.PdfBoxGraphics2D;

final class Metafiles {

    static final long MAX_DRAW_CALLS = 2_000_000;

    private static final float MAX_SIDE = 14_400f;

    private static final float RASTER_DPI = 150f;

    private static final long RASTER_PIXELS = 8_000_000;

    private Metafiles() {}

    @FunctionalInterface
    private interface Painter {
        void paint(Graphics2D g, Rectangle2D bounds);
    }

    static DecodedPicture render(PDDocument doc, byte[] data, PictureDecoder.Kind kind) throws IOException {
        if (data.length > PictureDecoder.MAX_METAFILE_BYTES) {
            throw new IOException("The metafile is too large: " + data.length + " bytes");
        }
        MetafileGuard.check(data, kind == PictureDecoder.Kind.EMF, PictureDecoder.DECODE_PIXELS,
                2 * PictureDecoder.DECODE_PIXELS);
        Painter painter;
        Rectangle2D bounds;
        try {
            if (kind == PictureDecoder.Kind.EMF) {
                HemfPicture emf = new HemfPicture(new ByteArrayInputStream(data));
                bounds = emf.getBoundsInPoints();
                painter = emf::draw;
            } else {
                HwmfPicture wmf = new HwmfPicture(new ByteArrayInputStream(data));
                bounds = wmf.getBoundsInPoints();
                painter = wmf::draw;
            }
        } catch (RuntimeException e) {
            throw new IOException("The metafile could not be read: " + e.getMessage(), e);
        }
        float w = side(bounds.getWidth());
        float h = side(bounds.getHeight());
        stopIfInterrupted();
        try {
            return vector(doc, kind, painter, w, h);
        } catch (Stop e) {
            throw e.io();
        } catch (IOException | RuntimeException e) {
            stopIfInterrupted();
        } catch (StackOverflowError e) {
            throw new IOException("The metafile nests too deeply to draw", e);
        } catch (OutOfMemoryError e) {
            throw new IOException("The metafile needs too much memory to draw", e);
        }
        try {
            return raster(doc, kind, painter, w, h);
        } catch (Stop e) {
            throw e.io();
        } catch (RuntimeException e) {
            throw new IOException("The metafile could not be drawn: " + e.getMessage(), e);
        } catch (StackOverflowError e) {
            throw new IOException("The metafile nests too deeply to draw", e);
        } catch (OutOfMemoryError e) {
            throw new IOException("The metafile needs too much memory to draw", e);
        }
    }

    private static DecodedPicture vector(PDDocument doc, PictureDecoder.Kind kind, Painter painter, float w, float h)
            throws IOException {
        Checked g = new Checked(doc, w, h);
        try {
            SafeImageRenderer.install(g);
            painter.paint(g, new Rectangle2D.Double(0, 0, w, h));
        } finally {
            g.dispose();
        }
        if (g.stopped != null) {
            throw g.stopped;
        }
        return DecodedPicture.vector(kind, g.getXFormObject(), w, h);
    }

    private static DecodedPicture raster(PDDocument doc, PictureDecoder.Kind kind, Painter painter, float w, float h)
            throws IOException {
        double scale = Math.min(RASTER_DPI / 72f, Math.sqrt(RASTER_PIXELS / ((double) w * h)));
        int pw = Math.max(1, (int) Math.round(w * scale));
        int ph = Math.max(1, (int) Math.round(h * scale));
        BufferedImage img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            SafeImageRenderer.install(g);
            painter.paint(g, new Rectangle2D.Double(0, 0, pw, ph));
        } finally {
            g.dispose();
        }
        stopIfInterrupted();
        PDImageXObject image = FlateImages.encode(doc, img);
        return DecodedPicture.raster(kind, image, w, h);
    }

    private static float side(double v) throws IOException {
        if (!(v > 0) || Double.isInfinite(v)) {
            throw new IOException("The metafile has no drawable size");
        }
        return (float) Math.max(1, Math.min(MAX_SIDE, v));
    }

    private static void stopIfInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Conversion interrupted");
        }
    }

    private static final class Stop extends RuntimeException {

        private final boolean interrupted;

        Stop(boolean interrupted) {
            super(interrupted ? "Conversion interrupted" : "The metafile draws too much", null, false, false);
            this.interrupted = interrupted;
        }

        IOException io() {
            return interrupted ? new InterruptedIOException(getMessage()) : new IOException(getMessage());
        }
    }

    // Bitmaps inside a metafile go through FlateImages too, each one encoded once
    private static final class Bitmaps implements IPdfBoxGraphics2DImageEncoder {

        private final Map<Image, PDImageXObject> done = new IdentityHashMap<>();

        @Override
        public PDImageXObject encodeImage(PDDocument doc, PDPageContentStream stream, Image image,
                IPdfBoxGraphics2DImageEncoderEnv env) {
            PDImageXObject known = done.get(image);
            if (known != null) {
                return known;
            }
            try {
                PDImageXObject x = FlateImages.encode(doc, buffered(image));
                done.put(image, x);
                return x;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static BufferedImage buffered(Image image) {
            if (image instanceof BufferedImage b) {
                return b;
            }
            int w = Math.max(1, image.getWidth(null));
            int h = Math.max(1, image.getHeight(null));
            BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = b.createGraphics();
            try {
                g.drawImage(image, 0, 0, null);
            } finally {
                g.dispose();
            }
            return b;
        }
    }

    private static final class Checked extends PdfBoxGraphics2D {

        private long calls;

        private Stop stopped;

        Checked(PDDocument doc, float w, float h) throws IOException {
            super(doc, w, h);
            setImageEncoder(new Bitmaps());
        }

        private void tick() {
            if (stopped == null && Thread.currentThread().isInterrupted()) {
                stopped = new Stop(true);
            } else if (stopped == null && ++calls > MAX_DRAW_CALLS) {
                stopped = new Stop(false);
            }
            if (stopped != null) {
                throw stopped;
            }
        }

        @Override
        public void draw(Shape s) {
            tick();
            super.draw(s);
        }

        @Override
        public void fill(Shape s) {
            tick();
            super.fill(s);
        }

        @Override
        public boolean drawImage(Image img, AffineTransform xform, ImageObserver obs) {
            tick();
            return super.drawImage(img, xform, obs);
        }

        @Override
        public boolean drawImage(Image img, int x, int y, ImageObserver observer) {
            tick();
            return super.drawImage(img, x, y, observer);
        }

        @Override
        public boolean drawImage(Image img, int x, int y, int width, int height, ImageObserver observer) {
            tick();
            return super.drawImage(img, x, y, width, height, observer);
        }

        @Override
        public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2,
                ImageObserver observer) {
            tick();
            return super.drawImage(img, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, observer);
        }

        @Override
        public void drawRenderedImage(RenderedImage img, AffineTransform xform) {
            tick();
            super.drawRenderedImage(img, xform);
        }

        @Override
        public void drawString(String str, float x, float y) {
            tick();
            super.drawString(str, x, y);
        }

        @Override
        public void drawGlyphVector(GlyphVector g, float x, float y) {
            tick();
            super.drawGlyphVector(g, x, y);
        }
    }
}
