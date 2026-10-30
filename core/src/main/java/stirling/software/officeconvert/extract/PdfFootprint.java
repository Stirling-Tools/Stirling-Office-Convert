package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import stirling.software.officeconvert.memory.Admission;

/** The heap a conversion of a loaded PDF is likely to need, from its page tree and picture dictionaries alone: the
 * pages sampled for document statistics, what PDFBox keeps of every page read, the largest picture that is decoded and
 * figures rendered finer than usual. Nothing is decoded or drawn. */
public final class PdfFootprint {

    static final int SAMPLED_PAGES = 60;

    static final long SAMPLED_PAGE_BYTES = 256L << 10;

    static final long PAGE_BYTES = 112L << 10;

    static final long MAX_RENDER_PIXELS = 16_000_000L;

    // The page costs were measured with figures drawn at this resolution; only a finer one costs more
    static final float MEASURED_DPI = 150f;

    static final int MAX_SCANNED_PAGES = 2_000;

    static final int MAX_SCANNED_OBJECTS = 20_000;

    static final long MAX_SCANNED_CONTENT = 64L << 20;

    static final int MAX_NESTING = 8;

    static final long UNSCANNED_PICTURE = ImageBudget.MAX_DECODED_BYTES;

    static final long FAILED = Admission.BASE_BYTES + SAMPLED_PAGES * SAMPLED_PAGE_BYTES + UNSCANNED_PICTURE
            + 4 * MAX_RENDER_PIXELS;

    private static final int CHUNK = 1 << 20;

    private static final int INLINE_WINDOW = 4 << 10;

    private PdfFootprint() {}

    public static long estimate(PDDocument doc, int firstPage, int lastPage, float dpi) {
        try {
            return scan(doc, firstPage, lastPage, dpi);
        } catch (RuntimeException e) {
            return FAILED;
        }
    }

    private static long scan(PDDocument doc, int firstPage, int lastPage, float dpi) {
        int total = doc.getNumberOfPages();
        int first = firstPage > 0 ? Math.min(firstPage, total) - 1 : 0;
        int last = lastPage > 0 ? Math.min(lastPage, total) - 1 : total - 1;
        int pages = Math.max(1, last - first + 1);
        long model = Math.min(pages, SAMPLED_PAGES) * SAMPLED_PAGE_BYTES + pages * PAGE_BYTES;
        Scan scan = new Scan();
        int end = Math.min(last, first + MAX_SCANNED_PAGES - 1);
        if (end < last) {
            scan.unknown();
        }
        int i = 0;
        for (PDPage page : doc.getPages()) {
            if (i > end) {
                break;
            }
            if (scan.budget <= 0) {
                scan.unknown();
                break;
            }
            if (i++ < first) {
                continue;
            }
            try {
                PDRectangle box = page.getCropBox();
                scan.area = Math.max(scan.area, (double) box.getWidth() * box.getHeight());
                PDResources resources = page.getResources();
                scan.resources(resources == null ? null : resources.getCOSObject(), 0);
                if (page.hasContents()) {
                    try (InputStream in = page.getContents()) {
                        scan.content(in);
                    }
                }
                scan.annotations(page.getCOSObject().getCOSArray(COSName.ANNOTS));
            } catch (IOException | RuntimeException e) {
                scan.unknown();
            }
        }
        long render = renderBytes(scan.area, dpi) - renderBytes(scan.area, MEASURED_DPI);
        long sum = model + scan.picture + Math.max(0, render);
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    private static long renderBytes(double area, float dpi) {
        double scale = dpi / 72.0;
        return 4 * (long) Math.min(MAX_RENDER_PIXELS, area * scale * scale);
    }

    private static long samples(long width, long height, int perPixel) {
        if (width <= 0 || height <= 0) {
            return 0;
        }
        if (width > Long.MAX_VALUE / height || width * height > Long.MAX_VALUE / perPixel) {
            return Long.MAX_VALUE;
        }
        return width * height * perPixel;
    }

    private static long affordable(long bytes) {
        return bytes > ImageBudget.MAX_DECODED_BYTES || bytes < 0 ? 0 : bytes;
    }

    static int components(COSBase space) {
        if (space instanceof COSArray a && a.size() > 0 && a.getObject(0) instanceof COSName family) {
            if (COSName.ICCBASED.equals(family) && a.size() > 1 && a.getObject(1) instanceof COSStream icc) {
                return Math.max(1, Math.min(4, icc.getInt(COSName.N, 4)));
            }
            if (COSName.DEVICEN.equals(family) && a.size() > 1 && a.getObject(1) instanceof COSArray names) {
                return Math.max(1, Math.min(32, names.size()));
            }
            return components(family);
        }
        if (!(space instanceof COSName n)) {
            return 4;
        }
        return switch (n.getName()) {
            case "DeviceGray", "G", "CalGray", "Indexed", "I", "Separation", "Pattern" -> 1;
            case "DeviceRGB", "RGB", "CalRGB", "Lab" -> 3;
            default -> 4;
        };
    }

    static long decoded(COSStream image) {
        long width = image.getInt(COSName.WIDTH, 0);
        long height = image.getInt(COSName.HEIGHT, 0);
        boolean stencil = image.getBoolean(COSName.IMAGE_MASK, false);
        int components = stencil ? 1 : components(image.getDictionaryObject(COSName.COLORSPACE));
        COSBase mask = image.getDictionaryObject(COSName.MASK);
        long base = samples(width, height, components + 4 + (mask instanceof COSArray ? 5 : 0));
        COSStream applied = image.getCOSStream(COSName.SMASK);
        if (applied == null && mask instanceof COSStream m && m.getBoolean(COSName.IMAGE_MASK, false)) {
            applied = m;
        }
        if (applied == null) {
            return affordable(base);
        }
        long mw = applied.getInt(COSName.WIDTH, 0);
        long mh = applied.getInt(COSName.HEIGHT, 0);
        long masked = samples(Math.max(width, mw), Math.max(height, mh), 4);
        long sum = base + samples(mw, mh, 5) + masked;
        return masked > ImageBudget.MAX_DECODED_BYTES || base > ImageBudget.MAX_DECODED_BYTES ? 0 : Math.max(0, sum);
    }

    static long inline(Map<String, String> params) {
        long width = number(params, "W", "Width");
        long height = number(params, "H", "Height");
        boolean stencil = "true".equals(value(params, "IM", "ImageMask"));
        String space = value(params, "CS", "ColorSpace");
        int components;
        if (stencil) {
            components = 1;
        } else if (space == null) {
            components = 4;
        } else if (space.startsWith("[")) {
            String family = space.substring(1);
            components = family.equals("/I") || family.equals("/Indexed") ? 1 : 4;
        } else {
            components = space.startsWith("/") ? components(COSName.getPDFName(space.substring(1))) : 4;
        }
        return affordable(samples(width, height, components + 4));
    }

    private static String value(Map<String, String> params, String abbreviated, String full) {
        String v = params.get(abbreviated);
        return v != null ? v : params.get(full);
    }

    private static long number(Map<String, String> params, String abbreviated, String full) {
        String v = value(params, abbreviated, full);
        if (v == null) {
            return 0;
        }
        try {
            double d = Double.parseDouble(v);
            return d > Integer.MAX_VALUE ? Integer.MAX_VALUE : (long) d;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static final class Scan {

        final Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        int budget = MAX_SCANNED_OBJECTS;

        long contentLeft = MAX_SCANNED_CONTENT;

        long picture;

        double area;

        private byte[] buffer;

        void unknown() {
            picture = Math.max(picture, UNSCANNED_PICTURE);
        }

        void picture(long bytes) {
            picture = Math.max(picture, bytes);
        }

        boolean visit(COSBase object) {
            if (object == null) {
                return false;
            }
            if (budget-- <= 0) {
                unknown();
                return false;
            }
            return seen.add(object);
        }

        void resources(COSDictionary resources, int depth) {
            if (depth > MAX_NESTING) {
                unknown();
                return;
            }
            if (!visit(resources)) {
                return;
            }
            COSDictionary xobjects = resources.getCOSDictionary(COSName.XOBJECT);
            if (xobjects != null) {
                for (COSName name : xobjects.keySet()) {
                    if (!(xobjects.getDictionaryObject(name) instanceof COSStream s) || !visit(s)) {
                        continue;
                    }
                    COSName subtype = s.getCOSName(COSName.SUBTYPE);
                    if (COSName.IMAGE.equals(subtype)) {
                        picture(decoded(s));
                    } else if (COSName.FORM.equals(subtype)) {
                        form(s, depth + 1);
                    }
                }
            }
            COSDictionary patterns = resources.getCOSDictionary(COSName.PATTERN);
            if (patterns != null) {
                for (COSName name : patterns.keySet()) {
                    if (patterns.getDictionaryObject(name) instanceof COSStream s && visit(s)) {
                        form(s, depth + 1);
                    }
                }
            }
            COSDictionary states = resources.getCOSDictionary(COSName.EXT_G_STATE);
            if (states != null) {
                for (COSName name : states.keySet()) {
                    if (states.getDictionaryObject(name) instanceof COSDictionary gs
                            && gs.getDictionaryObject(COSName.SMASK) instanceof COSDictionary mask
                            && mask.getDictionaryObject(COSName.G) instanceof COSStream group && visit(group)) {
                        form(group, depth + 1);
                    }
                }
            }
            COSDictionary fonts = resources.getCOSDictionary(COSName.FONT);
            if (fonts != null) {
                for (COSName name : fonts.keySet()) {
                    if (fonts.getDictionaryObject(name) instanceof COSDictionary font
                            && COSName.TYPE3.equals(font.getCOSName(COSName.SUBTYPE)) && visit(font)) {
                        type3(font, depth + 1);
                    }
                }
            }
        }

        void type3(COSDictionary font, int depth) {
            COSDictionary own = font.getCOSDictionary(COSName.RESOURCES);
            if (own != null) {
                resources(own, depth);
            }
            COSDictionary procs = font.getCOSDictionary(COSName.CHAR_PROCS);
            if (procs == null) {
                return;
            }
            for (COSName glyph : procs.keySet()) {
                if (procs.getDictionaryObject(glyph) instanceof COSStream proc && visit(proc)) {
                    stream(proc);
                }
            }
        }

        void form(COSStream form, int depth) {
            COSDictionary own = form.getCOSDictionary(COSName.RESOURCES);
            if (own != null) {
                resources(own, depth);
            }
            stream(form);
        }

        void annotations(COSArray annots) {
            if (annots == null) {
                return;
            }
            for (int i = 0; i < annots.size(); i++) {
                if (!(annots.getObject(i) instanceof COSDictionary annot) || !visit(annot)
                        || !(annot.getDictionaryObject(COSName.AP) instanceof COSDictionary ap)) {
                    continue;
                }
                for (COSName kind : new COSName[] {COSName.N, COSName.R, COSName.D}) {
                    COSBase appearance = ap.getDictionaryObject(kind);
                    if (appearance instanceof COSStream s) {
                        if (visit(s)) {
                            form(s, 1);
                        }
                    } else if (appearance instanceof COSDictionary states) {
                        for (COSName state : states.keySet()) {
                            if (states.getDictionaryObject(state) instanceof COSStream s && visit(s)) {
                                form(s, 1);
                            }
                        }
                    }
                }
            }
        }

        void stream(COSStream stream) {
            try (InputStream in = stream.createInputStream()) {
                content(in);
            } catch (IOException | RuntimeException e) {
                unknown();
            }
        }

        void content(InputStream in) throws IOException {
            if (buffer == null) {
                buffer = new byte[CHUNK + INLINE_WINDOW];
            }
            byte[] buf = buffer;
            int len = 0;
            int from = 0;
            boolean start = true;
            while (true) {
                int want = (int) Math.min(buf.length - len, contentLeft);
                int n = in.readNBytes(buf, len, want);
                contentLeft -= n;
                len += n;
                boolean full = len == buf.length;
                if (!full && contentLeft <= 0 && in.read() >= 0) {
                    unknown();
                    return;
                }
                int limit = full ? len - INLINE_WINDOW : len;
                for (int i = from; i < limit && i + 1 < len; i++) {
                    if (buf[i] == 'B' && buf[i + 1] == 'I' && (i == 0 ? start : boundary(buf[i - 1]))
                            && (i + 2 >= len || boundary(buf[i + 2]))) {
                        inline(buf, i + 2, len, !full);
                    }
                }
                if (!full) {
                    return;
                }
                int keep = len - limit + 1;
                System.arraycopy(buf, limit - 1, buf, 0, keep);
                len = keep;
                from = 1;
                start = false;
            }
        }

        private void inline(byte[] buf, int at, int end, boolean whole) {
            Tokens tokens = new Tokens(buf, at, end);
            Map<String, String> params = new HashMap<>();
            while (true) {
                String key = tokens.next();
                if (key == null) {
                    if (!whole) {
                        unknown();
                    }
                    return;
                }
                if (key.equals("ID")) {
                    picture(PdfFootprint.inline(params));
                    return;
                }
                if (!key.startsWith("/") || key.length() < 2) {
                    return;
                }
                String value = tokens.next();
                if (value == null) {
                    if (!whole) {
                        unknown();
                    }
                    return;
                }
                params.put(key.substring(1), value);
            }
        }
    }

    static boolean boundary(byte b) {
        return switch (b) {
            case ' ', '\n', '\r', '\t', '\f', 0, '(', ')', '<', '>', '[', ']', '{', '}', '/', '%' -> true;
            default -> false;
        };
    }

    static final class Tokens {

        private final byte[] buf;

        private int at;

        private final int end;

        Tokens(byte[] buf, int at, int end) {
            this.buf = buf;
            this.at = at;
            this.end = end;
        }

        String next() {
            skipSpace();
            if (at >= end) {
                return null;
            }
            byte c = buf[at];
            if (c == '[' || c == '<' && at + 1 < end && buf[at + 1] == '<') {
                String first = c == '[' ? firstInside(at + 1) : null;
                if (!skipNested()) {
                    return null;
                }
                return c == '[' ? "[" + (first == null ? "" : first) : "<<";
            }
            if (c == '(') {
                return skipString() ? "(" : null;
            }
            if (c == '<') {
                while (at < end && buf[at] != '>') {
                    at++;
                }
                if (at >= end) {
                    return null;
                }
                at++;
                return "<";
            }
            int begin = at;
            at++;
            while (at < end && !boundary(buf[at])) {
                at++;
            }
            if (at >= end) {
                return null;
            }
            return new String(buf, begin, at - begin, StandardCharsets.ISO_8859_1);
        }

        private String firstInside(int from) {
            Tokens inner = new Tokens(buf, from, end);
            String t = inner.next();
            return t != null && t.startsWith("/") ? t : null;
        }

        private void skipSpace() {
            while (at < end) {
                byte c = buf[at];
                if (c == '%') {
                    while (at < end && buf[at] != '\n' && buf[at] != '\r') {
                        at++;
                    }
                } else if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\f' || c == 0) {
                    at++;
                } else {
                    return;
                }
            }
        }

        private boolean skipNested() {
            int depth = 0;
            while (at < end) {
                byte c = buf[at];
                if (c == '(') {
                    if (!skipString()) {
                        return false;
                    }
                    continue;
                }
                if (c == '%') {
                    skipSpace();
                    continue;
                }
                if (c == '[' || c == '<' && at + 1 < end && buf[at + 1] == '<') {
                    depth++;
                    at += c == '[' ? 1 : 2;
                } else if (c == ']' || c == '>' && at + 1 < end && buf[at + 1] == '>') {
                    depth--;
                    at += c == ']' ? 1 : 2;
                } else {
                    at++;
                }
                if (depth == 0) {
                    return true;
                }
            }
            return false;
        }

        private boolean skipString() {
            int depth = 0;
            while (at < end) {
                byte c = buf[at++];
                if (c == '\\') {
                    at++;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')' && --depth == 0) {
                    return true;
                }
            }
            return false;
        }
    }
}
