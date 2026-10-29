package stirling.software.officeconvert.slides;

import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
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
import java.util.function.Predicate;
import javax.imageio.ImageIO;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.build.PlacedContent;
import stirling.software.officeconvert.build.RgbPixels;
import stirling.software.officeconvert.extract.OperatorBudget;
import stirling.software.officeconvert.layout.Box;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.slides.PaintOrder.Kind;
import stirling.software.officeconvert.slides.PaintOrder.Mark;

final class Art {

    private static final Log LOG = LogFactory.getLog(Art.class);

    private static final float MIN_AREA = 1f;

    private static final int MAX_PIECES = 120;

    private static final int MAX_BLANK_PIECES = 16;

    private static final float JOIN_GAP = 2f;

    private static final long MAX_PAGE_OPERATORS = 4 * OperatorBudget.MAX_OPERATORS;

    private static final long MAX_PIXELS = 3_000_000L;

    private static final float SHADING_DPI = 96f;

    private static final int MAX_FIGURE_RUNS = 2;

    private static final int MAX_EXTRA_RUNS = 2;

    private final PDDocument document;
    private final ArtRenderer renderer;
    private final PlacedContent.MediaStore media;
    private final float dpi;
    private final boolean lossless;
    private final Predicate<Gradient> gradients;

    Art(PDDocument document, PlacedContent.MediaStore media, float dpi, boolean lossless, Predicate<Gradient> gradients) {
        this.document = document;
        this.renderer = new ArtRenderer(document);
        this.media = media;
        this.dpi = dpi;
        this.lossless = lossless;
        this.gradients = gradients;
    }

    record Piece(SlideShape shape, int order) {}

    long operators() {
        return renderer.operators();
    }

    private record Plan(List<Run> runs, List<Piece> shapes) {}

    boolean drawable(PaintOrder paint, Set<Integer> represented, float width, float height) {
        if (!paint.drawsShapes()) {
            return false;
        }
        Plan plan = plan(paint, represented, true, width, height, gradients);
        return !plan.shapes().isEmpty() && plan.runs().size() <= MAX_FIGURE_RUNS;
    }

    List<Piece> pieces(PaintOrder paint, Set<Integer> represented, int pageIndex, AffineTransform toDisplay,
            float width, float height) {
        Plan plan = plan(paint, represented, paint.drawsShapes(), width, height, gradients);
        if (!plan.shapes().isEmpty()) {
            Plan raster = plan(paint, represented, false, width, height, gradients);
            if (plan.runs().size() > raster.runs().size() + MAX_EXTRA_RUNS) {
                plan = raster;
            }
        }
        List<Piece> out = new ArrayList<>(plan.shapes());
        List<Run> kept = plan.runs();
        kept.sort(Comparator.comparingDouble(r -> -r.box.area()));
        int blanks = 0;
        long start = renderer.operators();
        for (Run r : kept.subList(0, Math.min(MAX_PIECES, kept.size()))) {
            if (renderer.operators() - start > MAX_PAGE_OPERATORS) {
                break;
            }
            Piece p = piece(paint, r.marks, r.box, pageIndex, toDisplay, width, height);
            if (p != null) {
                out.add(p);
                blanks = 0;
            } else if (++blanks >= MAX_BLANK_PIECES) {
                break;
            }
        }
        return out;
    }

    private static Plan plan(PaintOrder paint, Set<Integer> represented, boolean shapes, float width, float height,
            Predicate<Gradient> gradients) {
        List<Run> runs = new ArrayList<>();
        List<Piece> drawn = new ArrayList<>();
        Run run = null;
        List<Mark> between = new ArrayList<>();
        for (Mark m : paint.marks()) {
            if (m.kind() == Kind.TEXT || represented.contains(m.order())) {
                between.add(m);
                continue;
            }
            if (shapes && m.line() != null) {
                drawn.add(new Piece(m.line(), m.order()));
                between.add(m);
                continue;
            }
            if (shapes && m.shape() != null
                    && (m.gradient() != null ? gradients.test(m.gradient()) : m.outline() == PaintOrder.Outline.OTHER)) {
                Piece p = shape(m);
                if (p != null) {
                    drawn.add(p);
                }
                between.add(m);
                continue;
            }
            Box b = m.box();
            if (run != null && !run.joins(b, between)) {
                runs.add(run);
                run = null;
            }
            if (run == null) {
                run = new Run();
            }
            run.add(m);
            between.clear();
        }
        if (run != null) {
            runs.add(run);
        }
        List<Run> kept = new ArrayList<>();
        for (Run r : runs) {
            r.box = bounds(r.marks, width, height);
            if (r.box != null && r.box.area() >= MIN_AREA) {
                kept.add(r);
            }
        }
        return new Plan(kept, drawn);
    }

    private static Piece shape(Mark m) {
        Rectangle2D tight = m.shape().getBounds2D();
        if (tight.getWidth() * tight.getHeight() < MIN_AREA) {
            return null;
        }
        Rectangle2D b = hull(m.shape());
        Frame frame = new Frame((float) b.getX(), (float) b.getY(), (float) b.getWidth(), (float) b.getHeight());
        return new Piece(new PathShape(frame, m.shape(), m.rgb(), m.alpha(), m.gradient()), m.order());
    }

    private static Rectangle2D hull(Path2D path) {
        double[] c = new double[6];
        Rectangle2D.Double b = null;
        for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
            int seg = it.currentSegment(c);
            int points = seg == PathIterator.SEG_CUBICTO ? 3 : seg == PathIterator.SEG_QUADTO ? 2
                    : seg == PathIterator.SEG_CLOSE ? 0 : 1;
            for (int i = 0; i < points; i++) {
                if (b == null) {
                    b = new Rectangle2D.Double(c[0], c[1], 0, 0);
                } else {
                    b.add(c[2 * i], c[2 * i + 1]);
                }
            }
        }
        return b == null ? path.getBounds2D() : b;
    }

    private static final class Run {
        final List<Mark> marks = new ArrayList<>();
        Box box;
        private Box reach;
        private final List<Mark> skipped = new ArrayList<>();

        void add(Mark m) {
            marks.add(m);
            reach = reach == null ? m.box() : reach.union(m.box());
        }

        boolean joins(Box next, List<Mark> between) {
            if (!between.isEmpty() && !reach.near(next, JOIN_GAP)) {
                return false;
            }
            skipped.addAll(between);
            Box joined = reach.union(next);
            for (Mark m : skipped) {
                if (m.box().overlapArea(joined) > 0) {
                    return false;
                }
            }
            return true;
        }
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

    Piece together(PaintOrder paint, List<Mark> marks, int pageIndex, AffineTransform toDisplay, float width,
            float height) {
        Box b = bounds(marks, width, height);
        return b == null ? null : piece(paint, marks, b, pageIndex, toDisplay, width, height);
    }

    private Piece piece(PaintOrder paint, List<Mark> run, Box box, int pageIndex, AffineTransform toDisplay,
            float width, float height) {
        Set<Integer> orders = new HashSet<>();
        int first = Integer.MAX_VALUE;
        boolean blended = false;
        boolean shading = true;
        for (Mark m : run) {
            orders.add(m.order());
            first = Math.min(first, m.order());
            blended |= m.blended();
            shading &= m.kind() == Kind.SHADING;
        }
        if (blended) {
            backdrop(paint, box, first, orders);
        }
        float scale = (shading ? Math.min(dpi, SHADING_DPI) : dpi) / 72f;
        if ((double) box.width() * box.height() * scale * scale > MAX_PIXELS) {
            scale = (float) Math.sqrt(MAX_PIXELS / ((double) box.width() * box.height()));
        }
        try {
            BufferedImage img = renderer.render(document.getPage(pageIndex), pageIndex, toDisplay, box, scale,
                    orders::contains, paint.groups());
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

    private static void backdrop(PaintOrder paint, Box box, int before, Set<Integer> orders) {
        for (Mark m : paint.marks()) {
            if (m.order() < before && m.box().overlapArea(box) > 0) {
                for (int o = m.from(); o <= m.order(); o++) {
                    orders.add(o);
                }
            }
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
            if (ImageIO.write(RgbPixels.onWhite(img), "jpeg", out)) {
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
