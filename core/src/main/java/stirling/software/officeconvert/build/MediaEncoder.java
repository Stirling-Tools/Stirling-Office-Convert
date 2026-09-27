package stirling.software.officeconvert.build;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;

import stirling.software.officeconvert.Pictures;
import stirling.software.officeconvert.extract.ImageBudget;
import stirling.software.officeconvert.extract.Jpeg;
import stirling.software.officeconvert.extract.PageGraphics.ImageDraw;
import stirling.software.officeconvert.extract.PageGraphics;
import stirling.software.officeconvert.layout.Box;

final class MediaEncoder {

    private static final int MAX_SIDE = 4000;

    private static final double EMBED_DPI = 220;

    private static final int JPEG_MIN_PIXELS = 400_000;

    private static final float PAPER_DPI = 110f;

    record Encoded(byte[] bytes, String ext, int width, int height) {}

    private static final long MAX_PIXELS = 16_000_000L;

    private static final long MAX_DECODE_PIXELS = 2L * MAX_SIDE * MAX_SIDE;

    private static final Log LOG = LogFactory.getLog(MediaEncoder.class);

    private final PDDocument document;
    private final float figureDpi;
    private final boolean lossless;
    private PDFRenderer renderer;
    private PDFRenderer textFreeRenderer;
    private FigureRenderer paperRenderer;
    private int lastFigurePage = -1;
    private BufferedImage pageRender;

    MediaEncoder(PDDocument document, float figureDpi, Pictures pictures) {
        this.document = document;
        this.figureDpi = figureDpi;
        this.lossless = pictures == Pictures.LOSSLESS;
    }

    void endPage() {
        pageRender = null;
        lastFigurePage = -1;
    }

    Encoded image(ImageDraw draw) {
        try {
            PDImage image = draw.image();
            if (!ImageBudget.affordable(image)) {
                LOG.warn("Image of " + image.getWidth() + "x" + image.getHeight() + " left out: too large to decode");
                return null;
            }
            if (draw.stencilRgb() >= 0) {
                if ((long) image.getWidth() * image.getHeight() > MAX_DECODE_PIXELS) {
                    LOG.warn("Stencil mask of " + image.getWidth() + "x" + image.getHeight() + " left out: too large to decode");
                    return null;
                }
                BufferedImage st = image.getStencilImage(new Color(draw.stencilRgb() | opacity(draw) << 24, true));
                return png(lossless ? st : limit(st));
            }
            if (image instanceof PDImageXObject x && opacity(draw) == 255) {
                Encoded raw = rawJpeg(x, lossless);
                if (raw != null) {
                    return raw;
                }
            }
            int subsampling = subsampling(draw, lossless);
            BufferedImage bi = subsampling > 1 ? image.getImage(null, subsampling) : image.getImage();
            if (bi == null) {
                return null;
            }
            bi = faded(lossless ? bi : limit(bi), opacity(draw));
            boolean alpha = bi.getColorModel().hasAlpha();
            if (!lossless && !alpha && (long) bi.getWidth() * bi.getHeight() >= JPEG_MIN_PIXELS && isPhoto(image)) {
                return jpeg(bi, 0.9f);
            }
            return png(bi);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Image left out: it could not be decoded", e);
            return null;
        } catch (OutOfMemoryError e) {
            LOG.warn("Image left out: not enough memory to decode it");
            return null;
        }
    }

    static int subsampling(ImageDraw draw) {
        return subsampling(draw, false);
    }

    static int subsampling(ImageDraw draw, boolean everyPixel) {
        PDImage image = draw.image();
        int s = 1;
        if (!everyPixel) {
            double drawn = Math.max(Math.abs(draw.right() - draw.x()), Math.abs(draw.bottom() - draw.top()));
            double needed = Math.min(MAX_SIDE, Math.max(1, drawn * EMBED_DPI / 72.0));
            s = Math.max(1, (int) Math.floor(Math.max(image.getWidth(), image.getHeight()) / needed));
        }
        while ((long) (image.getWidth() / s) * (image.getHeight() / s) > MAX_DECODE_PIXELS) {
            s++;
        }
        return s;
    }

    private static boolean isPhoto(PDImage image) {
        return image.getBitsPerComponent() >= 8;
    }

    private static Encoded rawJpeg(PDImageXObject x, boolean anySize) throws IOException {
        if (!"jpg".equals(x.getSuffix())
                || x.getCOSObject().containsKey(COSName.SMASK)
                || x.getCOSObject().containsKey(COSName.MASK)
                || x.getDecode() != null && x.getDecode().size() > 0) {
            return null;
        }
        PDColorSpace cs = x.getColorSpace();
        boolean simple = cs instanceof PDDeviceRGB || cs instanceof PDDeviceGray
                || cs != null && "ICCBased".equals(cs.getName()) && cs.getNumberOfComponents() != 4;
        if (!simple || !anySize && Math.max(x.getWidth(), x.getHeight()) > MAX_SIDE) {
            return null;
        }
        List<String> stop = List.of(COSName.DCT_DECODE.getName(), COSName.DCT_DECODE_ABBREVIATION.getName());
        try (InputStream in = x.getStream().createInputStream(stop)) {
            byte[] bytes = Jpeg.clean(in.readAllBytes());
            Jpeg.Frame f = bytes == null ? null : Jpeg.frame(bytes);
            if (f == null || f.width() != x.getWidth() || f.height() != x.getHeight()
                    || f.components() != cs.getNumberOfComponents()) {
                return null;
            }
            return new Encoded(bytes, "jpeg", x.getWidth(), x.getHeight());
        }
    }

    Encoded figure(PDPage page, int pageIndex, AffineTransform toDisplay, Box box, boolean withText) {
        try {
            if (!withText) {
                return png(render(textFree(), page, pageIndex, toDisplay, box, scaleFor(box)));
            }
            if (pageIndex == lastFigurePage) {
                if (pageRender == null) {
                    PDRectangle crop = page.getCropBox();
                    boolean quarter = Math.floorMod(page.getRotation(), 180) != 0;
                    float k = (float) Math.sqrt(Math.abs(toDisplay.getDeterminant()));
                    Box whole = new Box(0, 0, (quarter ? crop.getHeight() : crop.getWidth()) * k,
                            (quarter ? crop.getWidth() : crop.getHeight()) * k);
                    if (pixels(whole, figureDpi / 72f) <= MAX_PIXELS) {
                        pageRender = render(renderer(), page, pageIndex, toDisplay, whole, figureDpi / 72f);
                    }
                }
                BufferedImage cropped = cropFrom(pageRender, box, figureDpi / 72f);
                if (cropped != null) {
                    return png(cropped);
                }
            } else {
                lastFigurePage = pageIndex;
                pageRender = null;
            }
            return png(render(renderer(), page, pageIndex, toDisplay, box, scaleFor(box)));
        } catch (Exception e) {
            LOG.warn("Figure left out: the page region could not be rendered", e);
            return null;
        }
    }

    Encoded paper(PDPage page, int pageIndex, AffineTransform toDisplay, Box box) {
        try {
            float scale = Math.min(scaleFor(box), PAPER_DPI / 72f);
            FigureRenderer r = paperRenderer();
            r.paperSpan(toDisplay.createInverse().createTransformedShape(
                    new java.awt.geom.Rectangle2D.Float(box.x(), box.top(), box.width(), box.height())).getBounds2D());
            BufferedImage img = render(r, page, pageIndex, toDisplay, box, scale);
            return lossless ? png(img) : jpeg(img, 0.9f);
        } catch (Exception e) {
            LOG.warn("Page background left out: it could not be rendered", e);
            return null;
        }
    }

    Encoded veil(List<PageGraphics.Outline> outlines, Box box, List<Box> shown) {
        float scale = scaleFor(box);
        int w = Math.max(1, Math.round(box.width() * scale));
        int h = Math.max(1, Math.round(box.height() * scale));
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.scale(scale, scale);
            g.translate(-box.x(), -box.top());
            Area open = new Area(new Rectangle2D.Float(box.x(), box.top(), box.width(), box.height()));
            for (Box s : shown) {
                open.subtract(new Area(new Rectangle2D.Float(s.x(), s.top(), s.width(), s.height())));
            }
            g.clip(open);
            for (PageGraphics.Outline o : outlines) {
                g.setColor(new Color((o.rgb() >> 16) & 0xFF, (o.rgb() >> 8) & 0xFF, o.rgb() & 0xFF,
                        Math.clamp(Math.round(o.alpha() * 255), 0, 255)));
                g.fill(o.path());
            }
            return png(img);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Watermark left out: it could not be drawn", e);
            return null;
        } finally {
            g.dispose();
        }
    }

    private float scaleFor(Box box) {
        float scale = figureDpi / 72f;
        if (pixels(box, scale) > MAX_PIXELS) {
            scale = (float) Math.sqrt(MAX_PIXELS / ((double) box.width() * box.height()));
        }
        return scale;
    }

    private PDFRenderer renderer() {
        if (renderer == null) {
            renderer = new FigureRenderer(document, true);
            renderer.setSubsamplingAllowed(true);
        }
        return renderer;
    }

    private FigureRenderer paperRenderer() {
        if (paperRenderer == null) {
            paperRenderer = new FigureRenderer(document, false, true);
            paperRenderer.setSubsamplingAllowed(true);
        }
        return paperRenderer;
    }

    private PDFRenderer textFree() {
        if (textFreeRenderer == null) {
            textFreeRenderer = new FigureRenderer(document, false);
            textFreeRenderer.setSubsamplingAllowed(true);
        }
        return textFreeRenderer;
    }

    private static BufferedImage cropFrom(BufferedImage full, Box box, float scale) {
        if (full == null) {
            return null;
        }
        int x = Math.max(0, Math.round(box.x() * scale));
        int y = Math.max(0, Math.round(box.top() * scale));
        int w = Math.min(full.getWidth() - x, Math.max(1, Math.round(box.width() * scale)));
        int h = Math.min(full.getHeight() - y, Math.max(1, Math.round(box.height() * scale)));
        return w > 0 && h > 0 ? full.getSubimage(x, y, w, h) : null;
    }

    private static long pixels(Box box, float scale) {
        return (long) Math.max(1, Math.round(box.width() * scale)) * Math.max(1, Math.round(box.height() * scale));
    }

    private static BufferedImage render(PDFRenderer renderer, PDPage page, int pageIndex, AffineTransform toDisplay,
            Box box, float scale) throws IOException, java.awt.geom.NoninvertibleTransformException {
        int w = Math.max(1, Math.round(box.width() * scale));
        int h = Math.max(1, Math.round(box.height() * scale));
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setBackground(Color.WHITE);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setClip(0, 0, w, h);
            AffineTransform t = new AffineTransform();
            t.scale(scale, scale);
            t.translate(-box.x(), -box.top());
            t.concatenate(toDisplay);
            t.concatenate(renderTransform(page).createInverse());
            g.transform(t);
            renderer.renderPageToGraphics(pageIndex, g, 1f);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static AffineTransform renderTransform(PDPage page) {
        PDRectangle crop = page.getCropBox();
        int rotation = Math.floorMod(page.getRotation(), 360);
        AffineTransform t = new AffineTransform();
        if (rotation != 0) {
            float tx = 0;
            float ty = 0;
            switch (rotation) {
                case 90 -> tx = crop.getHeight();
                case 270 -> ty = crop.getWidth();
                case 180 -> {
                    tx = crop.getWidth();
                    ty = crop.getHeight();
                }
                default -> {}
            }
            t.translate(tx, ty);
            t.rotate(Math.toRadians(rotation));
        }
        t.translate(0, crop.getHeight());
        t.scale(1, -1);
        t.translate(-crop.getLowerLeftX(), -crop.getLowerLeftY());
        return t;
    }

    private static int opacity(ImageDraw draw) {
        return Math.clamp(Math.round(draw.alpha() * 255), 0, 255);
    }

    private static BufferedImage faded(BufferedImage bi, int opacity) {
        if (opacity >= 255) {
            return bi;
        }
        BufferedImage out = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity / 255f));
        g.drawImage(bi, 0, 0, null);
        g.dispose();
        return out;
    }

    private static BufferedImage limit(BufferedImage bi) {
        int side = Math.max(bi.getWidth(), bi.getHeight());
        if (side <= MAX_SIDE) {
            return bi;
        }
        double s = (double) MAX_SIDE / side;
        int w = Math.max(1, (int) Math.round(bi.getWidth() * s));
        int h = Math.max(1, (int) Math.round(bi.getHeight() * s));
        int type = bi.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage out = new BufferedImage(w, h, type);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(bi, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    static Encoded png(BufferedImage bi) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BufferedImage src = bi;
        int type = bi.getType();
        if (type != BufferedImage.TYPE_INT_RGB && type != BufferedImage.TYPE_INT_ARGB
                && type != BufferedImage.TYPE_3BYTE_BGR && type != BufferedImage.TYPE_4BYTE_ABGR
                && type != BufferedImage.TYPE_BYTE_GRAY && type != BufferedImage.TYPE_BYTE_BINARY) {
            boolean alpha = bi.getColorModel().hasAlpha();
            src = new BufferedImage(bi.getWidth(), bi.getHeight(),
                    alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            Graphics2D g = src.createGraphics();
            g.drawImage(bi, 0, 0, null);
            g.dispose();
        }
        if (!ImageIO.write(src, "png", out)) {
            throw new IOException("No PNG writer");
        }
        return new Encoded(out.toByteArray(), "png", bi.getWidth(), bi.getHeight());
    }

    static Encoded jpeg(BufferedImage bi, float quality) throws IOException {
        BufferedImage rgb = bi;
        if (bi.getType() != BufferedImage.TYPE_INT_RGB && bi.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            rgb = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.drawImage(bi, 0, 0, Color.WHITE, null);
            g.dispose();
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return png(bi);
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return new Encoded(out.toByteArray(), "jpeg", bi.getWidth(), bi.getHeight());
    }
}
