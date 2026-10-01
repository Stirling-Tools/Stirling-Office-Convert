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
import java.text.AttributedCharacterIterator;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.hemf.usermodel.HemfPicture;
import org.apache.poi.hwmf.draw.HwmfDrawProperties;
import org.apache.poi.hwmf.draw.HwmfGraphics;
import org.apache.poi.hwmf.draw.HwmfGraphicsState;
import org.apache.poi.hwmf.record.HwmfBrushStyle;
import org.apache.poi.hwmf.record.HwmfColorRef;
import org.apache.poi.hwmf.record.HwmfFill;
import org.apache.poi.hwmf.record.HwmfPenStyle;
import org.apache.poi.hwmf.record.HwmfRecord;
import org.apache.poi.hwmf.record.HwmfTernaryRasterOp;
import org.apache.poi.hwmf.record.HwmfText;
import org.apache.poi.hwmf.usermodel.HwmfPicture;
import org.apache.poi.sl.draw.DrawFactory;
import org.apache.poi.sl.draw.Drawable;
import org.apache.poi.sl.usermodel.Background;
import org.apache.poi.sl.usermodel.MasterSheet;
import org.apache.poi.sl.usermodel.Sheet;
import org.apache.poi.sl.usermodel.Slide;

import de.rototor.pdfbox.graphics2d.IPdfBoxGraphics2DFontTextDrawer;
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
                EmfFrames.Placement place = EmfFrames.of(emf.getHeader());
                if (place == null) {
                    bounds = emf.getBoundsInPoints();
                    painter = emf::draw;
                } else {
                    bounds = new Rectangle2D.Double(0, 0, place.widthPoints(), place.heightPoints());
                    painter = (g, r) -> {
                        g.setRenderingHint(Drawable.EMF_FORCE_HEADER_BOUNDS, true);
                        emf.draw(g, place.target(r));
                    };
                }
            } else {
                HwmfPicture wmf = new HwmfPicture(new ByteArrayInputStream(data));
                bounds = wmf.getBoundsInPoints();
                painter = (g, r) -> drawWmf(wmf, g, r);
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

    // POI's own WMF drawing, except that a pattern copy fills its rectangle with the brush as Windows does
    private static void drawWmf(HwmfPicture wmf, Graphics2D g, Rectangle2D target) {
        HwmfGraphicsState state = new HwmfGraphicsState();
        state.backup(g);
        try {
            Rectangle2D inner = wmf.getInnnerBounds();
            if (inner == null) {
                inner = wmf.getBounds();
            }
            g.translate(target.getCenterX(), target.getCenterY());
            g.scale(target.getWidth() / inner.getWidth(), target.getHeight() / inner.getHeight());
            g.translate(-inner.getCenterX(), -inner.getCenterY());
            HwmfGraphics ctx = new HwmfGraphics(g, inner);
            HwmfDrawProperties props = ctx.getProperties();
            props.setViewportOrg(inner.getX(), inner.getY());
            props.setViewportExt(inner.getWidth(), inner.getHeight());
            for (HwmfRecord r : wmf.getRecords()) {
                if (r instanceof HwmfFill.WmfPatBlt blt && blt.getRasterOperation() == HwmfTernaryRasterOp.PATCOPY) {
                    fillWith(ctx, blt.getBounds(), null);
                    continue;
                }
                if (r instanceof HwmfText.WmfExtTextOut text && text.getOptions().isOpaque()
                        && text.getBounds() != null && !text.getBounds().isEmpty()) {
                    fillWith(ctx, text.getBounds(), ctx.getProperties().getBackgroundColor());
                }
                r.draw(ctx);
            }
        } finally {
            state.restore(g);
        }
    }

    // A rectangle filled without its outline, with the current brush or else a solid colour
    private static void fillWith(HwmfGraphics ctx, Rectangle2D area, HwmfColorRef colour) {
        HwmfDrawProperties now = ctx.getProperties();
        HwmfPenStyle pen = now.getPenStyle();
        HwmfBrushStyle style = now.getBrushStyle();
        HwmfColorRef brush = now.getBrushColor();
        now.setPenStyle(HwmfPenStyle.valueOf(5));
        if (colour != null) {
            now.setBrushStyle(HwmfBrushStyle.BS_SOLID);
            now.setBrushColor(colour);
        }
        try {
            ctx.fill(area);
        } finally {
            now.setPenStyle(pen);
            now.setBrushStyle(style);
            now.setBrushColor(brush);
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

    static PDFormXObject sheet(PDDocument doc, Sheet<?, ?> sheet, float w, float h, IPdfBoxGraphics2DFontTextDrawer text)
            throws IOException {
        return sheetPart(doc, sheet, w, h, text, g -> SafeImageRenderer.draw(g, sheet));
    }

    static PDFormXObject shapes(PDDocument doc, Sheet<?, ?> sheet,
            List<? extends org.apache.poi.sl.usermodel.Shape<?, ?>> shapes, float w, float h,
            IPdfBoxGraphics2DFontTextDrawer text) throws IOException {
        return sheetPart(doc, sheet, w, h, text, g -> {
            for (org.apache.poi.sl.usermodel.Shape<?, ?> shape : shapes) {
                SafeImageRenderer.draw(g, shape);
            }
        });
    }

    // Background, master and each shape on a form of its own, so one that fails loses only itself
    static List<PDFormXObject> sheetParts(PDDocument doc, Sheet<?, ?> sheet, float w, float h,
            IPdfBoxGraphics2DFontTextDrawer text, Consumer<Throwable> skipped) throws IOException {
        List<Consumer<Graphics2D>> parts = new ArrayList<>();
        parts.add(g -> {
            Background<?, ?> bg = sheet.getBackground();
            if (bg != null) {
                DrawFactory.getInstance(g).getDrawable(bg).draw(g);
            }
        });
        parts.add(g -> {
            MasterSheet<?, ?> master = sheet.getMasterSheet();
            if (sheet.getFollowMasterGraphics() && master != null) {
                DrawFactory.getInstance(g).getDrawable(master).draw(g);
            }
        });
        try {
            for (org.apache.poi.sl.usermodel.Shape<?, ?> shape : sheet.getShapes()) {
                parts.add(g -> SafeImageRenderer.draw(g, shape));
            }
        } catch (RuntimeException e) {
            skipped.accept(e);
        }
        List<PDFormXObject> forms = new ArrayList<>();
        for (Consumer<Graphics2D> part : parts) {
            try {
                forms.add(sheetPart(doc, sheet, w, h, text, part));
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException | RuntimeException | StackOverflowError e) {
                stopIfInterrupted();
                skipped.accept(e);
            }
        }
        return forms;
    }

    private static PDFormXObject sheetPart(PDDocument doc, Sheet<?, ?> sheet, float w, float h,
            IPdfBoxGraphics2DFontTextDrawer text, Consumer<Graphics2D> painter) throws IOException {
        Checked g = new Checked(doc, w, h);
        g.countText = true;
        try {
            if (text != null) {
                g.setFontTextDrawer(text);
            }
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            SafeImageRenderer.install(g);
            if (sheet instanceof Slide<?, ?>) {
                g.setRenderingHint(Drawable.CURRENT_SLIDE, sheet);
            }
            painter.accept(g);
        } catch (Stop e) {
            throw e.io();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } finally {
            g.dispose();
        }
        if (g.stopped != null) {
            throw g.stopped.io();
        }
        return g.getXFormObject();
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

        private boolean countText;

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
        public void drawString(AttributedCharacterIterator iterator, float x, float y) {
            if (countText) {
                tick();
            }
            super.drawString(iterator, x, y);
        }

        @Override
        public void drawGlyphVector(GlyphVector g, float x, float y) {
            tick();
            super.drawGlyphVector(g, x, y);
        }
    }
}
