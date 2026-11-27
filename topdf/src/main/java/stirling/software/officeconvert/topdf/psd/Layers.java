package stirling.software.officeconvert.topdf.psd;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class Layers {

    static final int MAX_LAYERS = 8_000;

    static final int MAX_DEPTH = 64;

    static final long MAX_KEPT_BYTES = 64L << 20;

    private long kept;

    private boolean overKept;

    static final int SIG_8BIM = 0x3842494D;

    static final int SIG_8B64 = 0x38423634;

    record Channel(int id, long start, long length) {}

    static final class Mask {
        int top;
        int left;
        int bottom;
        int right;
        int fill;
        boolean disabled;
        boolean invert;
        boolean fromVector;
        int density = 255;
        double feather;
        int channel = -2;

        boolean empty() {
            return bottom <= top || right <= left;
        }
    }

    static final class Layer {
        String name = "";
        int top;
        int left;
        int bottom;
        int right;
        final List<Channel> channels = new ArrayList<>();
        int blend = Blend.NORMAL;
        int opacity = 255;
        int fillOpacity = 255;
        boolean clipped;
        boolean hidden;
        boolean blendClipped = true;
        int section;
        int sectionBlend = -1;
        final List<Mask> masks = new ArrayList<>();
        final Set<String> effects = new LinkedHashSet<>();
        final Set<String> legacyEffects = new LinkedHashSet<>();
        boolean modernEffects;
        String adjustment;
        byte[] adjustData;
        byte[] generator;
        byte[] pattern;
        boolean strokeApart;
        byte[] gradient;
        Descriptor vectorStroke;
        Effects.Overlay overlay;
        LayerEffects.Stroke stroke;
        LayerEffects.Shadow shadow;
        float[] solid;
        boolean vectorMask;
        int vectorDensity = 255;
        double vectorFeather;
        byte[] vector;
        boolean fill;
        int knockout;
        final List<Layer> children = new ArrayList<>();
        boolean group;
        int blendRangeFlags;

        boolean empty() {
            return bottom <= top || right <= left;
        }

        Channel channel(int id) {
            for (Channel c : channels) {
                if (c.id == id) {
                    return c;
                }
            }
            return null;
        }
    }

    final Layer root = new Layer();

    final List<long[]> patterns = new ArrayList<>();

    boolean mergedAlpha;

    boolean effects;

    long planes;

    long vectors;

    long blobs;

    int count;

    final List<String> warnings = new ArrayList<>();

    private boolean damaged;

    private Layers() {
        root.group = true;
        root.blend = Blend.PASS_THROUGH;
    }

    boolean drawable() {
        return count > 0 && !root.children.isEmpty();
    }

    long footprint(PsdDocument doc) {
        long merged = (long) doc.channels * Math.max(1 << 12, Math.min(1 << 16, doc.rowBytes() * 2L)) * 2
                + (long) doc.channels * doc.rowBytes() * 3;
        if (doc.realMerged || !drawable()) {
            return merged + (64L << 20);
        }
        long row = (long) doc.width * 4 * (doc.colorChannels() + 1);
        long buffers = (Math.min(count, 32) + 6) * row;
        long open = planes * (40L << 10);
        long caches = planes * doc.width * 4L;
        long strips = vectors * 2 * doc.width * VectorMask.STRIP;
        long fx = effects ? LayerEffects.MAX_PIXELS * 30 + LayerEffects.MAX_PIXELS * 4 * 2 : 0;
        return buffers + open + caches + strips + blobs * 4 + fx + (64L << 20);
    }

    static Layers read(Source src, PsdDocument doc) throws IOException {
        Layers layers = new Layers();
        if (doc.layerEnd <= doc.layerStart) {
            return layers;
        }
        try {
            layers.parse(src, doc);
        } catch (IOException | RuntimeException e) {
            layers.root.children.clear();
            layers.count = 0;
            layers.warnings.add("The PSD document's layers could not be read");
        }
        return layers;
    }

    private void parse(Source src, PsdDocument doc) throws IOException {
        Source.Cursor c = src.cursor(doc.layerStart, doc.layerEnd, 1 << 16);
        long infoLen = c.length(doc.big);
        long infoStart = c.position();
        long infoEnd = Math.min(doc.layerEnd, infoStart + infoLen);
        if (infoLen > 0) {
            info(src, doc, infoStart, infoEnd);
        }
        c.seek(infoEnd);
        if (c.remaining() >= 4) {
            long at = c.position();
            int sig = c.tag();
            c.seek(at);
            if (sig != SIG_8BIM && sig != SIG_8B64) {
                long maskLen = c.u32();
                c.skip(Math.min(maskLen, c.remaining()));
            }
        }
        int blocks = 0;
        while (c.remaining() >= 12 && blocks++ < 1000) {
            int sig = c.tag();
            if (sig != SIG_8BIM && sig != SIG_8B64) {
                break;
            }
            int key = c.tag();
            long len = big(key) && doc.big ? c.u64() : c.u32();
            long start = c.position();
            if (len > c.remaining()) {
                break;
            }
            if ((key == tag("Lr16") || key == tag("Lr32")) && count == 0) {
                info(src, doc, start, start + len);
            }
            if ((key == tag("Patt") || key == tag("Pat2") || key == tag("Pat3")) && patterns.size() < 16) {
                patterns.add(new long[] {start, len});
            }
            c.seek(start + (len + 3 & ~3L));
        }
    }

    private void info(Source src, PsdDocument doc, long start, long end) throws IOException {
        Source.Cursor c = src.cursor(start, end, 1 << 16);
        int n = c.i16();
        if (n < 0) {
            mergedAlpha = true;
            n = -n;
        }
        if (n > MAX_LAYERS) {
            throw new IOException("Too many layers");
        }
        List<Layer> records = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            records.add(record(c, doc));
        }
        long data = c.position();
        for (Layer l : records) {
            List<Channel> placed = new ArrayList<>();
            for (Channel ch : l.channels) {
                long len = ch.length;
                if (len > end - data) {
                    len = Math.max(0, end - data);
                    damaged = true;
                }
                placed.add(new Channel(ch.id, data, len));
                data += len;
                planes++;
            }
            if (l.vector != null) {
                vectors++;
                blobs += l.vector.length;
            }
            blobs += (l.adjustData == null ? 0 : l.adjustData.length) + (l.generator == null ? 0 : l.generator.length) + (l.pattern == null ? 0 : l.pattern.length) + (l.gradient == null ? 0 : l.gradient.length);
            l.channels.clear();
            l.channels.addAll(placed);
        }
        if (data > end + 64) {
            damaged = true;
        }
        count = n;
        tree(records);
        if (damaged) {
            warnings.add("Part of the PSD document's layer data is damaged or missing");
        }
    }

    private void tree(List<Layer> records) {
        Deque<Layer> stack = new ArrayDeque<>();
        Layer current = root;
        for (Layer l : records) {
            if (l.section == 3) {
                if (stack.size() >= MAX_DEPTH) {
                    damaged = true;
                    continue;
                }
                stack.push(current);
                Layer g = new Layer();
                g.group = true;
                current = g;
            } else if (l.section == 1 || l.section == 2) {
                if (stack.isEmpty()) {
                    l.group = true;
                    current.children.add(l);
                    continue;
                }
                Layer g = current;
                g.name = l.name;
                g.blend = l.sectionBlend >= 0 ? l.sectionBlend : l.blend;
                g.opacity = l.opacity;
                g.fillOpacity = l.fillOpacity;
                g.clipped = l.clipped;
                g.hidden = l.hidden;
                g.masks.addAll(l.masks);
                g.channels.addAll(l.channels);
                g.top = l.top;
                g.left = l.left;
                g.bottom = l.bottom;
                g.right = l.right;
                g.effects.addAll(l.effects);
                g.vectorMask = l.vectorMask;
                g.vector = l.vector;
                g.vectorDensity = l.vectorDensity;
                g.vectorFeather = l.vectorFeather;
                g.overlay = l.overlay;
                g.knockout = l.knockout;
                g.blendClipped = l.blendClipped;
                current = stack.pop();
                current.children.add(g);
            } else {
                current.children.add(l);
            }
        }
        while (!stack.isEmpty()) {
            Layer g = current;
            current = stack.pop();
            current.children.add(g);
        }
    }

    private Layer record(Source.Cursor c, PsdDocument doc) throws IOException {
        Layer l = new Layer();
        l.top = c.i32();
        l.left = c.i32();
        l.bottom = c.i32();
        l.right = c.i32();
        int channels = c.u16();
        if (channels > 64) {
            throw new IOException("A layer has too many channels");
        }
        for (int i = 0; i < channels; i++) {
            int id = c.i16();
            long len = c.length(doc.big);
            l.channels.add(new Channel(id, 0, len));
        }
        if (c.tag() != SIG_8BIM) {
            throw new IOException("A layer record has no blend mode signature");
        }
        l.blend = c.tag();
        l.opacity = c.u8();
        l.clipped = c.u8() != 0;
        int flags = c.u8();
        l.hidden = (flags & 2) != 0;
        c.u8();
        long extra = c.u32();
        long extraStart = c.position();
        long extraEnd = extraStart + extra;
        long maskLen = c.u32();
        long maskStart = c.position();
        if (maskLen >= 18) {
            Mask m = new Mask();
            m.top = c.i32();
            m.left = c.i32();
            m.bottom = c.i32();
            m.right = c.i32();
            m.fill = c.u8();
            int mf = c.u8();
            m.disabled = (mf & 2) != 0;
            m.invert = (mf & 4) != 0;
            m.fromVector = (mf & 8) != 0;
            l.masks.add(m);
            if (maskLen >= 36 && l.channel(-3) != null) {
                Mask real = new Mask();
                int rf = c.u8();
                real.fill = c.u8();
                real.top = c.i32();
                real.left = c.i32();
                real.bottom = c.i32();
                real.right = c.i32();
                real.disabled = (rf & 2) != 0;
                real.invert = (rf & 4) != 0;
                real.channel = -3;
                l.masks.add(real);
            }
            if ((mf & 16) != 0 && c.position() < maskStart + maskLen) {
                int params = c.u8();
                if ((params & 1) != 0) {
                    m.density = c.u8();
                }
                if ((params & 2) != 0) {
                    m.feather = Double.longBitsToDouble(c.u32() << 32 | c.u32());
                }
                if ((params & 4) != 0) {
                    l.vectorDensity = c.u8();
                }
                if ((params & 8) != 0) {
                    l.vectorFeather = Double.longBitsToDouble(c.u32() << 32 | c.u32());
                }
            }
        }
        c.seek(maskStart + maskLen);
        long rangesLen = c.u32();
        if (rangesLen >= 8) {
            int grayBlack = c.u16();
            int grayWhite = c.u16();
            int destBlack = c.u16();
            int destWhite = c.u16();
            boolean defaults = grayBlack == 0 && grayWhite == 0xFFFF && destBlack == 0 && destWhite == 0xFFFF;
            l.blendRangeFlags = defaults ? 0 : 1;
            c.seek(c.position() - 8);
        }
        c.skip(Math.min(rangesLen, c.remaining()));
        int nameLen = c.u8();
        byte[] name = new byte[nameLen];
        c.fully(name);
        l.name = new String(name, StandardCharsets.ISO_8859_1);
        int padded = (nameLen + 1 + 3) & ~3;
        c.skip(Math.min(padded - nameLen - 1, Math.max(0, extraEnd - c.position())));
        int blocks = 0;
        while (extraEnd - c.position() >= 12 && blocks++ < 500) {
            int sig = c.tag();
            if (sig != SIG_8BIM && sig != SIG_8B64) {
                break;
            }
            int key = c.tag();
            long len = big(key) && doc.big ? c.u64() : c.u32();
            long start = c.position();
            if (len > extraEnd - start) {
                break;
            }
            block(c, l, key, len);
            c.seek(start + len);
        }
        c.seek(extraEnd);
        if (!l.modernEffects) {
            l.effects.addAll(l.legacyEffects);
        }
        if (l.channels.size() > 0 && l.channel(-2) == null) {
            l.masks.removeIf(m -> m.channel == -2);
        }
        if (l.channel(-3) == null) {
            l.masks.removeIf(m -> m.channel == -3);
        }
        return l;
    }

    private void block(Source.Cursor c, Layer l, int key, long len) throws IOException {
        if (key == tag("luni") && len >= 4) {
            long n = c.u32();
            if (n * 2 <= len - 4 && n < 1024) {
                StringBuilder s = new StringBuilder();
                for (long i = 0; i < n; i++) {
                    char ch = (char) c.u16();
                    if (ch != 0) {
                        s.append(ch);
                    }
                }
                l.name = s.toString();
            }
        } else if ((key == tag("lsct") || key == tag("lsdk")) && len >= 4) {
            l.section = (int) c.u32();
            if (len >= 12) {
                c.tag();
                l.sectionBlend = c.tag();
            }
        } else if (key == tag("iOpa") && len >= 1) {
            l.fillOpacity = c.u8();
        } else if (key == tag("clbl") && len >= 1) {
            l.blendClipped = c.u8() != 0;
        } else if (key == tag("knko") && len >= 1) {
            l.knockout = c.u8();
        } else if (key == tag("vstk") && len > 4 && len <= Adjustments.MAX_BYTES && keep(len)) {
            byte[] d = new byte[(int) len];
            c.fully(d);
            l.vectorStroke = Descriptor.read(d, 4);
        } else if ((key == tag("vmsk") || key == tag("vsms")) && len >= 8 && len <= VectorMask.MAX_BYTES && keep(len)) {
            l.vectorMask = true;
            l.vector = new byte[(int) len];
            c.fully(l.vector);
        } else if (key == tag("lfx2") || key == tag("lmfx")) {
            l.modernEffects = true;
            Effects.Found found = Effects.scan(c, len, l.effects);
            if (found != null) {
                effects |= found.stroke() != null || found.shadow() != null;
                l.overlay = found.overlay();
                l.stroke = found.stroke();
                l.shadow = found.shadow();
            }
        } else if (key == tag("lrFX")) {
            Effects.legacy(c, len, l.legacyEffects);
        } else if (key == tag("vscg") && len > 8) {
            int type = c.tag();
            long rest = len - 4;
            if (type == tag("SoCo") && l.solid == null) {
                l.fill = true;
                l.solid = Effects.solidColor(c, rest);
            } else if (type == tag("GdFl") && l.gradient == null && rest <= Adjustments.MAX_BYTES && keep(rest)) {
                l.fill = true;
                l.gradient = new byte[(int) rest];
                c.fully(l.gradient);
            } else if (type == tag("PtFl") && l.pattern == null && rest <= Adjustments.MAX_BYTES && keep(rest)) {
                l.fill = true;
                l.pattern = new byte[(int) rest];
                c.fully(l.pattern);
            }
        } else if (key == tag("SoCo")) {
            l.fill = true;
            l.solid = Effects.solidColor(c, len);
        } else if (key == tag("GdFl") && len <= Adjustments.MAX_BYTES && keep(len)) {
            l.fill = true;
            l.gradient = new byte[(int) len];
            c.fully(l.gradient);
        } else if (key == tag("PtFl") && len <= Adjustments.MAX_BYTES && keep(len)) {
            l.fill = true;
            l.pattern = new byte[(int) len];
            c.fully(l.pattern);
        } else if (key == tag("GdFl") || key == tag("PtFl")) {
            l.fill = true;
        } else if (key == tag("vowv")) {
            l.strokeApart = true;
        } else if (key == tag("CgEd") && len <= Adjustments.MAX_BYTES && keep(len)) {
            l.generator = new byte[(int) len];
            c.fully(l.generator);
        } else if (ADJUSTMENTS.contains(key)) {
            l.adjustment = new String(new byte[] {(byte) (key >> 24), (byte) (key >> 16), (byte) (key >> 8),
                (byte) key}, StandardCharsets.ISO_8859_1);
            if (len <= Adjustments.MAX_BYTES && keep(len)) {
                l.adjustData = new byte[(int) len];
                c.fully(l.adjustData);
            }
        }
    }

    private boolean keep(long len) {
        if (kept + len > MAX_KEPT_BYTES) {
            if (!overKept) {
                overKept = true;
                warnings.add("The PSD document's layers hold more shape and adjustment data than can be read;"
                        + " some were left out");
            }
            return false;
        }
        kept += len;
        return true;
    }

    private static final Set<Integer> ADJUSTMENTS = Set.of(tag("levl"), tag("curv"), tag("brit"), tag("blnc"),
            tag("hue "), tag("hue2"), tag("selc"), tag("mixr"), tag("grdm"), tag("phfl"), tag("expA"), tag("vibA"),
            tag("nvrt"), tag("post"), tag("thrs"), tag("blwh"), tag("clrL"));

    private static boolean big(int key) {
        return key == tag("LMsk") || key == tag("Lr16") || key == tag("Lr32") || key == tag("Layr")
                || key == tag("Mt16") || key == tag("Mt32") || key == tag("Mtrn") || key == tag("Alph")
                || key == tag("FMsk") || key == tag("lnk2") || key == tag("lnk3") || key == tag("lnkE")
                || key == tag("FEid") || key == tag("FXid") || key == tag("PxSD") || key == tag("pths")
                || key == tag("extd") || key == tag("extn") || key == tag("cinf") || key == tag("artd");
    }

    static int tag(String s) {
        return s.charAt(0) << 24 | s.charAt(1) << 16 | s.charAt(2) << 8 | s.charAt(3);
    }
}
