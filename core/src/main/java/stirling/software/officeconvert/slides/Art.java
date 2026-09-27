package stirling.software.officeconvert.slides;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.build.PlacedContent;
import stirling.software.officeconvert.layout.Box;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.slides.PaintOrder.Kind;
import stirling.software.officeconvert.slides.PaintOrder.Mark;

final class Art {

    private static final Log LOG = LogFactory.getLog(Art.class);

    private static final float MIN_SHARE = 0.01f;

    private static final int MAX_RUNS = 4;

    private static final long MAX_PIXELS = 3_000_000L;

    private final PDDocument document;
    private final ArtRenderer renderer;
    private final PlacedContent.MediaStore media;
    private final float dpi;
    private final boolean lossless;

    Art(PDDocument document, PlacedContent.MediaStore media, float dpi, boolean lossless) {
        this.document = document;
        this.renderer = new ArtRenderer(document);
        this.media = media;
        this.dpi = dpi;
        this.lossless = lossless;
    }

    record Piece(PictureShape picture, int order) {}

    List<Piece> pieces(PaintOrder paint, Set<Integer> represented, int pageIndex, AffineTransform toDisplay,
            float width, float height) {
        List<List<Mark>> runs = new ArrayList<>();
        List<Mark> run = new ArrayList<>();
        for (Mark m : paint.marks()) {
            if (m.kind() != Kind.TEXT && !represented.contains(m.order())) {
                run.add(m);
            } else if (!run.isEmpty()) {
                runs.add(run);
                run = new ArrayList<>();
            }
        }
        if (!run.isEmpty()) {
            runs.add(run);
        }
        List<Box> boxes = new ArrayList<>();
        List<List<Mark>> kept = new ArrayList<>();
        for (List<Mark> r : runs) {
            Box b = bounds(r, width, height);
            boolean strong = r.stream().anyMatch(m -> m.kind() == Kind.SHADING || m.kind() == Kind.IMAGE);
            if (b != null && (b.area() >= MIN_SHARE * width * height || strong && b.area() >= 16)) {
                kept.add(r);
                boxes.add(b);
            }
        }
        List<Integer> index = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            index.add(i);
        }
        index.sort(Comparator.comparingDouble(i -> -boxes.get(i).area()));
        List<Piece> out = new ArrayList<>();
        for (int i : index.subList(0, Math.min(MAX_RUNS, index.size()))) {
            Piece p = piece(kept.get(i), boxes.get(i), pageIndex, toDisplay, width, height);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    private static Box bounds(List<Mark> run, float width, float height) {
        Box b = null;
        for (Mark m : run) {
            b = b == null ? m.box() : b.union(m.box());
        }
        if (b == null) {
            return null;
        }
        Box clipped = new Box(Math.max(0, b.x()), Math.max(0, b.top()), Math.min(width, b.right()), Math.min(height, b.bottom()));
        return clipped.width() > 0.5f && clipped.height() > 0.5f ? clipped : null;
    }

    Piece together(List<Mark> marks, int pageIndex, AffineTransform toDisplay, float width, float height) {
        Box b = bounds(marks, width, height);
        return b == null ? null : piece(marks, b, pageIndex, toDisplay, width, height);
    }

    private Piece piece(List<Mark> run, Box box, int pageIndex, AffineTransform toDisplay, float width, float height) {
        Set<Integer> orders = new HashSet<>();
        for (Mark m : run) {
            orders.add(m.order());
        }
        float scale = dpi / 72f;
        if ((double) box.width() * box.height() * scale * scale > MAX_PIXELS) {
            scale = (float) Math.sqrt(MAX_PIXELS / ((double) box.width() * box.height()));
        }
        try {
            BufferedImage img = renderer.render(document.getPage(pageIndex), pageIndex, toDisplay, box, scale,
                    orders::contains);
            if (blank(img)) {
                return null;
            }
            boolean opaque = opaque(img);
            boolean jpeg = !lossless && opaque && box.area() >= 0.5f * width * height;
            byte[] bytes = encode(img, jpeg);
            String key = "art:" + digest(bytes);
            Picture.MediaRef ref = media.media(key);
            if (ref == null) {
                ref = media.media(bytes, jpeg ? "jpeg" : "png", img.getWidth(), img.getHeight(), key);
            }
            Picture pic = new Picture(ref, box.width(), box.height());
            return new Piece(new PictureShape(new Frame(box.x(), box.top(), box.width(), box.height()), pic),
                    run.stream().mapToInt(Mark::order).max().orElse(0));
        } catch (IOException | RuntimeException e) {
            LOG.warn("Page art left out: it could not be rendered", e);
            return null;
        }
    }

    private static boolean blank(BufferedImage img) {
        int step = Math.max(1, (int) Math.sqrt((double) img.getWidth() * img.getHeight() / 40_000));
        for (int y = 0; y < img.getHeight(); y += step) {
            for (int x = 0; x < img.getWidth(); x += step) {
                if ((img.getRGB(x, y) >>> 24) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean opaque(BufferedImage img) {
        int step = Math.max(1, (int) Math.sqrt((double) img.getWidth() * img.getHeight() / 40_000));
        for (int y = 0; y < img.getHeight(); y += step) {
            for (int x = 0; x < img.getWidth(); x += step) {
                if ((img.getRGB(x, y) >>> 24) != 0xFF) {
                    return false;
                }
            }
        }
        return true;
    }

    private static byte[] encode(BufferedImage img, boolean jpeg) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (jpeg) {
            BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.drawImage(img, 0, 0, Color.WHITE, null);
            g.dispose();
            if (ImageIO.write(rgb, "jpeg", out)) {
                return out.toByteArray();
            }
            out.reset();
        }
        if (!ImageIO.write(img, "png", out)) {
            throw new IOException("No PNG writer");
        }
        return out.toByteArray();
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
