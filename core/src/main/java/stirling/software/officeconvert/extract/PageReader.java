package stirling.software.officeconvert.extract;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;

import stirling.software.officeconvert.extract.GlyphCollector.RawGlyph;

public final class PageReader {

    public interface PageConsumer {
        void accept(PageData page) throws IOException;
    }

    private final PDDocument document;
    private final FontResolver fonts = new FontResolver();
    private final ParsedStreams parsed = new ParsedStreams();
    private final PageIndex pages;

    public PageReader(PDDocument document) {
        this.document = document;
        this.pages = new PageIndex(document);
        TextMaps.seed(document);
    }

    private Map<COSDictionary, Integer> pageIndex;

    public static final float MAX_PAGE_SIDE = 1584f;

    public void read(int first, int last, boolean withGraphics, PageConsumer consumer)
            throws IOException {
        GlyphCollector.read(
                document,
                first,
                last,
                fonts,
                withGraphics ? parsed : null,
                (index, page, raw) -> consumer.accept(build(index, page, raw, withGraphics)));
    }

    private PageData build(int index, PDPage page, List<RawGlyph> raw, boolean withGraphics)
            throws IOException {
        int direction = raw.isEmpty() ? Math.floorMod(page.getRotation(), 360) / 90 * 90 : dominantDirection(raw);
        PDRectangle crop = page.getCropBox();
        boolean swapped = direction == 90 || direction == 270;
        float k = fitScale(crop);
        float width = (swapped ? crop.getHeight() : crop.getWidth()) * k;
        float height = (swapped ? crop.getWidth() : crop.getHeight()) * k;
        AffineTransform toDisplay = displayTransform(crop, direction);

        List<Glyph> glyphs = new ArrayList<>(raw.size());
        List<Glyph> hidden = new ArrayList<>();
        List<Glyph> rotated = new ArrayList<>();
        for (RawGlyph r : raw) {
            Glyph g = k == 1 ? r.glyph() : r.glyph().scaled(k);
            if (r.direction() != direction) {
                rotated.add(relocate(g, r, toDisplay, crop));
            } else if (r.invisible()) {
                hidden.add(g);
            } else {
                glyphs.add(g);
            }
        }
        if (direction == 0) {
            VerticalText.lift(glyphs, rotated);
        }
        glyphs = dedupe(Clusters.join(glyphs));
        PageGraphics graphics =
                withGraphics
                        ? GraphicsCollector.read(page, toDisplay, width, height, parsed)
                        : new PageGraphics(List.of(), List.of(), List.of(), List.of());
        List<PageData.Link> links = withGraphics ? links(page, toDisplay) : List.of();
        return new PageData(
                index, width, height, direction, glyphs, hidden, rotated, graphics, links);
    }

    public PageData unreadable(int index) {
        float width = 612;
        float height = 792;
        try {
            PDRectangle crop = pages.cropBox(index);
            width = crop.getWidth() * fitScale(crop);
            height = crop.getHeight() * fitScale(crop);
        } catch (RuntimeException e) {
        }
        return new PageData(index, width, height, 0, List.of(), List.of(), List.of(),
                new PageGraphics(List.of(), List.of(), List.of(), List.of()), List.of());
    }

    public PDRectangle cropBox(int index) {
        return pages.cropBox(index);
    }

    public PageData complete(PageData glyphsOnly) throws IOException {
        PDPage page = document.getPage(glyphsOnly.index());
        AffineTransform toDisplay = displayTransform(page.getCropBox(), glyphsOnly.direction());
        PageGraphics graphics =
                GraphicsCollector.read(page, toDisplay, glyphsOnly.width(), glyphsOnly.height(), parsed);
        return new PageData(
                glyphsOnly.index(),
                glyphsOnly.width(),
                glyphsOnly.height(),
                glyphsOnly.direction(),
                glyphsOnly.glyphs(),
                glyphsOnly.hidden(),
                glyphsOnly.rotated(),
                graphics,
                links(page, toDisplay));
    }

    private static Glyph relocate(Glyph g, RawGlyph r, AffineTransform toDisplay, PDRectangle crop) {
        Point2D.Float p = new Point2D.Float();
        toDisplay.transform(
                new Point2D.Double(
                        r.originX() + crop.getLowerLeftX(), r.originY() + crop.getLowerLeftY()),
                p);
        Glyph moved =
                new Glyph(
                        g.text, p.x, g.width, p.y, g.size, g.ascent, g.descent, g.font, g.rgb,
                        g.seq, g.spaceWidth, g.bold, g.italic);
        moved.vertAlign = r.direction();
        moved.hscale = g.hscale;
        return moved;
    }

    private static int dominantDirection(List<RawGlyph> raw) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (RawGlyph r : raw) {
            if (!r.invisible()) {
                counts.merge(r.direction(), 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            for (RawGlyph r : raw) {
                counts.merge(r.direction(), 1, Integer::sum);
            }
        }
        int best = 0;
        int bestCount = -1;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount || e.getValue() == bestCount && e.getKey() == 0) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    private static List<Glyph> dedupe(List<Glyph> glyphs) throws IOException {
        Cells grid = new Cells(glyphs.size());
        Set<Glyph> dropped = new HashSet<>();
        long remaining = 10_000_000;
        for (Glyph g : glyphs) {
            if (g.isSpace()) {
                continue;
            }
            int gx = (int) Math.floor(g.x);
            int gy = (int) Math.floor(g.baseline);
            float reachX = Math.max(0.6f, g.width * 0.25f);
            float reachY = Math.max(0.6f, g.size * 0.1f);
            long x0 = (int) Math.floor(g.x - reachX);
            long x1 = (int) Math.floor(g.x + reachX);
            long y0 = (int) Math.floor(g.baseline - reachY);
            long y1 = (int) Math.floor(g.baseline + reachY);
            // Font sizes and text matrices are supplied by the PDF, independent of its page dimensions.
            if (!Float.isFinite(reachX) || !Float.isFinite(reachY)
                    || (double) (x1 - x0 + 1) * (y1 - y0 + 1) > 1_000_000) {
                throw new IOException("Text geometry exceeds the duplicate-detection work limit");
            }
            Glyph twin = null;
            outer:
            for (long cx = x0; cx <= x1; cx++) {
                for (long cy = y0; cy <= y1; cy++) {
                    if (--remaining < 0) {
                        throw new IOException("Page exceeds the duplicate-detection work limit");
                    }
                    if ((remaining & 1023) == 0) {
                        PdfFiles.stopIfInterrupted();
                    }
                    List<Glyph> cell = grid.get(key((int) cx, (int) cy));
                    if (cell == null) {
                        continue;
                    }
                    for (Glyph o : cell) {
                        if (--remaining < 0) {
                            throw new IOException("Page exceeds the duplicate-detection work limit");
                        }
                        if ((remaining & 1023) == 0) {
                            PdfFiles.stopIfInterrupted();
                        }
                        if (o.text.equals(g.text)
                                && Math.abs(o.x - g.x) < reachX
                                && Math.abs(o.baseline - g.baseline) < reachY
                                && Math.abs(o.size - g.size) < 0.5f) {
                            twin = o;
                            break outer;
                        }
                    }
                }
            }
            if (twin != null && twin.rgb != g.rgb) {
                dropped.add(twin);
                grid.get(key((int) Math.floor(twin.x), (int) Math.floor(twin.baseline))).remove(twin);
                grid.add(key(gx, gy), g);
            } else if (twin != null) {
                if (Math.abs(twin.x - g.x) > 0.05f || Math.abs(twin.baseline - g.baseline) > 0.05f) {
                    twin.bold = true;
                }
                dropped.add(g);
            } else {
                grid.add(key(gx, gy), g);
            }
        }
        if (dropped.isEmpty()) {
            return glyphs;
        }
        List<Glyph> out = new ArrayList<>(glyphs.size() - dropped.size());
        for (Glyph g : glyphs) {
            if (!dropped.contains(g)) {
                out.add(g);
            }
        }
        return out;
    }

    private static long key(int x, int y) {
        return (((long) x << 32) ^ (y & 0xFFFFFFFFL)) * 0x9E3779B97F4A7C15L;
    }

    // Glyphs by whole-point cell, looked up without boxing: open addressing on the already mixed keys
    private static final class Cells {

        private long[] keys;

        private List<?>[] lists;

        private int size;

        Cells(int expected) {
            int capacity = Integer.highestOneBit(Math.max(8, expected) * 2 - 1) << 1;
            keys = new long[capacity];
            lists = new List<?>[capacity];
        }

        @SuppressWarnings("unchecked")
        List<Glyph> get(long key) {
            int mask = keys.length - 1;
            for (int i = slot(key, mask); lists[i] != null; i = (i + 1) & mask) {
                if (keys[i] == key) {
                    return (List<Glyph>) lists[i];
                }
            }
            return null;
        }

        void add(long key, Glyph g) {
            List<Glyph> cell = get(key);
            if (cell == null) {
                if (2 * (size + 1) > keys.length) {
                    grow();
                }
                cell = new ArrayList<>(2);
                put(key, cell);
                size++;
            }
            cell.add(g);
        }

        private void put(long key, List<?> list) {
            int mask = keys.length - 1;
            int i = slot(key, mask);
            while (lists[i] != null) {
                i = (i + 1) & mask;
            }
            keys[i] = key;
            lists[i] = list;
        }

        private void grow() {
            long[] oldKeys = keys;
            List<?>[] oldLists = lists;
            keys = new long[oldKeys.length * 2];
            lists = new List<?>[oldKeys.length * 2];
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldLists[i] != null) {
                    put(oldKeys[i], oldLists[i]);
                }
            }
        }

        private static int slot(long key, int mask) {
            return (int) (key >>> 32 ^ key) & mask;
        }
    }

    private List<PageData.Link> links(PDPage page, AffineTransform toDisplay) {
        List<PageData.Link> out = new ArrayList<>();
        List<PDAnnotation> annotations;
        try {
            annotations = page.getAnnotations();
        } catch (IOException e) {
            return out;
        }
        for (PDAnnotation a : annotations) {
            if (!(a instanceof PDAnnotationLink link) || link.getRectangle() == null) {
                continue;
            }
            String uri = null;
            int target = -1;
            try {
                PDAction action = link.getAction();
                if (action instanceof PDActionURI u) {
                    uri = u.getURI();
                } else if (action instanceof PDActionGoTo g) {
                    target = pageOf(g.getDestination());
                }
                if (uri == null && target < 0) {
                    target = pageOf(link.getDestination());
                }
            } catch (IOException | RuntimeException e) {
                continue;
            }
            if (uri == null && target < 0) {
                continue;
            }
            PDRectangle r = link.getRectangle();
            Point2D.Float p0 = new Point2D.Float();
            Point2D.Float p1 = new Point2D.Float();
            toDisplay.transform(new Point2D.Double(r.getLowerLeftX(), r.getLowerLeftY()), p0);
            toDisplay.transform(new Point2D.Double(r.getUpperRightX(), r.getUpperRightY()), p1);
            out.add(
                    new PageData.Link(
                            Math.min(p0.x, p1.x),
                            Math.min(p0.y, p1.y),
                            Math.max(p0.x, p1.x),
                            Math.max(p0.y, p1.y),
                            uri,
                            target));
        }
        return out;
    }

    private int pageOf(PDDestination dest) throws IOException {
        if (dest instanceof PDNamedDestination named) {
            dest = document.getDocumentCatalog().findNamedDestinationPage(named);
        }
        if (dest instanceof PDPageDestination pd) {
            PDPage target = pd.getPage();
            if (target == null) {
                return pd.getPageNumber();
            }
            Integer n = pageIndex().get(target.getCOSObject());
            return n == null ? -1 : n;
        }
        return -1;
    }

    private Map<COSDictionary, Integer> pageIndex() {
        if (pageIndex == null) {
            pageIndex = new HashMap<>();
            int i = 0;
            for (PDPage p : document.getPages()) {
                pageIndex.putIfAbsent(p.getCOSObject(), i++);
            }
        }
        return pageIndex;
    }

    public static AffineTransform displayTransform(PDRectangle crop, int direction) {
        double llx = crop.getLowerLeftX();
        double lly = crop.getLowerLeftY();
        double w = crop.getWidth();
        double h = crop.getHeight();
        AffineTransform t = AffineTransform.getScaleInstance(fitScale(crop), fitScale(crop));
        t.concatenate(switch (direction) {
            case 90 -> new AffineTransform(0, 1, 1, 0, -lly, -llx);
            case 180 -> new AffineTransform(-1, 0, 0, 1, w + llx, -lly);
            case 270 -> new AffineTransform(0, -1, -1, 0, h + lly, w + llx);
            default -> new AffineTransform(1, 0, 0, -1, -llx, h + lly);
        });
        return t;
    }

    static float fitScale(PDRectangle crop) {
        float side = Math.max(crop.getWidth(), crop.getHeight());
        return side > MAX_PAGE_SIDE ? MAX_PAGE_SIDE / side : 1f;
    }
}
