package stirling.software.officeconvert.topdf.io;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Dimension2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.poi.hemf.draw.HemfImageRenderer;
import org.apache.poi.hwmf.draw.HwmfImageRenderer;
import org.apache.poi.sl.draw.BitmapImageRenderer;
import org.apache.poi.sl.draw.DrawFactory;
import org.apache.poi.sl.draw.Drawable;
import org.apache.poi.sl.draw.ImageRenderer;
import org.apache.poi.sl.usermodel.Shape;
import org.apache.poi.sl.usermodel.Sheet;

import de.rototor.pdfbox.graphics2d.IPdfBoxGraphics2DFontTextDrawer;

public final class SafeImageRenderer implements ImageRenderer {

    public static final int MAX_PICTURE_BYTES = 256 << 20;

    public static final long MAX_IMAGE_PIXELS = 4_000_000L;

    private static final int MAX_IMAGE_SIDE = 4096;

    private static final int MAX_NESTING = 2;

    private static final ThreadLocal<int[]> NESTING = ThreadLocal.withInitial(() -> new int[1]);

    private ImageRenderer delegate = new Nothing();

    private PictureDecoder.Kind kind = PictureDecoder.Kind.UNKNOWN;

    private double alpha;

    private BufferedImage drawn;

    public static void install(Graphics2D g) {
        Objects.requireNonNull(g, "graphics").setRenderingHint(Drawable.IMAGE_RENDERER, new SafeImageRenderer());
    }

    public static void draw(Graphics2D g, Sheet<?, ?> sheet) {
        Objects.requireNonNull(sheet, "sheet");
        install(g);
        sheet.draw(g);
    }

    public static void draw(Graphics2D g, Shape<?, ?> shape) {
        Objects.requireNonNull(shape, "shape");
        install(g);
        DrawFactory.getInstance(g).drawShape(g, shape, null);
    }

    /** A whole sheet (a legacy slide) drawn by POI into a form XObject of the given size, with this renderer, the
     * metafile draw-call budget and interrupts; {@code text} writes the text, or null to draw it as outlines. */
    public static PDFormXObject drawForm(PDDocument doc, Sheet<?, ?> sheet, float width, float height,
            IPdfBoxGraphics2DFontTextDrawer text) throws IOException {
        Objects.requireNonNull(doc, "doc");
        Objects.requireNonNull(sheet, "sheet");
        return Metafiles.sheet(doc, sheet, width, height, text);
    }

    /** The same sheet drawn again part by part, background, master and each shape on a form of its own, for when
     * {@link #drawForm} fails: a part that fails is left out and handed to {@code skipped}. */
    public static List<PDFormXObject> drawParts(PDDocument doc, Sheet<?, ?> sheet, float width, float height,
            IPdfBoxGraphics2DFontTextDrawer text, Consumer<Throwable> skipped) throws IOException {
        Objects.requireNonNull(doc, "doc");
        Objects.requireNonNull(sheet, "sheet");
        return Metafiles.sheetParts(doc, sheet, width, height, text, Objects.requireNonNull(skipped, "skipped"));
    }

    public PictureDecoder.Kind kind() {
        return kind;
    }

    // Claiming SVG too keeps POI away from its ServiceLoader, which would pick Batik; loadImage then refuses it
    @Override
    public boolean canRender(String contentType) {
        return true;
    }

    @Override
    public void loadImage(InputStream data, String contentType) throws IOException {
        byte[] bytes = data.readNBytes(MAX_PICTURE_BYTES + 1);
        if (bytes.length > MAX_PICTURE_BYTES) {
            throw new IOException("The picture is larger than " + (MAX_PICTURE_BYTES >> 20) + " MB");
        }
        loadImage(bytes, contentType);
    }

    @Override
    public void loadImage(byte[] data, String contentType) throws IOException {
        delegate = new Nothing();
        kind = PictureDecoder.Kind.UNKNOWN;
        drawn = null;
        byte[] bytes = PictureDecoder.gunzip(data);
        PictureDecoder.Kind k = PictureDecoder.sniff(bytes);
        ImageRenderer next;
        switch (k) {
            case PNG, JPEG, GIF, BMP, TIFF ->
                next = new Decoded(PictureDecoder.readRaster(bytes, PictureDecoder.DECODE_PIXELS));
            case EMF, WMF -> {
                if (bytes.length > PictureDecoder.MAX_METAFILE_BYTES) {
                    throw new IOException("The metafile is too large: " + bytes.length + " bytes");
                }
                MetafileGuard.check(bytes, k == PictureDecoder.Kind.EMF, PictureDecoder.DECODE_PIXELS,
                        2 * PictureDecoder.DECODE_PIXELS);
                next = k == PictureDecoder.Kind.EMF ? new HemfImageRenderer() : new HwmfImageRenderer();
                next.loadImage(bytes, contentType);
            }
            default -> {
                kind = k;
                // POI moves on to the next picture (the PNG Office keeps beside an SVG) only after an IOException
                throw new IOException(k == PictureDecoder.Kind.SVG ? "SVG pictures are never drawn"
                        : "The picture is in a format that cannot be read");
            }
        }
        delegate = next;
        kind = k;
    }

    @Override
    public Rectangle2D getNativeBounds() {
        return delegate.getNativeBounds();
    }

    @Override
    public Rectangle2D getBounds() {
        return delegate.getBounds();
    }

    @Override
    public Dimension2D getDimension() {
        return delegate.getDimension();
    }

    @Override
    public void setAlpha(double alpha) {
        this.alpha = alpha;
        drawn = null;
        delegate.setAlpha(alpha);
    }

    @Override
    public BufferedImage getImage() {
        return metafile() ? metafileImage(delegate.getDimension()) : delegate.getImage();
    }

    // POI rasterizes metafiles here (texture brushes) on a graphics without our hint, so draw them ourselves
    @Override
    public BufferedImage getImage(Dimension2D dimension) {
        if (metafile()) {
            return metafileImage(dimension);
        }
        int[] size = capped(dimension);
        return size == null ? blank() : delegate.getImage(new Dimension(size[0], size[1]));
    }

    private boolean metafile() {
        return kind == PictureDecoder.Kind.EMF || kind == PictureDecoder.Kind.WMF;
    }

    private BufferedImage metafileImage(Dimension2D dimension) {
        int[] size = capped(dimension);
        int[] depth = NESTING.get();
        if (size == null || depth[0] >= MAX_NESTING || Thread.currentThread().isInterrupted()) {
            return blank();
        }
        if (drawn != null && drawn.getWidth() == size[0] && drawn.getHeight() == size[1]) {
            return drawn;
        }
        BufferedImage img = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        depth[0]++;
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            install(g);
            delegate.drawImage(g, new Rectangle2D.Double(0, 0, size[0], size[1]));
        } catch (RuntimeException | StackOverflowError e) {
            return blank();
        } finally {
            depth[0]--;
            g.dispose();
        }
        drawn = alpha == 0 ? img : BitmapImageRenderer.setAlpha(img, alpha);
        return drawn;
    }

    private static int[] capped(Dimension2D dimension) {
        if (dimension == null) {
            return null;
        }
        double w = dimension.getWidth();
        double h = dimension.getHeight();
        if (!(w >= 0.5) || !(h >= 0.5) || Double.isInfinite(w) || Double.isInfinite(h)) {
            return null;
        }
        double scale = Math.min(1, Math.min(MAX_IMAGE_SIDE / Math.max(w, h), Math.sqrt(MAX_IMAGE_PIXELS / (w * h))));
        return new int[] {Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale))};
    }

    private static BufferedImage blank() {
        return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    }

    @Override
    public boolean drawImage(Graphics2D graphics, Rectangle2D anchor) {
        return delegate.drawImage(graphics, anchor);
    }

    @Override
    public boolean drawImage(Graphics2D graphics, Rectangle2D anchor, Insets clip) {
        return delegate.drawImage(graphics, anchor, clip);
    }

    private static final class Decoded extends BitmapImageRenderer {
        Decoded(BufferedImage image) {
            img = image;
        }

        @Override
        public boolean canRender(String contentType) {
            return true;
        }

        @Override
        public void loadImage(byte[] data, String contentType) throws IOException {
            throw new IOException("A decoded picture cannot be reloaded");
        }

        @Override
        public void loadImage(InputStream data, String contentType) throws IOException {
            throw new IOException("A decoded picture cannot be reloaded");
        }
    }

    private static final class Nothing implements ImageRenderer {

        @Override
        public boolean canRender(String contentType) {
            return true;
        }

        @Override
        public void loadImage(InputStream data, String contentType) {}

        @Override
        public void loadImage(byte[] data, String contentType) {}

        @Override
        public Rectangle2D getNativeBounds() {
            return new Rectangle2D.Double(0, 0, 1, 1);
        }

        @Override
        public Rectangle2D getBounds() {
            return getNativeBounds();
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
            return false;
        }

        @Override
        public boolean drawImage(Graphics2D graphics, Rectangle2D anchor, Insets clip) {
            return false;
        }
    }
}
