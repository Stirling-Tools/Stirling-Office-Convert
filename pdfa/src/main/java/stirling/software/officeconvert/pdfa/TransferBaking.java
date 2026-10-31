package stirling.software.officeconvert.pdfa;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.common.function.PDFunction;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDInlineImage;

import stirling.software.officeconvert.extract.PdfFiles;

final class TransferBaking {

    private static final COSName TR2 = COSName.getPDFName("TR2");

    private static final int MAX_DEPTH = 12;

    private static final int SAMPLES = 256;

    private static final Transfer IDENTITY = new Transfer(null);

    private static final class Transfer {

        final int[][] lut;

        Transfer(int[][] lut) {
            this.lut = lut;
        }

        boolean identity() {
            return lut == null;
        }

        float apply(int channel, float v) {
            int[] t = lut[Math.min(channel, lut.length - 1)];
            float x = Math.max(0, Math.min(1, v)) * (SAMPLES - 1);
            int i = (int) Math.floor(x);
            if (i >= SAMPLES - 1) {
                return t[SAMPLES - 1] / 65535f;
            }
            float f = x - i;
            return (t[i] * (1 - f) + t[i + 1] * f) / 65535f;
        }

        int apply8(int channel, int v) {
            return Math.round(apply(channel, v / 255f) * 255);
        }
    }

    private static final class Colour {

        List<Object> original = List.of(COSInteger.ZERO);

        String originalOp;

        PDColorSpace space = PDDeviceGray.INSTANCE;

        float[] values = {0};

        COSName pattern;

        COSName spaceName;

        boolean known = true;

        Colour(boolean stroke) {
            originalOp = stroke ? "G" : "g";
        }

        Colour copy(boolean stroke) {
            Colour c = new Colour(stroke);
            c.original = original;
            c.originalOp = originalOp;
            c.space = space;
            c.values = values;
            c.pattern = pattern;
            c.spaceName = spaceName;
            c.known = known;
            return c;
        }
    }

    private static final class State {

        Transfer transfer;

        Colour stroke;

        Colour fill;

        State(Transfer transfer, Colour stroke, Colour fill) {
            this.transfer = transfer;
            this.stroke = stroke;
            this.fill = fill;
        }

        State copy() {
            return new State(transfer, stroke.copy(true), fill.copy(false));
        }
    }

    private record Key(COSBase object, Transfer transfer) {}

    private final PDDocument doc;

    private final Map<COSBase, Transfer> transfers = new IdentityHashMap<>();

    private final Map<COSDictionary, Transfer> states = new IdentityHashMap<>();

    private final Map<Key, COSBase> baked = new HashMap<>();

    private final Map<COSStream, Boolean> containsTransfer = new IdentityHashMap<>();

    private boolean lost;

    private TransferBaking(PDDocument doc) {
        this.doc = doc;
    }

    static void run(PDDocument doc, ContentGraph graph, Report report) throws IOException {
        TransferBaking b = new TransferBaking(doc);
        boolean any = false;
        for (COSDictionary res : graph.resources()) {
            COSDictionary gs = ContentGraph.dict(res.getDictionaryObject(COSName.EXT_G_STATE));
            if (gs == null) {
                continue;
            }
            for (COSName k : gs.keySet()) {
                COSDictionary state = ContentGraph.dict(gs.getDictionaryObject(k));
                if (state != null) {
                    Transfer t = b.transfer(state);
                    any |= t != null && !t.identity();
                }
            }
        }
        if (!any) {
            return;
        }
        for (ContentGraph.Node n : graph.nodes()) {
            PdfFiles.stopIfInterrupted();
            if (n.kind() != ContentGraph.Kind.PAGE && n.kind() != ContentGraph.Kind.APPEARANCE) {
                continue;
            }
            List<Object> out = b.content(n.streams(), n.resources(), IDENTITY, 0, true);
            if (out != null) {
                ContentTokens.replace(n, out);
            }
        }
        report.warn("Applied transfer functions, which PDF/A does not allow, to the colours they change");
        if (b.lost) {
            report.warn("Some transfer functions could not be applied to the content they change");
        }
    }

    private Transfer transfer(COSDictionary gs) {
        if (states.containsKey(gs)) {
            return states.get(gs);
        }
        COSBase tr = gs.getDictionaryObject(TR2);
        if (tr == null || COSName.DEFAULT.equals(tr)) {
            tr = gs.getDictionaryObject(COSName.TR);
        }
        Transfer t = tr == null ? null : transfers.computeIfAbsent(tr, TransferBaking::parse);
        states.put(gs, t);
        return t;
    }

    private static Transfer parse(COSBase tr) {
        List<COSBase> functions = new ArrayList<>();
        if (tr instanceof COSArray a) {
            if (a.size() != 4) {
                return IDENTITY;
            }
            for (int i = 0; i < 4; i++) {
                if (a.getObject(i) instanceof COSName) {
                    return IDENTITY;
                }
            }
            for (int i = 0; i < 3; i++) {
                functions.add(a.getObject(i));
            }
        } else {
            functions.add(tr);
        }
        int[][] lut = new int[functions.size()][SAMPLES];
        boolean identity = true;
        for (int c = 0; c < functions.size(); c++) {
            COSBase f = functions.get(c);
            PDFunction fn = null;
            if (!(f instanceof COSName)) {
                try {
                    fn = PDFunction.create(f);
                } catch (IOException | RuntimeException e) {
                    fn = null;
                }
            }
            for (int i = 0; i < SAMPLES; i++) {
                float x = i / (float) (SAMPLES - 1);
                float y = x;
                if (fn != null) {
                    try {
                        float[] r = fn.eval(new float[] {x});
                        if (r.length > 0 && Float.isFinite(r[0])) {
                            y = Math.max(0, Math.min(1, r[0]));
                        }
                    } catch (IOException | RuntimeException e) {
                        y = x;
                    }
                }
                lut[c][i] = Math.round(y * 65535);
                identity &= Math.abs(lut[c][i] - Math.round(x * 65535)) <= 64;
            }
        }
        return identity ? IDENTITY : new Transfer(lut);
    }

    private boolean containsTransfer(COSDictionary resources, int depth) {
        if (resources == null || depth > MAX_DEPTH) {
            return false;
        }
        COSDictionary gs = ContentGraph.dict(resources.getDictionaryObject(COSName.EXT_G_STATE));
        if (gs != null) {
            for (COSName k : gs.keySet()) {
                COSDictionary state = ContentGraph.dict(gs.getDictionaryObject(k));
                Transfer t = state == null ? null : transfer(state);
                if (t != null && !t.identity()) {
                    return true;
                }
            }
        }
        for (COSName category : List.of(COSName.XOBJECT, COSName.PATTERN)) {
            COSDictionary d = ContentGraph.dict(resources.getDictionaryObject(category));
            if (d == null) {
                continue;
            }
            for (COSName k : d.keySet()) {
                if (d.getDictionaryObject(k) instanceof COSStream s && !COSName.IMAGE.equals(s.getCOSName(COSName.SUBTYPE))
                        && containsTransfer(s, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsTransfer(COSStream s, int depth) {
        Boolean known = containsTransfer.get(s);
        if (known != null) {
            return known;
        }
        containsTransfer.put(s, false);
        boolean r = containsTransfer(ContentGraph.dict(s.getDictionaryObject(COSName.RESOURCES)), depth);
        containsTransfer.put(s, r);
        return r;
    }

    private List<Object> content(List<COSStream> streams, COSDictionary resources, Transfer entry, int depth,
            boolean defaultColour) throws IOException {
        List<Object> tokens;
        try {
            tokens = ContentTokens.parse(streams);
        } catch (IOException e) {
            Decoded.rethrowFatal(e);
            lost |= !entry.identity();
            return null;
        }
        PDResources pdRes = resources == null ? null : new PDResources(resources);
        List<Object> out = new ArrayList<>(tokens.size() + 16);
        Deque<State> stack = new ArrayDeque<>();
        State st = initial(entry, defaultColour);
        boolean changed = false;
        int start = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (!(tokens.get(i) instanceof Operator op)) {
                continue;
            }
            List<Object> operands = tokens.subList(start, i);
            start = i + 1;
            String name = op.getName();
            boolean stroke = !name.isEmpty() && Character.isUpperCase(name.charAt(0));
            Transfer t = st.transfer;
            switch (name) {
                case "q" -> stack.push(st.copy());
                case "Q" -> st = stack.isEmpty() ? initial(entry, defaultColour) : stack.pop();
                case "gs" -> {
                    COSDictionary gs = operands.size() == 1 && operands.get(0) instanceof COSName gn
                            ? TransparencyScan.lookup(resources, COSName.EXT_G_STATE, gn) : null;
                    Transfer next = gs == null ? null : transfer(gs);
                    if (next != null && next != st.transfer) {
                        st.transfer = next;
                        out.addAll(operands);
                        out.add(op);
                        if (st.stroke.known) {
                            emit(out, st.stroke, true, next, resources, pdRes, depth);
                        }
                        if (st.fill.known) {
                            emit(out, st.fill, false, next, resources, pdRes, depth);
                        }
                        changed = true;
                        continue;
                    }
                }
                case "G", "g", "RG", "rg", "K", "k" -> {
                    Colour c = colour(st, stroke);
                    float[] v = numbers(operands);
                    if (v != null) {
                        c.space = switch (name.toLowerCase()) {
                            case "g" -> PDDeviceGray.INSTANCE;
                            case "rg" -> PDDeviceRGB.INSTANCE;
                            default -> org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK.INSTANCE;
                        };
                        c.values = v;
                        c.original = new ArrayList<>(operands);
                        c.originalOp = name;
                        c.pattern = null;
                        c.spaceName = null;
                        c.known = true;
                    }
                    if (!t.identity() && v != null) {
                        rgb(out, c, stroke, t);
                        changed = true;
                        continue;
                    }
                }
                case "CS", "cs" -> {
                    Colour c = colour(st, stroke);
                    if (operands.size() == 1 && operands.get(0) instanceof COSName csName) {
                        PDColorSpace cs = space(csName, pdRes);
                        if (cs != null) {
                            c.space = cs;
                            c.spaceName = csName;
                            c.values = cs instanceof PDPattern ? new float[0] : cs.getInitialColor().getComponents();
                            c.pattern = null;
                            c.original = List.of(csName, Operator.getOperator(name));
                            c.originalOp = null;
                            c.known = true;
                        }
                    }
                    if (!t.identity() && !(c.space instanceof PDPattern)) {
                        out.addAll(operands);
                        out.add(op);
                        rgb(out, c, stroke, t);
                        changed = true;
                        continue;
                    }
                }
                case "SC", "SCN", "sc", "scn" -> {
                    Colour c = colour(st, stroke);
                    COSName pat = !operands.isEmpty() && operands.get(operands.size() - 1) instanceof COSName pn ? pn
                            : null;
                    float[] v = numbers(pat == null ? operands : operands.subList(0, operands.size() - 1));
                    if (v != null) {
                        c.values = v;
                        c.pattern = pat;
                        List<Object> orig = new ArrayList<>();
                        if (c.spaceName != null) {
                            orig.add(c.spaceName);
                            orig.add(Operator.getOperator(stroke ? "CS" : "cs"));
                        }
                        orig.addAll(operands);
                        c.original = orig;
                        c.originalOp = name;
                    }
                    if (!t.identity() && v != null) {
                        if (pat != null) {
                            List<Object> p = pattern(c, stroke, t, resources, pdRes, depth);
                            if (p != null) {
                                out.addAll(p);
                                changed = true;
                                continue;
                            }
                            lost = true;
                        } else {
                            rgb(out, c, stroke, t);
                            changed = true;
                            continue;
                        }
                    }
                }
                case "Do" -> {
                    if (operands.size() == 1 && operands.get(0) instanceof COSName xn) {
                        COSName replacement = xobject(xn, t, resources, depth);
                        if (replacement != null) {
                            out.add(replacement);
                            out.add(op);
                            changed = true;
                            continue;
                        }
                    }
                }
                case "BI" -> {
                    if (!t.identity()) {
                        Operator image = inline(op, t, pdRes);
                        if (image != null) {
                            out.add(image);
                            changed = true;
                            continue;
                        }
                    }
                }
                default -> {
                }
            }
            out.addAll(operands);
            out.add(op);
        }
        return changed ? out : null;
    }

    private static State initial(Transfer entry, boolean defaultColour) {
        State st = new State(entry, new Colour(true), new Colour(false));
        st.stroke.known = defaultColour;
        st.fill.known = defaultColour;
        return st;
    }

    private static Colour colour(State st, boolean stroke) {
        return stroke ? st.stroke : st.fill;
    }

    private void emit(List<Object> out, Colour c, boolean stroke, Transfer t, COSDictionary resources,
            PDResources pdRes, int depth) throws IOException {
        if (t.identity()) {
            out.addAll(c.original);
            if (c.originalOp != null) {
                out.add(Operator.getOperator(c.originalOp));
            }
        } else if (c.space instanceof PDPattern) {
            if (c.pattern != null) {
                List<Object> p = pattern(c, stroke, t, resources, pdRes, depth);
                if (p != null) {
                    out.addAll(p);
                } else {
                    lost = true;
                }
            }
        } else {
            rgb(out, c, stroke, t);
        }
    }

    private void rgb(List<Object> out, Colour c, boolean stroke, Transfer t) {
        float[] rgb = toRgb(c.space, c.values);
        if (rgb == null) {
            lost = true;
            rgb = new float[] {0, 0, 0};
        }
        for (int i = 0; i < 3; i++) {
            out.add(new COSFloat(Math.round(t.apply(i, rgb[i]) * 10000) / 10000f));
        }
        out.add(Operator.getOperator(stroke ? "RG" : "rg"));
    }

    private static float[] toRgb(PDColorSpace cs, float[] v) {
        try {
            if (cs == null || cs instanceof PDPattern || v.length < cs.getNumberOfComponents()) {
                return null;
            }
            float[] r = cs.toRGB(v);
            return r != null && r.length >= 3 ? r : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static PDColorSpace space(COSName name, PDResources res) {
        try {
            return PDColorSpace.create(name, res);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static float[] numbers(List<Object> operands) {
        float[] v = new float[operands.size()];
        for (int i = 0; i < v.length; i++) {
            if (!(operands.get(i) instanceof COSNumber n)) {
                return null;
            }
            v[i] = n.floatValue();
        }
        return v;
    }

    private static COSName unique(COSDictionary category, COSName base) {
        for (int i = 1;; i++) {
            COSName n = COSName.getPDFName(base.getName() + "_tr" + i);
            if (!category.containsKey(n)) {
                return n;
            }
        }
    }

    private static COSName register(COSDictionary resources, COSName category, COSName base, COSBase value) {
        COSDictionary d = ContentGraph.dict(resources.getDictionaryObject(category));
        if (d == null) {
            d = new COSDictionary();
            resources.setItem(category, d);
        }
        for (COSName k : d.keySet()) {
            if (d.getItem(k) == value || d.getDictionaryObject(k) == value) {
                return k;
            }
        }
        COSName n = unique(d, base);
        d.setItem(n, value);
        return n;
    }

    private COSName xobject(COSName name, Transfer t, COSDictionary resources, int depth) throws IOException {
        COSDictionary x = TransparencyScan.lookup(resources, COSName.XOBJECT, name);
        if (!(x instanceof COSStream s) || depth > MAX_DEPTH) {
            return null;
        }
        COSName sub = s.getCOSName(COSName.SUBTYPE);
        if (COSName.IMAGE.equals(sub)) {
            if (t.identity() || s.getBoolean(COSName.IMAGE_MASK, false)) {
                return null;
            }
            COSBase img = baked.get(new Key(s, t));
            if (img == null) {
                img = image(s, t);
                if (img == null) {
                    lost = true;
                    return null;
                }
                baked.put(new Key(s, t), img);
            }
            return register(resources, COSName.XOBJECT, name, img);
        }
        if (!COSName.FORM.equals(sub) || t.identity() && !containsTransfer(s, 0)) {
            return null;
        }
        if (t.identity()) {
            if (baked.putIfAbsent(new Key(s, t), s) == null) {
                List<Object> out = content(List.of(s), ContentGraph.dict(s.getDictionaryObject(COSName.RESOURCES)), t,
                        depth + 1, false);
                if (out != null) {
                    ContentTokens.write(s, out);
                }
            }
            return null;
        }
        COSBase form = baked.get(new Key(s, t));
        if (form == null) {
            COSStream copy = copyStream(s);
            baked.put(new Key(s, t), copy);
            COSDictionary res = ContentGraph.dict(copy.getDictionaryObject(COSName.RESOURCES));
            if (res != null) {
                res = copyDict(res);
                copy.setItem(COSName.RESOURCES, res);
            }
            List<Object> out = content(List.of(s), res, t, depth + 1, false);
            if (out != null) {
                ContentTokens.write(copy, out);
            }
            form = copy;
        }
        return register(resources, COSName.XOBJECT, name, form);
    }

    private static COSDictionary copyDict(COSDictionary d) {
        COSDictionary c = new COSDictionary();
        for (Map.Entry<COSName, COSBase> e : d.entrySet()) {
            COSBase v = e.getValue();
            if (v instanceof org.apache.pdfbox.cos.COSObject o && o.getObject() instanceof COSDictionary inner
                    && !(inner instanceof COSStream)) {
                v = inner;
            }
            if (v instanceof COSDictionary inner && !(inner instanceof COSStream)) {
                COSDictionary sub = new COSDictionary();
                for (Map.Entry<COSName, COSBase> f : inner.entrySet()) {
                    sub.setItem(f.getKey(), f.getValue());
                }
                v = sub;
            }
            c.setItem(e.getKey(), v);
        }
        return c;
    }

    private COSStream copyStream(COSStream s) throws IOException {
        COSStream copy = doc.getDocument().createCOSStream();
        for (Map.Entry<COSName, COSBase> e : s.entrySet()) {
            if (!COSName.LENGTH.equals(e.getKey())) {
                copy.setItem(e.getKey(), e.getValue());
            }
        }
        try (InputStream in = s.createRawInputStream(); OutputStream o = copy.createRawOutputStream()) {
            in.transferTo(o);
        }
        return copy;
    }

    private List<Object> pattern(Colour c, boolean stroke, Transfer t, COSDictionary resources, PDResources pdRes,
            int depth) throws IOException {
        COSDictionary p = TransparencyScan.lookup(resources, COSName.PATTERN, c.pattern);
        if (p == null || depth > MAX_DEPTH) {
            return null;
        }
        COSBase replacement = baked.get(new Key(p, t));
        if (replacement == null) {
            if (p.getInt(COSName.PATTERN_TYPE, 1) == 2) {
                replacement = p;
            } else if (p instanceof COSStream ps && p.getInt(COSName.PAINT_TYPE, 1) == 1) {
                COSStream copy = copyStream(ps);
                baked.put(new Key(p, t), copy);
                COSDictionary res = ContentGraph.dict(copy.getDictionaryObject(COSName.RESOURCES));
                if (res != null) {
                    res = copyDict(res);
                    copy.setItem(COSName.RESOURCES, res);
                }
                List<Object> out = content(List.of(ps), res, t, depth + 1, true);
                if (out != null) {
                    ContentTokens.write(copy, out);
                }
                replacement = copy;
            } else {
                replacement = p;
            }
            baked.put(new Key(p, t), replacement);
        }
        COSName name = replacement == p ? c.pattern : register(resources, COSName.PATTERN, c.pattern, replacement);
        List<Object> out = new ArrayList<>();
        float[] comps = c.values;
        if (comps.length > 0 && c.space instanceof PDPattern pp) {
            float[] rgb = toRgb(pp.getUnderlyingColorSpace(), comps);
            if (rgb == null) {
                return null;
            }
            COSArray space = new COSArray();
            space.add(COSName.PATTERN);
            space.add(COSName.DEVICERGB);
            out.add(register(resources, COSName.COLORSPACE, COSName.getPDFName("PatternRGB"), space));
            out.add(Operator.getOperator(stroke ? "CS" : "cs"));
            for (int i = 0; i < 3; i++) {
                out.add(new COSFloat(Math.round(t.apply(i, rgb[i]) * 10000) / 10000f));
            }
        } else {
            out.add(COSName.PATTERN);
            out.add(Operator.getOperator(stroke ? "CS" : "cs"));
        }
        out.add(name);
        out.add(Operator.getOperator(stroke ? "SCN" : "scn"));
        return out;
    }

    private COSStream image(COSStream s, Transfer t) throws IOException {
        BufferedImage opaque;
        BufferedImage masked = null;
        try {
            PDImageXObject img = new PDImageXObject(new PDStream(s), null);
            opaque = img.getOpaqueImage();
            if (s.getDictionaryObject(COSName.MASK) instanceof COSArray) {
                masked = img.getImage();
            }
        } catch (IOException | RuntimeException e) {
            Decoded.rethrowFatal(e instanceof IOException io ? io : new IOException(e));
            return null;
        }
        if (opaque == null) {
            return null;
        }
        BufferedImage rgb = transferred(opaque, t);
        boolean jpeg = COSName.DCT_DECODE.equals(lastFilter(s)) && Transparency.photographic(rgb);
        PDImageXObject out = jpeg ? JPEGFactory.createFromImage(doc, rgb, 0.92f) : LosslessFactory.createFromImage(doc, rgb);
        COSStream o = out.getCOSObject();
        for (COSName k : List.of(COSName.SMASK, COSName.INTENT, COSName.STRUCT_PARENT, COSName.METADATA)) {
            if (s.getItem(k) != null) {
                o.setItem(k, s.getItem(k));
            }
        }
        if (s.getDictionaryObject(COSName.MASK) instanceof COSStream mask) {
            o.setItem(COSName.MASK, mask);
        } else if (masked != null && masked.getColorModel().hasAlpha() && o.getItem(COSName.SMASK) == null) {
            BufferedImage alpha = new BufferedImage(masked.getWidth(), masked.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
            alpha.setData(masked.getAlphaRaster().createTranslatedChild(0, 0));
            o.setItem(COSName.SMASK, GreyImages.lossless(doc, alpha).getCOSObject());
        }
        return o;
    }

    private static COSName lastFilter(COSStream s) {
        COSBase f = s.getDictionaryObject(COSName.FILTER);
        if (f instanceof COSArray a && a.size() > 0 && a.getObject(a.size() - 1) instanceof COSName n) {
            return n;
        }
        return f instanceof COSName n ? n : null;
    }

    private static BufferedImage transferred(BufferedImage src, Transfer t) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[][] lut = new int[3][256];
        for (int c = 0; c < 3; c++) {
            for (int v = 0; v < 256; v++) {
                lut[c][v] = t.apply8(c, v);
            }
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            src.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int p = row[x];
                row[x] = lut[0][p >> 16 & 0xFF] << 16 | lut[1][p >> 8 & 0xFF] << 8 | lut[2][p & 0xFF];
            }
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        return out;
    }

    private Operator inline(Operator op, Transfer t, PDResources res) throws IOException {
        COSDictionary params = op.getImageParameters();
        if (params == null || params.getBoolean(COSName.IM, false) || params.getBoolean(COSName.IMAGE_MASK, false)) {
            return null;
        }
        BufferedImage img;
        try {
            img = new PDInlineImage(params, op.getImageData(), res).getImage();
        } catch (IOException | RuntimeException e) {
            Decoded.rethrowFatal(e instanceof IOException io ? io : new IOException(e));
            lost = true;
            return null;
        }
        BufferedImage rgb = transferred(img, t);
        int w = rgb.getWidth();
        int h = rgb.getHeight();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(data, new Deflater(Deflater.BEST_COMPRESSION))) {
            int[] row = new int[w];
            byte[] bytes = new byte[w * 3];
            for (int y = 0; y < h; y++) {
                rgb.getRGB(0, y, w, 1, row, 0, w);
                for (int x = 0; x < w; x++) {
                    bytes[3 * x] = (byte) (row[x] >> 16);
                    bytes[3 * x + 1] = (byte) (row[x] >> 8);
                    bytes[3 * x + 2] = (byte) row[x];
                }
                z.write(bytes);
            }
        }
        COSDictionary p = new COSDictionary();
        p.setInt(COSName.W, w);
        p.setInt(COSName.H, h);
        p.setInt(COSName.BPC, 8);
        p.setItem(COSName.CS, COSName.getPDFName("RGB"));
        p.setItem(COSName.F, COSName.getPDFName("Fl"));
        Operator bi = Operator.getOperator("BI");
        bi.setImageParameters(p);
        bi.setImageData(data.toByteArray());
        return bi;
    }
}
