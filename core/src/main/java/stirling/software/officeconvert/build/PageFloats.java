package stirling.software.officeconvert.build;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import stirling.software.officeconvert.extract.PageGraphics;
import stirling.software.officeconvert.layout.Box;
import stirling.software.officeconvert.layout.PageLayout;
import stirling.software.officeconvert.layout.ParaDraft;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.Stacking;

final class PageFloats {

    private final List<Picture> pictures = new ArrayList<>();
    private final List<Inline.Shape> shapes = new ArrayList<>();
    private final List<Inline.TextBox> boxes = new ArrayList<>();
    private final Map<ParaDraft, List<Inline.Shape>> riders = new LinkedHashMap<>();
    private final Map<PageLayout.Decoration, ParaDraft> hostOf = new IdentityHashMap<>();
    private int pageBase;
    private int nextPageBase;
    private int[] painted = new int[0];

    void queue(PageLayout layout, ParagraphFactory paragraphs, MediaPlacer placer, float bodySize) {
        List<PageLayout.Decoration> decorations =
                layout.decorations().stream().sorted(Comparator.comparingInt(PageLayout.Decoration::order)).toList();
        PageGraphics gfx = layout.page().graphics();
        painted = Stream.of(decorations.stream().mapToInt(PageLayout.Decoration::order),
                        gfx.images().stream().mapToInt(gfx::order), layout.veils().stream().mapToInt(PageLayout.Veil::order),
                        paperOrders(layout))
                .flatMapToInt(s -> s)
                .filter(o -> o != Integer.MAX_VALUE).distinct().sorted().toArray();
        pageBase = nextPageBase;
        nextPageBase = pageBase + painted.length + decorations.size();
        int unknown = painted.length;
        List<ParaDraft> hosts = ShapeAnchors.hosts(layout, decorations, paragraphs, bodySize);
        hostOf.clear();
        for (int i = 0; i < decorations.size(); i++) {
            PageLayout.Decoration d = decorations.get(i);
            hostOf.put(d, hosts.get(i));
            Box b = d.box();
            int z = Stacking.stacked(pageBase + (d.order() == Integer.MAX_VALUE ? unknown++ : step(d.order())));
            Inline.Shape shape = new Inline.Shape(b.x(), b.top(), Math.max(0.25f, b.width()), Math.max(0.25f, b.height()),
                    d.rgb(), d.lineRgb(), d.lineWidth(), d.rounded(), d.ellipse(), false, z);
            if (hosts.get(i) != null) {
                riders.computeIfAbsent(hosts.get(i), k -> new ArrayList<>()).add(shape);
            } else {
                shapes.add(shape);
            }
        }
        for (PageLayout.TextBoxItem tb : layout.furniture()) {
            boxes.add(placer.textBox(tb));
        }
    }

    ParaDraft host(PageLayout.Decoration d) {
        return hostOf.get(d);
    }

    void add(Picture picture) {
        pictures.add(picture);
    }

    void stack(Picture picture) {
        if (picture.paintOrder >= 0) {
            picture.z = Stacking.stacked(pageBase + step(picture.paintOrder));
        }
    }

    private static IntStream paperOrders(PageLayout layout) {
        return layout.bands().stream().flatMap(b -> b.columns().stream()).flatMap(c -> c.items().stream())
                .map(it -> it instanceof PageLayout.FloatItem f ? f.picture() : it)
                .filter(it -> it instanceof PageLayout.FigureItem fi && fi.paper())
                .mapToInt(it -> ((PageLayout.FigureItem) it).order());
    }

    private int step(int order) {
        int at = Arrays.binarySearch(painted, order);
        return at >= 0 ? at : -at - 1;
    }

    void add(Inline.TextBox box) {
        boxes.add(box);
    }

    boolean waiting() {
        return !pictures.isEmpty() || !shapes.isEmpty() || !boxes.isEmpty();
    }

    void anchorOn(Paragraph anchor) {
        anchor.inlines.addAll(0, waitingInStack());
        clear();
    }

    void anchorLeftovers(Paragraph last, int slot) {
        last.inlines.addAll(slot, waitingInStack());
        clear();
    }

    private List<Inline> waitingInStack() {
        List<Inline> out = new ArrayList<>();
        pictures.forEach(p -> out.add(new Inline.Image(p)));
        out.addAll(shapes);
        out.sort(Comparator.comparingInt(PageFloats::height));
        out.addAll(boxes);
        return out;
    }

    private static int height(Inline in) {
        return in instanceof Inline.Image im ? Stacking.picture(im.picture(), 0) : Stacking.shape((Inline.Shape) in, 0);
    }

    PageFloats setAside() {
        PageFloats held = new PageFloats();
        held.pictures.addAll(pictures);
        held.shapes.addAll(shapes);
        held.boxes.addAll(boxes);
        clear();
        return held;
    }

    void restore(PageFloats held) {
        pictures.addAll(held.pictures);
        shapes.addAll(held.shapes);
        boxes.addAll(held.boxes);
    }

    void ride(ParaDraft d, Paragraph p) {
        List<Inline.Shape> own = riders.remove(d);
        if (own != null) {
            for (Inline.Shape s : own.reversed()) {
                p.inlines.addFirst(Float.isNaN(p.sourceTop) ? s : s.riding(s.y() - anchorTop(p)));
            }
        }
    }

    List<Inline.Shape> takeUnder(ParaDraft d, float bottom, float nextTop) {
        List<Inline.Shape> own = riders.get(d);
        List<Inline.Shape> under = new ArrayList<>();
        if (own != null) {
            own.removeIf(s -> {
                boolean between = s.y() >= bottom - 0.5f && s.y() + s.height() <= nextTop + 0.5f;
                if (between) {
                    under.add(s);
                }
                return between;
            });
        }
        return under;
    }

    void rehost(ParaDraft from, ParaDraft to) {
        List<Inline.Shape> own = riders.remove(from);
        if (own != null) {
            riders.put(to, own);
        }
    }

    void unhost(ParaDraft d) {
        List<Inline.Shape> own = riders.remove(d);
        if (own != null) {
            shapes.addAll(own);
        }
    }

    void unseat(ParaDraft held) {
        riders.entrySet().removeIf(e -> {
            if (e.getKey() == held) {
                return false;
            }
            shapes.addAll(e.getValue());
            return true;
        });
    }

    static float anchorTop(Paragraph p) {
        return p.sourceTop - Math.max(0, p.spaceBefore);
    }

    private void clear() {
        pictures.clear();
        shapes.clear();
        boxes.clear();
    }
}
