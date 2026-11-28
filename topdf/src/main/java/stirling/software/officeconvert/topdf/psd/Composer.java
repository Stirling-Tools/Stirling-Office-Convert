package stirling.software.officeconvert.topdf.psd;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;

final class Composer {

    static final long MAX_LAYER_SIDE = 1_200_000;

    private final Source src;

    private final PsdDocument doc;

    private final int n;

    private final int stride;

    private final int width;

    private final boolean rgb;

    private byte[] scratch = new byte[64];

    private final Set<String> effects = new LinkedHashSet<>();

    private final Set<String> skipped = new LinkedHashSet<>();

    private boolean damaged;

    static final long MAX_PATTERN_BYTES = 64L << 20;

    private final List<float[]> pool = new ArrayList<>();

    private Node top;

    private final List<float[]> shapes = new ArrayList<>();

    private int spanLo;

    private int spanHi;

    private List<long[]> patternBlocks = List.of();

    private final java.util.Map<String, Pattern> patterns = new java.util.HashMap<>();

    private long patternBytes;

    private float[] shallowBase;

    private float[] deepBase;

    private float[] maskRow;

    private float[] maskTmp;

    private int y;

    private Composer(Source src, PsdDocument doc) {
        this.src = src;
        this.doc = doc;
        this.n = doc.colorChannels();
        this.stride = n + 1;
        this.width = doc.width;
        this.rgb = doc.mode == PsdDocument.RGB;
    }

    static boolean supports(PsdDocument doc) {
        return switch (doc.mode) {
            case PsdDocument.GRAY, PsdDocument.DUOTONE, PsdDocument.RGB, PsdDocument.CMYK, PsdDocument.LAB -> true;
            default -> false;
        };
    }

    static COSStream draw(Source src, PsdDocument doc, Layers layers, PDDocument pdf, List<String> warnings,
            boolean[] omitted) throws IOException {
        Composer c = new Composer(src, doc);
        c.patternBlocks = layers.patterns;
        Node root = c.build(layers.root, 0);
        try {
            return c.render(root, pdf, warnings, omitted);
        } finally {
            c.close(root);
        }
    }

    private COSStream render(Node root, PDDocument pdf, List<String> warnings, boolean[] omitted)
            throws IOException {
        Composer c = this;
        c.top = root;
        c.cacheRows(root, false);
        long[] budget = {LayerEffects.MAX_PIXELS * 4};
        c.feathers(root, budget);
        c.effects(root, budget);
        int components = c.n;
        int bits = doc.depth == 32 ? 16 : doc.depth;
        Plan plan = Plan.of(doc, -1);
        int w = doc.width;
        float[] canvas = new float[w * c.stride];
        byte[] out = new byte[w * components * (bits / 8)];
        byte[] alphaOut = new byte[w * (bits / 8)];
        boolean translucent = false;
        boolean invert = doc.mode == PsdDocument.CMYK;
        boolean encode = doc.depth == 32 && plan.profile == null;
        try (ImageStream color = new ImageStream(pdf, w, doc.height, components, bits, plan.colorSpace(pdf, doc),
                PsdPage.level(doc));
                ImageStream alpha = new ImageStream(pdf, w, doc.height, 1, bits, Spaces.device(1),
                        PsdPage.level(doc))) {
            for (int y = 0; y < doc.height; y++) {
                if ((y & 15) == 0) {
                    PsdPage.checkpoint();
                }
                c.y = y;
                Arrays.fill(canvas, 0);
                c.children(root, canvas);
                translucent |= c.write(canvas, out, alphaOut, bits, invert, encode);
                color.row(out);
                alpha.row(alphaOut);
            }
            color.finish();
            alpha.finish();
            COSStream image = color.stream();
            if (translucent) {
                image.setItem(COSName.SMASK, alpha.stream());
            }
            if (!c.effects.isEmpty()) {
                warnings.add("Layer effects that could not be drawn were left out: " + String.join(", ", c.effects));
            }
            if (!c.skipped.isEmpty()) {
                warnings.add("Some layers could not be drawn and were left out: " + String.join(", ", c.skipped));
            }
            if (c.damaged) {
                warnings.add("Part of the PSD document's layer data is damaged or missing and was left blank");
            }
            omitted[0] = !c.effects.isEmpty() || !c.skipped.isEmpty() || c.damaged;
            warnings.add("The PSD document holds no merged picture, so its layers were composited; it may"
                    + " differ from how the authoring app shows it");
            return image;
        }
    }

    private boolean write(float[] canvas, byte[] out, byte[] alphaOut, int bits, boolean invert, boolean encode) {
        boolean translucent = false;
        int k = 0;
        for (int x = 0; x < width; x++) {
            int at = x * stride;
            float a = canvas[at + n];
            if (a < 0.99999f) {
                translucent = true;
            }
            for (int i = 0; i < n; i++) {
                float v = a > 0 ? canvas[at + i] : 1;
                if (doc.mode == PsdDocument.LAB && i > 0 && a <= 0) {
                    v = 0.5f;
                }
                if (encode) {
                    v = Colors.encode(v);
                }
                v = Colors.clamp(v);
                if (invert) {
                    v = 1 - v;
                }
                if (bits == 8) {
                    out[k++] = (byte) Math.round(v * 255);
                } else {
                    int s = Math.round(v * 65535);
                    out[k++] = (byte) (s >> 8);
                    out[k++] = (byte) s;
                }
            }
            float av = Colors.clamp(a);
            if (bits == 8) {
                alphaOut[x] = (byte) Math.round(av * 255);
            } else {
                int s = Math.round(av * 65535);
                alphaOut[2 * x] = (byte) (s >> 8);
                alphaOut[2 * x + 1] = (byte) s;
            }
        }
        return translucent;
    }

    private static class Node {
        Layers.Layer layer;
        final List<Node> clipped = new ArrayList<>();
        final List<Plane> masks = new ArrayList<>();
        VectorMask vector;
        float vectorDensity = 1;
        boolean vectorBaked;
        LayerEffects fx;
        Gradient strokeGradient;
    }

    private static final class Group extends Node {
        final List<Node> children = new ArrayList<>();
        float[] background;
        int[] board;
    }

    private static final class Adjust extends Node {
        Adjustments adjustment;
    }

    private static final class Pixels extends Node {
        Plane[] color;
        Plane transparency;
        float[] solid;
        Gradient gradient;
        VectorMask line;
        Gradient overlayGradient;
        Pattern pattern;
        double[] patternPlace;
        Pattern overlayPattern;
        double[] overlayPlace;
        float[] lineColor;
        float lineOpacity;
        boolean noFill;
    }

    private final class Plane {
        final Layers.Channel channel;
        final int top;
        final int left;
        final int bottom;
        final int right;
        final int fill;
        Rows rows;
        int next;
        boolean done;
        boolean cacheable;
        float density = 1;
        double feather;
        float[] raster;
        int rx;
        int ry;
        int rw;
        int rh;
        float[] cache;
        int cachedRow = -1;

        Plane(Layers.Channel channel, int top, int left, int bottom, int right, int fill) {
            this.channel = channel;
            this.top = top;
            this.left = left;
            this.bottom = bottom;
            this.right = right;
            this.fill = fill;
        }

        boolean covers(int row) {
            return row >= top && row < bottom && right > left && (long) right - left <= MAX_LAYER_SIDE
                    && (long) bottom - top <= MAX_LAYER_SIDE;
        }

        int lineBytes() {
            return (int) (((long) (right - left) * doc.depth + 7) / 8);
        }

        byte[] line(int row) throws IOException {
            if (rows == null && !done) {
                open();
            }
            if (rows == null) {
                return null;
            }
            int lb = lineBytes();
            if (scratch.length < lb) {
                scratch = new byte[lb];
            }
            long skip = (long) row - top - next;
            if (skip > 0) {
                rows.skip(skip, scratch);
                next = (int) (next + skip);
            }
            rows.next(scratch);
            next++;
            if (next >= bottom - top) {
                damaged |= rows.damaged;
                rows.close();
                rows = null;
                done = true;
            }
            return scratch;
        }

        private void open() throws IOException {
            done = true;
            if (channel == null || channel.length() < 2) {
                return;
            }
            long start = channel.start();
            long end = Math.min(src.size, start + channel.length());
            if (start + 2 > end) {
                damaged = true;
                return;
            }
            Source.Cursor head = src.cursor(start, start + 2, 16);
            int compression = head.u16();
            int lb = lineBytes();
            int h = bottom - top;
            switch (compression) {
                case Rows.RAW -> rows = Rows.raw(src.cursor(start + 2, end, 1 << 13), lb);
                case Rows.RLE -> {
                    long table = start + 2;
                    long data = table + (long) h * (doc.big ? 4 : 2);
                    if (data > end) {
                        damaged = true;
                        return;
                    }
                    rows = Rows.rle(src.cursor(table, data, 1 << 10), src.cursor(data, end, 1 << 13), doc.big, lb);
                }
                case Rows.ZIP, Rows.ZIP_PREDICTED -> rows = Rows.zip(src.cursor(start + 2, end, 1 << 13), lb,
                        compression == Rows.ZIP_PREDICTED, doc.depth, right - left);
                default -> damaged = true;
            }
            done = rows == null;
        }

        void close() {
            if (rows != null) {
                rows.close();
                rows = null;
            }
        }

        float sample(byte[] line, int x) {
            return switch (doc.depth) {
                case 8 -> (line[x] & 0xFF) / 255f;
                case 16 -> ((line[2 * x] & 0xFF) << 8 | line[2 * x + 1] & 0xFF) / 65535f;
                case 32 -> {
                    float f = Samples.float32(line, x);
                    yield Float.isNaN(f) ? 0 : f;
                }
                default -> (line[x >> 3] >> (7 - (x & 7)) & 1) == 0 ? 1 : 0;
            };
        }

        void read(float[] into, int offset, int step, float outside) throws IOException {
            if (raster != null) {
                int ly = y - ry;
                for (int x = 0; x < width; x++) {
                    int lx = x - rx;
                    into[x * step + offset] = ly >= 0 && ly < rh && lx >= 0 && lx < rw ? raster[ly * rw + lx] : outside;
                }
                return;
            }
            if (cache != null && cachedRow == y) {
                for (int x = 0; x < width; x++) {
                    into[x * step + offset] = cache[x];
                }
                return;
            }
            load(into, offset, step, outside);
            if (cacheable) {
                if (cache == null) {
                    cache = new float[width];
                }
                for (int x = 0; x < width; x++) {
                    cache[x] = into[x * step + offset];
                }
                cachedRow = y;
            }
        }

        private void load(float[] into, int offset, int step, float outside) throws IOException {
            int row = y;
            if (!covers(row)) {
                for (int x = 0; x < width; x++) {
                    into[x * step + offset] = outside;
                }
                return;
            }
            byte[] line = line(row);
            int x0 = Math.max(0, Math.min(width, left));
            int x1 = Math.max(x0, Math.min(width, right));
            for (int x = 0; x < x0; x++) {
                into[x * step + offset] = outside;
            }
            for (int x = x1; x < width; x++) {
                into[x * step + offset] = outside;
            }
            if (line == null) {
                for (int x = x0; x < x1; x++) {
                    into[x * step + offset] = fill / 255f;
                }
                return;
            }
            for (int x = x0; x < x1; x++) {
                into[x * step + offset] = sample(line, x - left);
            }
        }
    }

    private Node build(Layers.Layer l, int depth) {
        if (l.group) {
            Group g = new Group();
            g.layer = l;
            masks(g, l);
            artboard(g, l);
            Node base = null;
            for (Layers.Layer child : l.children) {
                Node node = build(child, depth + 1);
                if (node == null) {
                    continue;
                }
                if (child.clipped && base != null) {
                    base.clipped.add(node);
                } else {
                    g.children.add(node);
                    base = node;
                }
            }
            if (depth > 0) {
                effects(l);
            }
            return g;
        }
        Pixels p = new Pixels();
        p.layer = l;
        masks(p, l);
        if (l.adjustment != null) {
            Adjustments a = Adjustments.of(l.adjustment, l.adjustData, l.generator, doc.mode, doc.depth);
            if (a == null) {
                if (!l.hidden) {
                    skipped.add("adjustment layer '" + l.name + "'");
                }
                return null;
            }
            Adjust adjust = new Adjust();
            adjust.layer = l;
            adjust.adjustment = a;
            masks(adjust, l);
            if (!Blend.known(l.blend)) {
                l.blend = Blend.NORMAL;
            }
            return adjust;
        }
        boolean softVector = l.vectorMask && l.vector != null && (l.vectorDensity < 255 || l.vectorFeather > 0);
        boolean soft = l.solid != null
                && (softVector || l.masks.stream().anyMatch(m -> !m.disabled && m.feather > 0));
        boolean pixels = !soft && !l.empty() && l.channel(0) != null && l.channel(0).length() > 2;
        if (pixels) {
            if (l.strokeApart && strokeInPixels(p, l)) {
                l.strokeApart = false;
            }
            boolean stroked = l.strokeApart && l.vectorStroke != null && l.vectorStroke.flag("strokeEnabled", false);
            p.vectorBaked |= (l.fill || l.solid != null) && !stroked && l.vectorDensity >= 255
                    && !(l.vectorFeather > 0);
            p.color = new Plane[n];
            for (int i = 0; i < n; i++) {
                p.color[i] = new Plane(l.channel(i), l.top, l.left, l.bottom, l.right, 0);
            }
            Layers.Channel t = l.channel(-1);
            if (t != null) {
                p.transparency = new Plane(t, l.top, l.left, l.bottom, l.right, 0);
            }
        } else if (l.solid != null) {
            p.solid = solid(l.solid);
        } else if (l.fill) {
            p.gradient = l.gradient == null ? null : Gradient.of(Descriptor.read(l.gradient, 4), n, this::solid);
            if (p.gradient == null && l.pattern != null) {
                Descriptor d = Descriptor.read(l.pattern, 4);
                p.pattern = pattern(d);
                p.patternPlace = place(d, 0, 0);
            }
            if (p.gradient == null && p.pattern == null) {
                if (!l.hidden) {
                    skipped.add("gradient or pattern fill layer '" + l.name + "'");
                }
                return null;
            }
        }
        if (!Blend.known(l.blend)) {
            skipped.add("blend mode of '" + l.name + "'");
            l.blend = Blend.NORMAL;
        }
        vectorStroke(p, l);
        if (l.overlay != null && l.overlay.pattern() != null && pattern(l.overlay.pattern()) == null) {
            if (!l.hidden) {
                effects.add("pattern overlay");
            }
            l.overlay = null;
        }
        effects(l);
        return p;
    }

    private boolean strokeInPixels(Node p, Layers.Layer l) {
        Layers.Channel t = l.channel(-1);
        if (t == null || p.vector == null) {
            return false;
        }
        java.awt.geom.Rectangle2D b = p.vector.shape();
        int row = (int) Math.floor(b.getMinY()) - 1;
        int col = (int) Math.floor(b.getCenterX());
        if (b.isEmpty() || row < l.top || row >= l.bottom || col < l.left || col >= l.right) {
            return false;
        }
        Plane plane = new Plane(t, l.top, l.left, l.bottom, l.right, 0);
        if (!plane.covers(row)) {
            return false;
        }
        try {
            byte[] line = plane.line(row);
            return line != null && plane.sample(line, col - l.left) > 0;
        } catch (IOException e) {
            return false;
        } finally {
            plane.close();
        }
    }

    private Pattern pattern(Descriptor d) {
        Descriptor ref = d == null ? null : d.object("Ptrn");
        Object id = ref == null ? null : ref.get("Idnt");
        if (!(id instanceof String s)) {
            return null;
        }
        String key = s.replace("\0", "");
        if (patterns.containsKey(key)) {
            return patterns.get(key);
        }
        Pattern found;
        try {
            found = Pattern.find(src, patternBlocks, key, doc);
        } catch (IOException | RuntimeException e) {
            found = null;
        }
        if (found != null && patternBytes + found.bytes() > MAX_PATTERN_BYTES) {
            found = null;
        }
        if (found != null) {
            patternBytes += found.bytes();
        }
        patterns.put(key, found);
        return found;
    }

    private static double[] place(Descriptor d, double x, double y) {
        Descriptor phase = d == null ? null : d.object("phase");
        double scale = d == null ? 100 : d.number("Scl ", 100);
        double px = phase == null ? 0 : phase.number("Hrzn", 0);
        double py = phase == null ? 0 : phase.number("Vrtc", 0);
        return new double[] {x + px, y + py, Math.max(1, Math.min(1000, scale)) / 100};
    }

    private double[] bounds(Pixels p) {
        Layers.Layer l = p.layer;
        if (p.color != null) {
            return new double[] {l.left, l.top, l.right, l.bottom};
        }
        if (p.vector != null) {
            java.awt.geom.Rectangle2D b = p.vector.shape();
            if (b.getWidth() > 0 && b.getHeight() > 0) {
                return new double[] {b.getMinX(), b.getMinY(), b.getMaxX(), b.getMaxY()};
            }
        }
        return new double[] {0, 0, width, doc.height};
    }

    private void vectorStroke(Pixels p, Layers.Layer l) {
        Descriptor d = l.vectorStroke;
        if (d == null || p.vector == null || p.color != null && !l.strokeApart) {
            return;
        }
        p.noFill = p.color == null && !d.flag("fillEnabled", true);
        if (!d.flag("strokeEnabled", false)) {
            return;
        }
        Descriptor content = d.object("strokeStyleContent");
        float[] color = content == null ? null : content.color("Clr ");
        if (color == null) {
            effects.add("shape stroke");
            return;
        }
        double w = d.number("strokeStyleLineWidth", 1);
        if (d.get("strokeStyleLineWidth") instanceof Descriptor.Unit u && u.unit().equals("#Pnt")) {
            w = w * doc.xDpi / 72;
        }
        String align = d.get("strokeStyleLineAlignment") instanceof Descriptor.Enum e ? e.value() : null;
        p.line = p.vector.stroke(Math.max(0, w), align);
        p.lineColor = solid(color);
        p.lineOpacity = (float) (d.number("strokeStyleOpacity", 100) / 100);
    }

    private void effects(Layers.Layer l) {
        if (!l.hidden) {
            effects.addAll(l.effects);
            if (l.blendRangeFlags != 0) {
                effects.add("blend if ranges");
            }
        }
    }

    private float[] solid(float[] c) {
        float[] v = new float[n];
        if (c.length == 6 && doc.mode == PsdDocument.LAB) {
            System.arraycopy(c, 3, v, 0, 3);
        } else if (c.length == 5) {
            float r = (1 - c[0]) * (1 - c[3]);
            float g = (1 - c[1]) * (1 - c[3]);
            float b = (1 - c[2]) * (1 - c[3]);
            fillFrom(v, new float[] {r, g, b}, c);
        } else if (c.length == 1) {
            fillFrom(v, new float[] {c[0], c[0], c[0]}, null);
        } else {
            fillFrom(v, c, null);
        }
        return v;
    }

    private void fillFrom(float[] v, float[] rgbc, float[] cmyk) {
        switch (doc.mode) {
            case PsdDocument.CMYK -> {
                if (cmyk != null) {
                    for (int i = 0; i < 4; i++) {
                        v[i] = 1 - cmyk[i];
                    }
                } else {
                    float k = 1 - Math.max(rgbc[0], Math.max(rgbc[1], rgbc[2]));
                    for (int i = 0; i < 3; i++) {
                        v[i] = k >= 1 ? 1 : 1 - (1 - rgbc[i] - k) / (1 - k);
                    }
                    v[3] = 1 - k;
                }
            }
            case PsdDocument.RGB -> {
                for (int i = 0; i < 3; i++) {
                    v[i] = rgbc[i];
                }
            }
            case PsdDocument.LAB -> System.arraycopy(Colors.toLab(rgbc), 0, v, 0, 3);
            default -> {
                float g = 0.3f * rgbc[0] + 0.59f * rgbc[1] + 0.11f * rgbc[2];
                v[0] = g;
            }
        }
    }

    private void masks(Node node, Layers.Layer l) {
        boolean real = false;
        for (Layers.Mask m : l.masks) {
            real |= m.channel == -3 && !m.disabled;
        }
        for (Layers.Mask m : l.masks) {
            if (m.disabled || real && m.fromVector && l.vectorMask && l.vector != null) {
                continue;
            }
            Layers.Channel ch = l.channel(m.channel);
            if (ch == null) {
                continue;
            }
            boolean rendered = m.fromVector && l.vectorMask && l.vector != null;
            if (m.fromVector && !rendered) {
                continue;
            }
            Plane plane = new Plane(ch, m.top, m.left, m.bottom, m.right, m.fill);
            plane.density = (rendered ? l.vectorDensity : m.density) / 255f;
            double feather = rendered ? l.vectorFeather : m.feather;
            plane.feather = Double.isFinite(feather) ? Math.min(1000, Math.max(0, feather)) : 0;
            node.masks.add(plane);
            node.vectorBaked |= rendered;
        }
        if (l.vectorMask && l.vector != null) {
            node.vector = VectorMask.of(l.vector, doc.width, doc.height);
            node.vectorDensity = l.vectorDensity / 255f;
        }
    }

    private float[] shapeRow() {
        return shapes.isEmpty() ? new float[width] : shapes.remove(shapes.size() - 1);
    }

    private void releaseShape(float[] s) {
        if (s != null && s.length == width && shapes.size() < 64) {
            shapes.add(s);
        }
    }

    private float[] buffer() {
        return pool.isEmpty() ? new float[width * stride] : pool.remove(pool.size() - 1);
    }

    private void release(float[] b) {
        if (pool.size() < 16) {
            pool.add(b);
        }
    }

    private void artboard(Group g, Layers.Layer l) {
        Descriptor d = l.artboard == null || l.artboard.length < 8 ? null : Descriptor.read(l.artboard, 4);
        Descriptor r = d == null ? null : d.object("artboardRect");
        if (r == null) {
            return;
        }
        int type = (int) d.number("artboardBackgroundType", 3);
        float[] c = switch (type) {
            case 1 -> new float[] {1, 1, 1};
            case 2 -> new float[] {0, 0, 0};
            case 4 -> d.color("Clr ");
            default -> null;
        };
        if (c == null) {
            return;
        }
        g.background = solid(c);
        g.board = new int[] {(int) Math.round(r.number("Left", 0)), (int) Math.round(r.number("Top ", 0)),
            (int) Math.round(r.number("Rght", 0)), (int) Math.round(r.number("Btom", 0))};
    }

    private void children(Node group, float[] dest) throws IOException {
        Group board = (Group) group;
        if (board.background != null && y >= board.board[1] && y < board.board[3]) {
            for (int x = Math.max(0, board.board[0]); x < Math.min(width, board.board[2]); x++) {
                int at = x * stride;
                System.arraycopy(board.background, 0, dest, at, n);
                dest[at + n] = 1;
            }
        }
        List<Node> list = board.children;
        float[] savedShallow = shallowBase;
        boolean root = group == top;
        boolean own = false;
        if (!root && knocks(list, 1)) {
            shallowBase = buffer();
            System.arraycopy(dest, 0, shallowBase, 0, dest.length);
            own = true;
        }
        try {
            for (int i = 0; i < list.size(); i++) {
                Node child = list.get(i);
                if (child.layer.hidden) {
                    continue;
                }
                item(child, dest);
                if (root && i == 0 && background(child)) {
                    deepBase = buffer();
                    System.arraycopy(dest, 0, deepBase, 0, dest.length);
                    shallowBase = deepBase;
                }
            }
        } finally {
            if (own) {
                release(shallowBase);
            }
            if (root && deepBase != null) {
                release(deepBase);
                deepBase = null;
            }
            shallowBase = savedShallow;
        }
    }

    private static boolean adjusts(Group g) {
        for (Node c : g.children) {
            if (c instanceof Adjust && !c.layer.hidden) {
                return true;
            }
        }
        return false;
    }

    private static boolean knocks(List<Node> list, int kind) {
        for (Node c : list) {
            if (c.layer.knockout == kind && !c.layer.hidden && !(c instanceof Adjust)) {
                return true;
            }
        }
        return false;
    }

    private static boolean background(Node n) {
        return n instanceof Pixels p && p.color != null && p.transparency == null && p.layer.masks.isEmpty()
                && n.vector == null && n.layer.blend == Blend.NORMAL;
    }

    private void knockout(Node base, float[] dest) throws IOException {
        Layers.Layer l = base.layer;
        float[] knock = l.knockout == 1 ? shallowBase : deepBase;
        float[] src = buffer();
        float[] tmp = buffer();
        float[] shape = null;
        try {
            shape = coverage(base, src);
            if (knock == null) {
                Arrays.fill(tmp, 0);
            } else {
                System.arraycopy(knock, 0, tmp, 0, tmp.length);
            }
            boolean group = base instanceof Group;
            composite(tmp, src, l.blend == Blend.PASS_THROUGH ? Blend.NORMAL : l.blend,
                    group ? l.fillOpacity / 255f : 1, null);
            float opacity = l.opacity / 255f;
            for (int x = 0; x < width; x++) {
                float t = shape[x] * opacity;
                if (t <= 0) {
                    continue;
                }
                int at = x * stride;
                float ab = dest[at + n];
                float ak = tmp[at + n];
                float ao = ab + (ak - ab) * t;
                for (int i = 0; i < n; i++) {
                    float pb = dest[at + i] * ab;
                    float pk = tmp[at + i] * ak;
                    dest[at + i] = ao <= 0 ? tmp[at + i] : (pb + (pk - pb) * t) / ao;
                }
                dest[at + n] = ao;
            }
        } finally {
            releaseShape(shape);
            release(src);
            release(tmp);
        }
    }

    private void item(Node base, float[] dest) throws IOException {
        Layers.Layer l = base.layer;
        if (base instanceof Adjust a) {
            adjust(a, dest, null);
            return;
        }
        if (l.knockout != 0 && base.clipped.isEmpty()) {
            knockout(base, dest);
            return;
        }
        float opacity = l.opacity / 255f * (base instanceof Group ? l.fillOpacity / 255f : 1);
        if (base instanceof Group g && l.blend == Blend.PASS_THROUGH && (l.fillOpacity >= 255 || !adjusts(g))
                && (base.clipped.isEmpty() || !adjusts(g))) {
            if (base.clipped.isEmpty()) {
                LayerEffects fx = base.fx;
                float fxOpacity = l.opacity / 255f;
                if (fx != null && fx.shadow != null) {
                    drawEffect(dest, fx, fx.shadow, l.shadow.color(), l.shadow.opacity(), l.shadow.blend(), fxOpacity,
                            null);
                }
                if (fx != null && fx.stroke != null && fx.strokeUnder) {
                    drawStroke(dest, base, fxOpacity);
                }
                passThrough(g, dest, opacity);
                if (fx != null && fx.stroke != null && !fx.strokeUnder) {
                    drawStroke(dest, base, fxOpacity);
                }
                return;
            }
            float[] isolated = buffer();
            float[] shape = coverage(g, isolated);
            release(isolated);
            passThrough(g, dest, opacity);
            for (Node c : base.clipped) {
                if (c.layer.hidden) {
                    continue;
                }
                clip(c, dest, shape);
            }
            releaseShape(shape);
            return;
        }
        if (base.clipped.isEmpty()) {
            LayerEffects fx = base.fx;
            float fxOpacity = l.opacity / 255f;
            float[] src = buffer();
            float[] shape = coverage(base, src);
            int lo = spanLo;
            int hi = spanHi;
            if (fx != null && fx.shadow != null) {
                drawEffect(dest, fx, fx.shadow, l.shadow.color(), l.shadow.opacity(), l.shadow.blend(), fxOpacity,
                        l.shadow.conceal() && !(l.shadow.spread() > 0) ? shape : null);
            }
            if (fx != null && fx.stroke != null && fx.strokeUnder) {
                drawStroke(dest, base, fxOpacity);
            }
            releaseShape(shape);
            composite(dest, src, l.blend, opacity, null, lo, hi);
            release(src);
            if (fx != null && fx.stroke != null && !fx.strokeUnder) {
                drawStroke(dest, base, fxOpacity);
            }
            return;
        }
        LayerEffects fx = base.fx;
        float fxOpacity = l.opacity / 255f;
        float[] src = buffer();
        float[] shape = coverage(base, src);
        if (fx != null && fx.shadow != null) {
            drawEffect(dest, fx, fx.shadow, l.shadow.color(), l.shadow.opacity(), l.shadow.blend(), fxOpacity,
                    l.shadow.conceal() && !(l.shadow.spread() > 0) ? shape : null);
        }
        if (fx != null && fx.stroke != null && fx.strokeUnder) {
            drawStroke(dest, base, fxOpacity);
        }
        if (!l.blendClipped) {
            composite(dest, src, l.blend, opacity, null);
            for (Node c : base.clipped) {
                if (c.layer.hidden) {
                    continue;
                }
                clip(c, dest, shape);
            }
        } else {
            for (Node c : base.clipped) {
                if (c.layer.hidden) {
                    continue;
                }
                clip(c, src, shape);
            }
            composite(dest, src, l.blend, opacity, null);
        }
        release(src);
        releaseShape(shape);
        if (fx != null && fx.stroke != null && !fx.strokeUnder) {
            drawStroke(dest, base, fxOpacity);
        }
    }

    private void clip(Node c, float[] dest, float[] shape) throws IOException {
        if (c instanceof Adjust a) {
            adjust(a, dest, shape);
            return;
        }
        float[] s = buffer();
        releaseShape(coverage(c, s));
        composite(dest, s, c.layer.blend, c.layer.opacity / 255f, shape, spanLo, spanHi);
        release(s);
    }

    private void adjust(Adjust a, float[] dest, float[] clip) throws IOException {
        Layers.Layer l = a.layer;
        float[] m = mask(a);
        float opacity = l.opacity / 255f * l.fillOpacity / 255f;
        float[] changed = new float[Math.max(n, 3)];
        float[] mixed = new float[3];
        boolean whole = Blend.whole(l.blend) && rgb;
        boolean separable = Blend.separable(l.blend) && doc.mode != PsdDocument.LAB;
        for (int x = 0; x < width; x++) {
            int at = x * stride;
            float t = opacity * (m == null ? 1 : m[x]) * (clip == null ? 1 : clip[x]);
            if (t <= 0 || dest[at + n] <= 0) {
                continue;
            }
            a.adjustment.apply(dest, at, n, changed);
            if (whole) {
                float[] src = {changed[0], changed[1], changed[2]};
                Blend.pixel(l.blend, dest, at, src, 0, mixed);
            }
            for (int i = 0; i < n; i++) {
                float cb = dest[at + i];
                float b = whole ? Colors.clamp(mixed[i]) : separable ? Blend.channel(l.blend, cb, changed[i]) : changed[i];
                dest[at + i] = cb + (b - cb) * t;
            }
        }
    }

    private void passThrough(Group g, float[] dest, float opacity) throws IOException {
        boolean masked = !g.masks.isEmpty() || g.vector != null;
        if (opacity >= 1 && !masked) {
            children(g, dest);
            return;
        }
        float[] before = buffer();
        System.arraycopy(dest, 0, before, 0, dest.length);
        children(g, dest);
        float[] m = mask(g);
        for (int x = 0; x < width; x++) {
            float t = opacity * (m == null ? 1 : m[x]);
            if (t >= 1) {
                continue;
            }
            int at = x * stride;
            float ab = before[at + n];
            float ad = dest[at + n];
            float a = ab + (ad - ab) * t;
            for (int i = 0; i < n; i++) {
                float pc = before[at + i] * ab + (dest[at + i] * ad - before[at + i] * ab) * t;
                dest[at + i] = a > 0 ? pc / a : before[at + i];
            }
            dest[at + n] = a;
        }
        release(before);
    }

    private float[] mask(Node node) throws IOException {
        if (node.masks.isEmpty() && (node.vector == null || node.vectorBaked)) {
            return null;
        }
        if (maskRow == null) {
            maskRow = new float[width];
            maskTmp = new float[width];
        }
        float[] m = maskRow;
        Arrays.fill(m, 1);
        float[] tmp = maskTmp;
        for (Plane p : node.masks) {
            p.read(tmp, 0, 1, p.fill / 255f);
            for (int x = 0; x < width; x++) {
                m[x] *= 1 - p.density * (1 - tmp[x]);
            }
        }
        if (node.vector != null && !node.vectorBaked) {
            node.vector.row(y, tmp);
            for (int x = 0; x < width; x++) {
                m[x] *= 1 - node.vectorDensity * (1 - tmp[x]);
            }
        }
        return m;
    }

    private float[] coverage(Node node, float[] out) throws IOException {
        Layers.Layer l = node.layer;
        if (node instanceof Group g) {
            Arrays.fill(out, 0);
            float[] savedDeep = deepBase;
            float[] savedShallow = shallowBase;
            deepBase = null;
            shallowBase = null;
            try {
                children(g, out);
            } finally {
                deepBase = savedDeep;
                shallowBase = savedShallow;
            }
            float[] m = mask(g);
            if (m != null) {
                for (int x = 0; x < width; x++) {
                    out[x * stride + n] *= m[x];
                }
            }
            float[] shape = shapeRow();
            for (int x = 0; x < width; x++) {
                shape[x] = out[x * stride + n];
            }
            spanLo = 0;
            spanHi = width;
            return shape;
        }
        Pixels p = (Pixels) node;
        float fill = l.fillOpacity / 255f;
        float base = l.interior ? 1 : fill;
        float scale = l.interior ? fill : 1;
        if (p.color != null) {
            for (int i = 0; i < n; i++) {
                p.color[i].read(out, i, stride, 0);
            }
            if (p.transparency != null) {
                p.transparency.read(out, n, stride, 0);
            } else {
                for (int x = 0; x < width; x++) {
                    out[x * stride + n] = x >= l.left && x < l.right && y >= l.top && y < l.bottom ? 1 : 0;
                }
            }
        } else if (p.gradient != null) {
            double[] b = bounds(p);
            p.gradient.row(y, width, b[0], b[1], b[2], b[3], out, stride, n);
        } else if (p.pattern != null) {
            p.pattern.row(y, width, p.patternPlace[0], p.patternPlace[1], p.patternPlace[2], out, stride, n);
        } else if (p.solid != null) {
            for (int x = 0; x < width; x++) {
                int at = x * stride;
                System.arraycopy(p.solid, 0, out, at, n);
                out[at + n] = 1;
            }
        } else {
            Arrays.fill(out, 0);
            float[] none = shapeRow();
            Arrays.fill(none, 0);
            spanLo = 0;
            spanHi = 0;
            return none;
        }
        float[] m = mask(node);
        float[] shape = shapeRow();
        Effects.Overlay overlay = l.overlay;
        float[] tint = overlay == null || overlay.color() == null ? null : solid(overlay.color());
        float[] ramp = null;
        if (overlay != null && overlay.gradient() != null) {
            if (p.overlayGradient == null) {
                p.overlayGradient = Gradient.of(overlay.gradient(), n, this::solid);
            }
            if (p.overlayGradient == null) {
                effects.add("gradient overlay");
            } else {
                ramp = buffer();
                double[] b = bounds(p);
                p.overlayGradient.row(y, width, b[0], b[1], b[2], b[3], ramp, stride, n);
            }
        }
        if (overlay != null && overlay.pattern() != null) {
            if (p.overlayPattern == null) {
                p.overlayPattern = pattern(overlay.pattern());
                double[] b = bounds(p);
                boolean linked = overlay.pattern().flag("Algn", true);
                p.overlayPlace = place(overlay.pattern(), linked ? b[0] : 0, linked ? b[1] : 0);
            }
            if (p.overlayPattern != null) {
                ramp = buffer();
                p.overlayPattern.row(y, width, p.overlayPlace[0], p.overlayPlace[1], p.overlayPlace[2], ramp, stride, n);
            }
        }
        float[] mixed = new float[3];
        float[] local = new float[n];
        for (int x = 0; x < width; x++) {
            int at = x * stride + n;
            float a = out[at] * (m == null ? 1 : m[x]);
            shape[x] = a;
            if (tint == null && ramp == null) {
                out[at] = a * fill;
                continue;
            }
            int c0 = x * stride;
            float strength = overlay.opacity();
            if (ramp != null) {
                System.arraycopy(ramp, c0, local, 0, n);
                tint = local;
                strength *= ramp[c0 + n];
            }
            if (Blend.whole(overlay.blend()) && rgb) {
                Blend.pixel(overlay.blend(), out, c0, tint, 0, mixed);
            }
            for (int i = 0; i < n; i++) {
                float cb = out[c0 + i];
                float b = Blend.whole(overlay.blend()) && rgb ? Colors.clamp(mixed[i])
                        : Blend.separable(overlay.blend()) ? Blend.channel(overlay.blend(), cb, tint[i]) : tint[i];
                float mine = a * base;
                float fx = a * strength;
                float total = fx + mine * (1 - strength);
                out[c0 + i] = total <= 0 ? cb : (b * fx + cb * mine * (1 - strength)) / total;
            }
            out[at] = (a * base + (1 - base) * a * strength) * scale;
        }
        if (ramp != null) {
            release(ramp);
        }
        if (p.noFill) {
            for (int x = 0; x < width; x++) {
                out[x * stride + n] = 0;
                shape[x] = 0;
            }
        }
        if (p.line != null) {
            float[] line = new float[width];
            p.line.row(y, line);
            for (int x = 0; x < width; x++) {
                float as = line[x] * p.lineOpacity;
                if (as <= 0) {
                    continue;
                }
                int at = x * stride;
                float af = out[at + n];
                float ao = as + af * (1 - as);
                for (int i = 0; i < n; i++) {
                    out[at + i] = (p.lineColor[i] * as + out[at + i] * af * (1 - as)) / ao;
                }
                out[at + n] = ao;
                shape[x] = Math.max(shape[x], line[x]);
            }
        }
        if (l.blend == Blend.DISSOLVE) {
            for (int x = 0; x < width; x++) {
                int at = x * stride + n;
                float a = out[at] * l.opacity / 255f;
                out[at] = noise(x, y) < a ? 255f / Math.max(1, l.opacity) : 0;
            }
        }
        if (p.color != null && p.line == null) {
            boolean inside = y >= l.top && y < l.bottom;
            spanLo = inside ? Math.max(0, Math.min(width, l.left)) : 0;
            spanHi = inside ? Math.max(spanLo, Math.min(width, l.right)) : 0;
        } else {
            spanLo = 0;
            spanHi = width;
        }
        return shape;
    }

    private static float noise(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ h >>> 13) * 1274126177;
        return ((h ^ h >>> 16) & 0xFFFFFF) / (float) 0x1000000;
    }

    private void composite(float[] dest, float[] src, int mode, float opacity, float[] clip) {
        composite(dest, src, mode, opacity, clip, 0, width);
    }

    private void composite(float[] dest, float[] src, int mode, float opacity, float[] clip, int lo, int hi) {
        boolean cmyk = doc.mode == PsdDocument.CMYK;
        float[] mixed = new float[4];
        boolean whole = Blend.whole(mode) && (rgb || cmyk);
        boolean separable = Blend.separable(mode) && doc.mode != PsdDocument.LAB;
        if (!whole && !separable) {
            for (int x = lo; x < hi; x++) {
                int at = x * stride;
                float as = src[at + n] * opacity * (clip == null ? 1 : clip[x]);
                if (as <= 0) {
                    continue;
                }
                if (as >= 1) {
                    System.arraycopy(src, at, dest, at, n);
                    dest[at + n] = 1;
                    continue;
                }
                float kb = dest[at + n] * (1 - as);
                float ao = as + kb;
                for (int i = 0; i < n; i++) {
                    dest[at + i] = (kb * dest[at + i] + as * src[at + i]) / ao;
                }
                dest[at + n] = ao;
            }
            return;
        }
        for (int x = lo; x < hi; x++) {
            int at = x * stride;
            float as = src[at + n] * opacity * (clip == null ? 1 : clip[x]);
            if (as <= 0) {
                continue;
            }
            float ab = dest[at + n];
            float ao = as + ab - as * ab;
            if (whole && ab > 0) {
                if (cmyk) {
                    Blend.pixelCmyk(mode, dest, at, src, at, mixed);
                } else {
                    Blend.pixel(mode, dest, at, src, at, mixed);
                }
            }
            for (int i = 0; i < n; i++) {
                float cb = dest[at + i];
                float cs = src[at + i];
                float b;
                if (whole) {
                    b = ab > 0 ? Colors.clamp(mixed[i]) : cs;
                } else if (separable) {
                    b = Blend.channel(mode, cb, cs);
                } else {
                    b = cs;
                }
                dest[at + i] = ((1 - as) * ab * cb + as * (1 - ab) * cs + as * ab * b) / ao;
            }
            dest[at + n] = ao;
        }
    }

    private void effects(Node node, long[] budget) throws IOException {
        if (node != top && node.layer.hidden) {
            return;
        }
        for (Node c : node.clipped) {
            if (!c.layer.hidden && c.layer.stroke != null) {
                effects.add("stroke");
            }
            if (!c.layer.hidden && c.layer.shadow != null) {
                effects.add("drop shadow");
            }
        }
        if (node instanceof Group g) {
            for (Node c : g.children) {
                effects(c, budget);
            }
        }
        if (!(node instanceof Pixels || node instanceof Group) || node == top || node.layer.hidden
                || node.layer.stroke == null && node.layer.shadow == null) {
            return;
        }
        Layers.Layer l = node.layer;
        int e = LayerEffects.extent(l.stroke, l.shadow);
        int x0 = 0;
        int y0 = 0;
        int x1 = width;
        int y1 = doc.height;
        if (node instanceof Group g) {
            int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            if (extent(g, b)) {
                x0 = b[0];
                y0 = b[1];
                x1 = b[2];
                y1 = b[3];
            }
        } else if (node instanceof Pixels p && p.color != null) {
            x0 = l.left;
            y0 = l.top;
            x1 = l.right;
            y1 = l.bottom;
        } else if (node instanceof Pixels p && p.vector != null) {
            java.awt.geom.Rectangle2D b = p.vector.shape();
            x0 = (int) Math.floor(b.getMinX());
            y0 = (int) Math.floor(b.getMinY());
            x1 = (int) Math.ceil(b.getMaxX());
            y1 = (int) Math.ceil(b.getMaxY());
        }
        x0 = Math.max(0, x0 - e);
        y0 = Math.max(0, y0 - e);
        x1 = Math.min(width, x1 + e);
        y1 = Math.min(doc.height, y1 + e);
        long area = (long) Math.max(0, x1 - x0) * Math.max(0, y1 - y0);
        if (area == 0 || area > LayerEffects.MAX_PIXELS || area > budget[0]) {
            if (l.stroke != null) {
                effects.add("stroke");
            }
            if (l.shadow != null) {
                effects.add("drop shadow");
            }
            l.stroke = null;
            l.shadow = null;
            return;
        }
        budget[0] -= area;
        Node copy = build(l, node instanceof Group ? 1 : 0);
        if (copy == null) {
            return;
        }
        cacheRows(copy, false);
        int w = x1 - x0;
        int h = y1 - y0;
        float[] alpha = new float[(int) area];
        float[] row = new float[width * stride];
        Layers.Layer saved = l;
        int fill = l.fillOpacity;
        Effects.Overlay overlay = l.overlay;
        l.fillOpacity = 255;
        l.overlay = null;
        try {
            for (int yy = y0; yy < y1; yy++) {
                if ((yy & 63) == 0) {
                    PsdPage.checkpoint();
                }
                y = yy;
                float[] shape = coverage(copy, row);
                System.arraycopy(shape, x0, alpha, (yy - y0) * w, w);
                releaseShape(shape);
            }
        } finally {
            saved.fillOpacity = fill;
            saved.overlay = overlay;
            y = 0;
            close(copy);
        }
        node.fx = LayerEffects.build(alpha, x0, y0, w, h, l.stroke, l.shadow, doc.globalAngle,
                l.fillOpacity >= 255);
    }

    private static boolean extent(Group g, int[] b) {
        boolean found = false;
        for (Node c : g.children) {
            if (c.layer.hidden) {
                continue;
            }
            if (c instanceof Group inner) {
                found |= extent(inner, b);
            } else if (c instanceof Pixels p && p.color != null) {
                b[0] = Math.min(b[0], c.layer.left);
                b[1] = Math.min(b[1], c.layer.top);
                b[2] = Math.max(b[2], c.layer.right);
                b[3] = Math.max(b[3], c.layer.bottom);
                found = true;
            } else if (!(c instanceof Adjust)) {
                b[0] = Math.min(b[0], 0);
                b[1] = Math.min(b[1], 0);
                b[2] = Math.max(b[2], Integer.MAX_VALUE / 2);
                b[3] = Math.max(b[3], Integer.MAX_VALUE / 2);
                found = true;
            }
        }
        return found;
    }

    private void feathers(Node node, long[] budget) throws IOException {
        if (node != top && node.layer.hidden) {
            return;
        }
        if (node instanceof Group g) {
            for (Node c : g.children) {
                feathers(c, budget);
            }
        }
        for (Node c : node.clipped) {
            feathers(c, budget);
        }
        if (node.layer.hidden) {
            return;
        }
        for (Plane p : node.masks) {
            if (p.feather > 0 && !blurred(p, p.top, p.left, p.bottom, p.right, p.feather, budget, null)) {
                effects.add("mask feathering");
            }
        }
        double vf = node.layer.vectorFeather;
        if (node.vector != null && !node.vectorBaked && Double.isFinite(vf) && vf > 0) {
            java.awt.geom.Rectangle2D b = node.vector.shape();
            Plane p = new Plane(null, 0, 0, 0, 0, 0);
            p.density = node.vectorDensity;
            if (blurred(p, (int) Math.floor(b.getMinY()), (int) Math.floor(b.getMinX()), (int) Math.ceil(b.getMaxY()),
                    (int) Math.ceil(b.getMaxX()), Math.min(1000, vf), budget, node.vector)) {
                node.vector = null;
                node.masks.add(p);
            } else {
                effects.add("mask feathering");
            }
        }
    }

    private boolean blurred(Plane p, int top, int left, int bottom, int right, double feather, long[] budget,
            VectorMask vector) throws IOException {
        int e = (int) Math.ceil(feather * 3) + 2;
        int x0 = Math.max(0, left - e);
        int y0 = Math.max(0, top - e);
        int x1 = Math.min(width, right + e);
        int y1 = Math.min(doc.height, bottom + e);
        long area = (long) Math.max(0, x1 - x0) * Math.max(0, y1 - y0);
        if (area == 0 || area > LayerEffects.MAX_PIXELS || area > budget[0]) {
            return area == 0;
        }
        budget[0] -= area;
        int w = x1 - x0;
        int h = y1 - y0;
        float[] r = new float[(int) area];
        float[] row = new float[width];
        try {
            for (int yy = y0; yy < y1; yy++) {
                if ((yy & 63) == 0) {
                    PsdPage.checkpoint();
                }
                y = yy;
                if (vector != null) {
                    vector.row(yy, row);
                } else {
                    p.read(row, 0, 1, p.fill / 255f);
                }
                System.arraycopy(row, x0, r, (yy - y0) * w, w);
            }
        } finally {
            y = 0;
            p.close();
        }
        int radius = (int) Math.max(1, Math.round(Math.sqrt(feather * feather + 0.25) - 0.5));
        for (int pass = 0; pass < 3; pass++) {
            LayerEffects.blur(r, w, h, radius);
        }
        p.raster = r;
        p.rx = x0;
        p.ry = y0;
        p.rw = w;
        p.rh = h;
        return true;
    }

    private void drawStroke(float[] dest, Node node, float layerOpacity) {
        Layers.Layer l = node.layer;
        LayerEffects fx = node.fx;
        if (l.stroke.gradient() == null) {
            drawEffect(dest, fx, fx.stroke, l.stroke.color(), l.stroke.opacity(), l.stroke.blend(), layerOpacity, null);
            return;
        }
        if (node.strokeGradient == null) {
            node.strokeGradient = Gradient.of(l.stroke.gradient(), n, this::solid);
            if (node.strokeGradient == null) {
                drawEffect(dest, fx, fx.stroke, l.stroke.color(), l.stroke.opacity(), l.stroke.blend(), layerOpacity,
                        null);
                return;
            }
        }
        double[] b = node instanceof Pixels p ? bounds(p)
                : new double[] {fx.left, fx.top, fx.left + fx.width, fx.top + fx.height};
        float[] src = buffer();
        node.strokeGradient.row(y, width, b[0], b[1], b[2], b[3], src, stride, n);
        for (int x = 0; x < width; x++) {
            int at = x * stride + n;
            src[at] *= fx.at(fx.stroke, x, y) * l.stroke.opacity();
        }
        composite(dest, src, l.stroke.blend(), layerOpacity, null);
        release(src);
    }

    private void drawEffect(float[] dest, LayerEffects fx, byte[] raster, float[] color, float opacity, int blend,
            float layerOpacity, float[] conceal) {
        float[] src = buffer();
        float[] tint = solid(color);
        for (int x = 0; x < width; x++) {
            int at = x * stride;
            System.arraycopy(tint, 0, src, at, n);
            src[at + n] = fx.at(raster, x, y) * opacity * (conceal == null ? 1 : 1 - conceal[x]);
        }
        composite(dest, src, blend, layerOpacity, null);
        release(src);
    }

    private void cacheRows(Node node, boolean on) {
        boolean here = on || node instanceof Group && node.layer.blend == Blend.PASS_THROUGH && !node.clipped.isEmpty();
        if (here) {
            for (Plane p : node.masks) {
                p.cacheable = true;
            }
            if (node instanceof Pixels p) {
                if (p.color != null) {
                    for (Plane c : p.color) {
                        c.cacheable = true;
                    }
                }
                if (p.transparency != null) {
                    p.transparency.cacheable = true;
                }
            }
        }
        if (node instanceof Group g) {
            for (Node c : g.children) {
                cacheRows(c, here);
            }
        }
        for (Node c : node.clipped) {
            cacheRows(c, on);
        }
    }

    private void close(Node node) {
        if (node instanceof Group g) {
            for (Node c : g.children) {
                close(c);
            }
        }
        for (Node c : node.clipped) {
            close(c);
        }
        for (Plane p : node.masks) {
            p.close();
        }
        if (node instanceof Pixels p) {
            if (p.color != null) {
                for (Plane c : p.color) {
                    c.close();
                }
            }
            if (p.transparency != null) {
                p.transparency.close();
            }
        }
    }
}
