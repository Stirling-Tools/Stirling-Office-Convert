package stirling.software.officeconvert.extract;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

import org.apache.fontbox.cff.CFFType1Font;
import org.apache.fontbox.cmap.CMap;
import org.apache.fontbox.cmap.CMapParser;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.font.PDCIDFont;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType0;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDCIDSystemInfo;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType3CharProc;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;
import org.apache.pdfbox.pdmodel.font.encoding.Encoding;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

final class UnmappedGlyphs {

    static final String STAND_IN = "\uFFFC";

    private static final float UNMAPPED_SHARE = 0.5f;

    private static final float READ_SHARE = 0.5f;

    private static final float TRUSTED_SHARE = 0.6f;

    private static final int MAX_POOL = 512;

    private static final int SAMPLE = 48;

    private static final float ASPECT_WEIGHT = 0.02f;

    private static final float PLACE_WEIGHT = 0.1f;

    private static final float SHAPE_LIMIT = 0.1f;

    private static final float PLACE_LIMIT = 0.012f;

    private static final float OUTLINE_PLACE_LIMIT = 0.1f;

    private static final float OUTLINE_PLACE_WEIGHT = 0.3f;

    private static final float SETTLED = 0.1f;

    private static final float ANCHOR_MARGIN = 0.01f;

    private static final float CLUSTER_GAP = 0.06f;

    private static final float FIRST_PASS = 0.03f;

    private static final float ACCEPT = 0.045f;

    private static final float MARGIN = 0.003f;

    private static final int MIN_SAMPLES = 3;

    private static final int MAX_INKS = 16_384;

    private static final long MAX_INK_ELEMENTS = 8_000_000;

    private static final int MAX_AREA_ELEMENTS = 16_384;

    private static final double MAX_ICON_POINTS = 300;

    private static final String[][] TIES = {{"-", "\u2013"}, {"\u2013", "\u2014"}, {"l", "I"},
        {"(", "["}, {")", "]"}};

    private static final List<String> CJK_ORDERINGS = List.of("Japan1", "GB1", "CNS1", "Korea1");

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private static final String STEADY_HEIGHTS = "abdeghkmnpqrtyABDEFGHKLMNQRTY023456789";

    private final GlyphList glyphs;
    private final Map<COSDictionary, Shapes> fonts = new WeakHashMap<>();
    private final List<Size> known = new ArrayList<>();
    private final OperatorBudget operators = new OperatorBudget("The document", "reading the shapes of glyphs without a text map",
            "the remaining glyphs in those fonts are left unread");
    private int ids;
    private boolean rawCodesLie;
    private int inks;
    private long inkElements;
    private int areaElements;

    UnmappedGlyphs(GlyphList glyphs) {
        this.glyphs = glyphs;
    }

    OperatorBudget operators() {
        return operators;
    }

    boolean spendArea(int elements) {
        areaElements += elements;
        return areaElements <= MAX_AREA_ELEMENTS;
    }

    private boolean spendInk(GeneralPath path) {
        for (var it = path.getPathIterator(null); !it.isDone() && inkElements <= MAX_INK_ELEMENTS; it.next()) {
            inkElements++;
        }
        return inkElements <= MAX_INK_ELEMENTS;
    }

    String text(PDFont font, int code, Matrix trm) {
        Shapes shapes = shapes(font, trm);
        if (shapes == null) {
            return null;
        }
        Read read = shapes.read(code);
        return read == null ? null : read.text;
    }

    IconShape icon(PDFont font, int code, Matrix trm, int rgb) {
        Shapes shapes = fonts.get(font.getCOSObject());
        Read read = shapes == null ? null : shapes.reads.get(code);
        if (read == null || read.outline == null) {
            return null;
        }
        float k = scale(trm);
        if (!(k > 0) || !Float.isFinite(k)) {
            return null;
        }
        GeneralPath outline = new GeneralPath(read.outline);
        AffineTransform toPoints = AffineTransform.getScaleInstance(k, -k);
        toPoints.translate(0, -read.base);
        outline.transform(toPoints);
        Rectangle2D b = outline.getBounds2D();
        if (!(Math.max(Math.max(Math.abs(b.getMinX()), Math.abs(b.getMaxX())),
                Math.max(Math.abs(b.getMinY()), Math.abs(b.getMaxY()))) <= MAX_ICON_POINTS)) {
            return null;
        }
        return new IconShape(outline, rgb,
                "unmapped|" + shapes.id + "|" + code + "|" + Integer.toHexString(rgb) + "|" + Math.round(k * 4000));
    }

    float rise(PDFont font, int code, Matrix trm) {
        Shapes shapes = fonts.get(font.getCOSObject());
        Read read = shapes == null ? null : shapes.reads.get(code);
        if (read == null || read.base == 0) {
            return 0;
        }
        float k = scale(trm);
        return k > 0 && Float.isFinite(k) ? read.base * k : 0;
    }

    float size(PDFont font, int code, Matrix trm) {
        Shapes shapes = fonts.get(font.getCOSObject());
        Read read = shapes == null ? null : shapes.reads.get(code);
        return read == null ? Float.NaN : read.em * scale(trm);
    }

    float drawnWidth(PDFont font, int code, Matrix trm) {
        Shapes shapes = fonts.get(font.getCOSObject());
        Read read = shapes == null || !shapes.carriers ? null : shapes.reads.get(code);
        if (read == null || !(read.right > 0)) {
            return Float.NaN;
        }
        return read.right * scale(trm);
    }

    int style(PDFont font) {
        Shapes shapes = fonts.get(font.getCOSObject());
        return shapes == null ? 0 : shapes.style;
    }

    private Shapes shapes(PDFont font, Matrix trm) {
        COSDictionary key = font.getCOSObject();
        if (fonts.containsKey(key)) {
            return fonts.get(key);
        }
        Shapes shapes = null;
        try {
            if (handled(font)) {
                AffineTransform units = units(trm);
                if (units != null) {
                    shapes = new Shapes(this, font, ids++, units, named(font));
                }
            }
        } catch (IOException | RuntimeException e) {
            shapes = null;
        }
        fonts.put(key, shapes);
        return shapes;
    }

    private boolean handled(PDFont font) throws IOException {
        if (font.getCOSObject().containsKey(COSName.TO_UNICODE)) {
            return false;
        }
        if (font instanceof PDType0Font t0) {
            return t0.isEmbedded() && t0.getDescendantFont() != null;
        }
        if (!(font instanceof PDSimpleFont simple) || !(font instanceof PDType3Font) && !font.isEmbedded()) {
            return false;
        }
        int[] codes = codes(simple);
        if (codes.length == 0) {
            return false;
        }
        int unmapped = 0;
        for (int code : codes) {
            if (font.toUnicode(code, glyphs) == null) {
                unmapped++;
            }
        }
        return unmapped >= UNMAPPED_SHARE * codes.length;
    }

    private static int[] codes(PDSimpleFont font) throws IOException {
        List<Integer> out = new ArrayList<>();
        if (font instanceof PDType3Font t3) {
            Encoding encoding = t3.getEncoding();
            if (encoding == null || t3.getCharProcs() == null) {
                return new int[0];
            }
            for (Map.Entry<Integer, String> e : encoding.getCodeToNameMap().entrySet()) {
                if (e.getKey() >= 0 && e.getKey() < 256
                        && t3.getCharProcs().containsKey(COSName.getPDFName(e.getValue()))) {
                    out.add(e.getKey());
                }
            }
        } else if (font instanceof PDVectorFont vector) {
            for (int code = 0; code < 256; code++) {
                if (font.getWidth(code) > 0 && vector.hasGlyph(code)) {
                    out.add(code);
                }
            }
        }
        return out.stream().mapToInt(Integer::intValue).sorted().toArray();
    }

    private static AffineTransform units(Matrix trm) {
        AffineTransform r = rest(trm);
        double angle = Math.atan2(r.getShearY(), r.getScaleX());
        AffineTransform n = AffineTransform.getRotateInstance(-angle);
        n.concatenate(r);
        double det = Math.abs(n.getDeterminant());
        if (!(det > 0) || !Double.isFinite(det)) {
            return null;
        }
        AffineTransform out = AffineTransform.getScaleInstance(1 / Math.sqrt(det), 1 / Math.sqrt(det));
        out.concatenate(n);
        return out;
    }

    private static float scale(Matrix trm) {
        return (float) Math.sqrt(Math.abs(rest(trm).getDeterminant()));
    }

    private static AffineTransform rest(Matrix trm) {
        AffineTransform r = trm.createAffineTransform();
        return new AffineTransform(r.getScaleX(), r.getShearY(), r.getShearX(), r.getScaleY(), 0, 0);
    }

    private IntFunction<String> named(PDFont font) {
        return font instanceof PDSimpleFont ? code -> String.valueOf((char) code) : programText(font);
    }

    private IntFunction<String> programText(PDFont font) {
        IntFunction<String> trueType = UnicodeRecovery.programText(font);
        if (trueType != null) {
            return code -> {
                String text = trueType.apply(code);
                return text == null || UnicodeRecovery.privateUse(text) ? null : text;
            };
        }
        IntFunction<String> ordered = orderedText(font);
        if (ordered != null) {
            return ordered;
        }
        if (font instanceof PDType0Font t0 && t0.getDescendantFont() instanceof PDCIDFontType0 cff
                && cff.getCFFFont() instanceof CFFType1Font type1) {
            return code -> {
                try {
                    String name = type1.getCharset().getNameForGID(cff.codeToGID(code));
                    String text = name == null ? null : glyphs.toUnicode(name);
                    return text == null || UnicodeRecovery.privateUse(text) ? null : text;
                } catch (RuntimeException e) {
                    return null;
                }
            };
        }
        return null;
    }

    private static IntFunction<String> orderedText(PDFont font) {
        if (!(font instanceof PDType0Font t0) || t0.getDescendantFont().getCIDSystemInfo() == null) {
            return null;
        }
        PDCIDSystemInfo info = t0.getDescendantFont().getCIDSystemInfo();
        if (!"Adobe".equals(info.getRegistry()) || !CJK_ORDERINGS.contains(info.getOrdering())) {
            return null;
        }
        CMap ucs2;
        try {
            ucs2 = new CMapParser().parsePredefined("Adobe-" + info.getOrdering() + "-UCS2");
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return code -> {
            int cid = t0.codeToCID(code);
            String text = cid > 0 ? ucs2.toUnicode(cid, 2) : null;
            return text == null || UnicodeRecovery.privateUse(text) ? null : text;
        };
    }

    private record Read(String text, GeneralPath outline, GlyphRef ref, float distance, float right, float base,
            float em) {}

    private record Ink(int code, GeneralPath outline, GlyphPrint print, float advance, List<Match> matches) {}

    private record Match(GlyphRef ref, float shape) {}

    private record Option(int index, GlyphRef ref, float distance) {}

    private record Size(float[] size, float frame) {}

    private static final class Shapes {

        final PDFont font;
        final int id;
        final AffineTransform units;
        final IntFunction<String> named;
        final UnmappedGlyphs owner;
        final List<Size> known;
        final Map<Integer, Read> reads = new HashMap<>();
        boolean pooled;
        List<float[]> sizes = List.of();
        boolean legible;
        boolean trusted;
        float placeLimit = PLACE_LIMIT;
        float placeWeight = PLACE_WEIGHT;
        boolean carriers;
        int style;

        Shapes(UnmappedGlyphs owner, PDFont font, int id, AffineTransform units, IntFunction<String> named) {
            this.owner = owner;
            this.known = owner.known;
            this.font = font;
            this.id = id;
            this.units = units;
            this.named = named;
        }

        Read read(int code) {
            Read read = reads.get(code);
            if (read != null || reads.containsKey(code)) {
                return read;
            }
            if (!pooled) {
                pool();
                read = reads.get(code);
                if (read != null || reads.containsKey(code)) {
                    return read;
                }
            }
            String text = trusted ? named.apply(code) : null;
            read = text != null ? new Read(text, null, null, 0, Float.NaN, 0, Float.NaN) : classify(ink(code));
            reads.put(code, read);
            return read;
        }

        private void pool() {
            pooled = true;
            int[] codes = poolCodes();
            if (named != null && codes.length > SAMPLE && provedBy(sample(codes))) {
                trusted = true;
                return;
            }
            List<Ink> inks = inks(codes);
            measure(inks);
            int drawn = 0;
            int unmoved = 0;
            for (Ink ink : inks) {
                if (ink.print != null) {
                    drawn++;
                    unmoved += ink.advance <= 0 ? 1 : 0;
                }
            }
            carriers = drawn > 0 && unmoved * 2 > drawn;
            List<Read> found = new ArrayList<>();
            for (Ink ink : inks) {
                found.add(best(ink));
            }
            if (!(font instanceof PDType3Font)) {
                assign(inks, found);
            }
            int inked = 0;
            int legibleCount = 0;
            for (int i = 0; i < inks.size(); i++) {
                Ink ink = inks.get(i);
                Read read = found.get(i);
                if (ink.print != null) {
                    inked++;
                    legibleCount += read.ref != null ? 1 : 0;
                }
            }
            legible = inked > 0 && legibleCount >= READ_SHARE * inked;
            trusted = named != null && agrees(inks, found);
            int[] votes = new int[4];
            int voters = 0;
            for (int i = 0; i < inks.size(); i++) {
                Read read = legible ? found.get(i) : unread(found.get(i));
                String text = trusted ? named.apply(inks.get(i).code) : null;
                if (text != null) {
                    read = new Read(text, null, read.ref, 0, Float.NaN, 0, Float.NaN);
                }
                reads.put(inks.get(i).code, read);
                if (read.ref != null && Character.isLetter(read.text.charAt(0))) {
                    voters++;
                    for (int bit = 0; bit < 2; bit++) {
                        votes[bit] += (read.ref.style() >> bit & 1);
                    }
                }
            }
            for (int bit = 0; bit < 2 && !trusted; bit++) {
                if (voters > 0 && votes[bit] * 2 > voters) {
                    style |= 1 << bit;
                }
            }
        }

        private List<Ink> inks(int[] codes) {
            List<Ink> inks = new ArrayList<>();
            for (int code : codes) {
                Ink ink = ink(code);
                if (ink != null) {
                    inks.add(ink);
                }
            }
            return inks;
        }

        private static int[] sample(int[] codes) {
            int[] out = new int[SAMPLE];
            for (int i = 0; i < SAMPLE; i++) {
                out[i] = codes[(int) ((long) i * codes.length / SAMPLE)];
            }
            return out;
        }

        private boolean provedBy(int[] codes) {
            List<Ink> inks = inks(codes);
            measure(inks);
            List<Read> found = new ArrayList<>();
            int checked = 0;
            for (Ink ink : inks) {
                Read read = best(ink);
                found.add(read);
                checked += read.ref != null && named.apply(ink.code) != null ? 1 : 0;
            }
            return checked >= MIN_SAMPLES && agrees(inks, found);
        }

        private void assign(List<Ink> inks, List<Read> found) {
            List<Option> options = new ArrayList<>();
            for (int i = 0; i < inks.size(); i++) {
                Ink ink = inks.get(i);
                if (ink.print == null) {
                    continue;
                }
                Map<String, Option> byText = new HashMap<>();
                for (Match m : ink.matches) {
                    float d = m.shape + misplaced(ink.print, m.ref.print());
                    Option known = byText.get(m.ref.text());
                    if (d <= ACCEPT && (known == null || d < known.distance)) {
                        byText.put(m.ref.text(), new Option(i, m.ref, d));
                    }
                }
                options.addAll(byText.values());
            }
            options.sort((a, b) -> Float.compare(a.distance, b.distance));
            boolean[] placed = new boolean[inks.size()];
            Map<String, GlyphPrint> taken = new HashMap<>();
            for (Option option : options) {
                Ink ink = inks.get(option.index);
                GlyphPrint owner = taken.get(option.ref.text());
                if (placed[option.index] || owner != null && !Arrays.equals(owner.cells(), ink.print.cells())) {
                    continue;
                }
                placed[option.index] = true;
                taken.put(option.ref.text(), ink.print);
                found.set(option.index, new Read(option.ref.text(), ink.outline, option.ref, option.distance,
                        advance(ink.print, option.ref.print()), 0, 1));
            }
            for (int i = 0; i < inks.size(); i++) {
                if (!placed[i] && found.get(i).ref != null) {
                    found.set(i, unreadable(inks.get(i), found.get(i).distance));
                }
            }
        }

        private boolean agrees(List<Ink> inks, List<Read> found) {
            int checked = 0;
            int agreed = 0;
            for (int i = 0; i < inks.size(); i++) {
                Read read = found.get(i);
                String text = read.ref == null ? null : named.apply(inks.get(i).code);
                if (text != null) {
                    checked++;
                    agreed += plain(text).equalsIgnoreCase(plain(read.text)) ? 1 : 0;
                }
            }
            if (checked < MIN_SAMPLES) {
                return !(font instanceof PDSimpleFont) || !owner.rawCodesLie;
            }
            boolean agrees = agreed >= TRUSTED_SHARE * checked;
            if (!agrees && font instanceof PDSimpleFont) {
                owner.rawCodesLie = true;
            }
            return agrees;
        }

        private static String plain(String text) {
            return MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFKD)).replaceAll("");
        }

        private static Read unread(Read read) {
            return read.ref == null ? read
                    : new Read(STAND_IN, read.outline, null, read.distance, read.right, read.base, read.em);
        }

        private Read classify(Ink ink) {
            if (ink == null) {
                return null;
            }
            Read read = best(ink);
            return legible ? read : unread(read);
        }

        private int[] poolCodes() {
            try {
                if (font instanceof PDSimpleFont simple) {
                    return codes(simple);
                }
                if (font instanceof PDType0Font t0) {
                    int glyphs = glyphCount(t0.getDescendantFont());
                    int[] out = new int[Math.min(glyphs, MAX_POOL)];
                    for (int i = 0; i < out.length; i++) {
                        out[i] = i + 1;
                    }
                    return out;
                }
            } catch (IOException | RuntimeException e) {
                return new int[0];
            }
            return new int[0];
        }

        private static int glyphCount(PDCIDFont cid) throws IOException {
            if (cid instanceof PDCIDFontType2 tt && tt.getTrueTypeFont() != null) {
                return tt.getTrueTypeFont().getNumberOfGlyphs() - 1;
            }
            if (cid instanceof PDCIDFontType0 cff && cff.getCFFFont() != null) {
                return cff.getCFFFont().getNumCharStrings() - 1;
            }
            return 0;
        }

        private Ink ink(int code) {
            if (++owner.inks > MAX_INKS || owner.inkElements > MAX_INK_ELEMENTS) {
                return null;
            }
            try {
                GeneralPath path;
                if (font instanceof PDType3Font t3) {
                    PDType3CharProc proc = t3.getCharProc(code);
                    path = proc == null ? null : Type3Ink.of(proc, owner);
                } else if (font instanceof PDVectorFont vector && vector.hasGlyph(code)) {
                    path = vector.getNormalizedPath(code);
                    if (path != null) {
                        path = new GeneralPath(path);
                        path.transform(AffineTransform.getScaleInstance(0.001, 0.001));
                    }
                } else {
                    path = null;
                }
                if (path == null || !owner.spendInk(path)) {
                    return null;
                }
                path.transform(units);
                Vector d = font.getDisplacement(code);
                Point2D advance = units.deltaTransform(new Point2D.Double(d.getX(), d.getY()), null);
                GlyphPrint print = GlyphPrint.of(path, (float) advance.getX());
                return new Ink(code, path, print, (float) advance.getX(), matches(print));
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        private void measure(List<Ink> inks) {
            if (!(font instanceof PDType3Font)) {
                sizes = List.of(new float[] {0, 0, 1});
                placeWeight = OUTLINE_PLACE_WEIGHT;
                placeLimit = OUTLINE_PLACE_LIMIT;
                return;
            }
            List<float[]> anchors = new ArrayList<>();
            for (Ink ink : inks) {
                if (ink.print == null) {
                    continue;
                }
                GlyphRef ref = null;
                float best = FIRST_PASS;
                float other = Float.MAX_VALUE;
                for (Match m : ink.matches) {
                    GlyphRef r = m.ref;
                    float d = m.shape;
                    if (d < best) {
                        if (ref != null && !ref.text().equals(r.text())) {
                            other = Math.min(other, best);
                        }
                        best = d;
                        ref = r;
                    } else if (d < other && ref != null && !ref.text().equals(r.text())) {
                        other = d;
                    }
                }
                if (ref != null && other - best >= ANCHOR_MARGIN && ref.text().length() == 1
                        && STEADY_HEIGHTS.indexOf(ref.text().charAt(0)) >= 0) {
                    anchors.add(placement(ink.print, ref.print()));
                }
            }
            anchors.sort((a, b) -> Float.compare(a[0], b[0]));
            List<float[]> found = new ArrayList<>();
            for (int i = 0; i < anchors.size(); ) {
                int end = i + 1;
                while (end < anchors.size() && anchors.get(end)[0] - anchors.get(end - 1)[0] < CLUSTER_GAP) {
                    end++;
                }
                if (end - i >= MIN_SAMPLES) {
                    found.addAll(byBaseline(anchors.subList(i, end)));
                }
                i = end;
            }
            float frame = font.getFontMatrix().getScaleX();
            for (Size size : known) {
                if (size.frame == frame && !near(found, size.size) && (near(anchors, size.size) || anchors.isEmpty())) {
                    found.add(size.size);
                }
            }
            for (float[] size : found) {
                if (!nearSize(known, size, frame)) {
                    known.add(new Size(size, frame));
                }
            }
            sizes = found;
        }

        private static boolean near(List<float[]> points, float[] size) {
            for (float[] p : points) {
                if (Math.abs(p[0] - size[0]) < CLUSTER_GAP && Math.abs(p[1] - size[1]) < CLUSTER_GAP) {
                    return true;
                }
            }
            return false;
        }

        private static boolean nearSize(List<Size> known, float[] size, float frame) {
            for (Size k : known) {
                if (k.frame == frame && Math.abs(k.size[0] - size[0]) < CLUSTER_GAP
                        && Math.abs(k.size[1] - size[1]) < CLUSTER_GAP) {
                    return true;
                }
            }
            return false;
        }

        private static List<float[]> byBaseline(List<float[]> group) {
            List<float[]> sorted = new ArrayList<>(group);
            sorted.sort((a, b) -> Float.compare(a[1], b[1]));
            List<float[]> out = new ArrayList<>();
            for (int i = 0; i < sorted.size(); ) {
                int end = i + 1;
                while (end < sorted.size() && sorted.get(end)[1] - sorted.get(end - 1)[1] < CLUSTER_GAP) {
                    end++;
                }
                if (end - i >= MIN_SAMPLES) {
                    float[] scales = new float[end - i];
                    float[] baselines = new float[end - i];
                    for (int k = i; k < end; k++) {
                        scales[k - i] = sorted.get(k)[0];
                        baselines[k - i] = sorted.get(k)[1];
                    }
                    out.add(new float[] {median(scales), median(baselines), end - i});
                }
                i = end;
            }
            return out;
        }

        private static float[] placement(GlyphPrint g, GlyphPrint r) {
            float scale = g.height() / Math.max(r.height(), 1e-3f);
            float baseline = g.bottom() - scale * r.bottom();
            return new float[] {(float) Math.log(scale), baseline / scale};
        }

        private static float median(float[] values) {
            Arrays.sort(values);
            return values[values.length / 2];
        }

        private static List<Match> matches(GlyphPrint g) {
            if (g == null) {
                return List.of();
            }
            List<Match> out = new ArrayList<>();
            float aspect = g.aspect();
            for (GlyphRef r : GlyphRef.all()) {
                float skew = ASPECT_WEIGHT * Math.abs(aspect - r.print().aspect());
                if (skew > SHAPE_LIMIT) {
                    continue;
                }
                float d = g.shape(r.print(), SHAPE_LIMIT - skew) + skew;
                if (d <= SHAPE_LIMIT) {
                    out.add(new Match(r, d));
                }
            }
            return out;
        }

        private Read best(Ink ink) {
            if (ink.print == null) {
                return new Read(carriers ? "" : " ", null, null, 0, Float.NaN, 0, Float.NaN);
            }
            GlyphRef best = null;
            float bestD = Float.MAX_VALUE;
            GlyphRef runner = null;
            float other = Float.MAX_VALUE;
            for (Match m : ink.matches) {
                GlyphRef r = m.ref;
                float d = m.shape + misplaced(ink.print, r.print());
                if (d < bestD) {
                    if (best != null && !best.text().equals(r.text())) {
                        other = bestD;
                        runner = best;
                    }
                    bestD = d;
                    best = r;
                } else if (d < other && !best.text().equals(r.text())) {
                    other = d;
                    runner = r;
                }
            }
            if (best == null || bestD > ACCEPT) {
                return unreadable(ink, bestD);
            }
            if (other - bestD < MARGIN) {
                best = preferred(best, runner);
                if (best == null) {
                    return unreadable(ink, bestD);
                }
            }
            return new Read(best.text(), ink.outline, best, bestD, advance(ink.print, best.print()),
                    base(ink.print, best.print()), em(settled(ink.print, best.print())));
        }

        private Read unreadable(Ink ink, float distance) {
            float[] rest = resting(ink.print);
            return new Read(STAND_IN, ink.outline, null, distance, ink.print.right(), rest[0], rest[1]);
        }

        private static GlyphRef preferred(GlyphRef best, GlyphRef runner) {
            String a = best.text();
            String b = runner.text();
            if (a.equalsIgnoreCase(b)) {
                return a.equals(a.toLowerCase(Locale.ROOT)) ? best : runner;
            }
            for (String[] pair : TIES) {
                if (pair[0].equals(a) && pair[1].equals(b)) {
                    return best;
                }
                if (pair[0].equals(b) && pair[1].equals(a)) {
                    return runner;
                }
            }
            return null;
        }

        private static float advance(GlyphPrint g, GlyphPrint r) {
            float scale = g.height() / Math.max(r.height(), 1e-3f);
            float right = g.left() + (r.advance() - r.left()) * scale;
            return Math.clamp(right, g.left() + 0.5f * g.width(), g.right());
        }

        private float base(GlyphPrint g, GlyphPrint r) {
            float[] settled = settled(g, r);
            return settled == null ? 0 : baseline(settled);
        }

        private float[] nearest(GlyphPrint g, GlyphPrint r) {
            float[] nearest = null;
            float gap = Float.MAX_VALUE;
            for (float[] size : sizes) {
                float d = offset(g, r, size);
                if (d < gap) {
                    gap = d;
                    nearest = size;
                }
            }
            return nearest;
        }

        private float[] settled(GlyphPrint g, GlyphPrint r) {
            float[] nearest = nearest(g, r);
            if (nearest == null) {
                return null;
            }
            float own = offset(g, r, nearest);
            float other = Float.MAX_VALUE;
            float[] dominant = nearest;
            for (float[] size : sizes) {
                if (size != nearest) {
                    other = Math.min(other, offset(g, r, size));
                }
                if (size[2] > dominant[2]) {
                    dominant = size;
                }
            }
            return own < SETTLED && other > 3 * own ? nearest : dominant;
        }

        private static float offset(GlyphPrint g, GlyphPrint r, float[] size) {
            float scale = (float) Math.exp(size[0]);
            float base = baseline(size);
            return (Math.abs(g.bottom() - base - scale * r.bottom()) + Math.abs(g.top() - base - scale * r.top()))
                    / scale;
        }

        private float[] resting(GlyphPrint g) {
            float[] best = {0, Float.NaN};
            float gap = Float.MAX_VALUE;
            for (float[] size : sizes) {
                float b = baseline(size);
                if (Math.abs(g.bottom() - b) < gap) {
                    gap = Math.abs(g.bottom() - b);
                    best = new float[] {b, em(size)};
                }
            }
            return best;
        }

        private static float em(float[] size) {
            return size == null ? Float.NaN : (float) Math.exp(size[0]);
        }

        private static float baseline(float[] size) {
            return (float) (size[1] * Math.exp(size[0]));
        }

        private float misplaced(GlyphPrint g, GlyphPrint r) {
            float[] nearest = nearest(g, r);
            return nearest == null ? 0 : Math.min(placeWeight * offset(g, r, nearest), placeLimit);
        }
    }
}
