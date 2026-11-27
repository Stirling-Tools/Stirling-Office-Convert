package stirling.software.officeconvert.topdf.psd;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;

import stirling.software.officeconvert.memory.Admission;

public final class PsdPage {

    static final float MAX_PAGE_SIDE = 14_400f;

    static final int DEFLATE_LEVEL = 5;

    static final long MAX_PIXELS = 1L << 32;

    static final long MAX_COMPOSITE_PIXELS = 1L << 30;

    static final long MAX_COMPOSITE_WORK = 16L << 30;

    private PsdPage() {}

    public static boolean is(Path file) throws IOException {
        return PsdDocument.is(file);
    }

    public static long estimate(Path file) throws IOException {
        try (Source src = new Source(file)) {
            PsdDocument doc = PsdDocument.read(src);
            Layers layers = Layers.read(src, doc);
            return Admission.BASE_BYTES + layers.footprint(doc);
        }
    }

    public static boolean draw(Path file, PDDocument pdf, List<String> warnings) throws IOException {
        boolean lost = false;
        try (Source src = new Source(file)) {
            PsdDocument doc = PsdDocument.read(src);
            Layers layers = Layers.read(src, doc);
            warnings.addAll(layers.warnings);
            long pixels = (long) doc.width * doc.height;
            if (pixels > MAX_PIXELS) {
                throw new IOException("The PSD document is " + doc.width + " x " + doc.height
                        + " pixels, more than the " + MAX_PIXELS + " pixels it can convert");
            }
            boolean compose = !doc.realMerged && layers.drawable() && Composer.supports(doc);
            if (compose && (pixels > MAX_COMPOSITE_PIXELS || pixels * Math.max(1, layers.count) > MAX_COMPOSITE_WORK)) {
                throw new IOException("The PSD document was saved without a merged picture and is too large to"
                        + " composite its " + layers.count + " layers (" + doc.width + " x " + doc.height
                        + " pixels); save it with Maximize Compatibility on");
            }
            COSStream image = compose ? Composer.draw(src, doc, layers, pdf, warnings)
                    : merged(src, doc, layers, pdf, warnings);
            if (!doc.realMerged && !compose) {
                lost = layers.count > 0;
                warnings.add("The PSD document was saved without a merged picture and has no layers that can be"
                        + " drawn; the page may be blank. Save it with Maximize Compatibility on");
            }
            place(pdf, doc, image);
        }
        return lost;
    }

    static int alphaChannel(PsdDocument doc, Layers layers) {
        int first = doc.colorChannels();
        if (doc.mode == PsdDocument.MULTICHANNEL || doc.mode == PsdDocument.BITMAP || doc.mode == PsdDocument.INDEXED
                || doc.channels <= first) {
            return -1;
        }
        return layers.mergedAlpha ? first : -1;
    }

    private static COSStream merged(Source src, PsdDocument doc, Layers layers, PDDocument pdf, List<String> warnings)
            throws IOException {
        int alpha = alphaChannel(doc, layers);
        Plan plan = Plan.of(doc, alpha);
        warnings.addAll(plan.warnings);
        Rows[] rows = Merged.open(src, doc, plan.channels);
        int w = doc.width;
        int h = doc.height;
        int cc = plan.channels.length - (alpha >= 0 ? 1 : 0);
        byte[][] in = new byte[plan.channels.length][doc.rowBytes()];
        byte[][] color = alpha >= 0 ? java.util.Arrays.copyOf(in, cc) : in;
        int bits = plan.bits;
        int components = plan.components;
        byte[] out = new byte[(int) (((long) w * components * bits + 7) / 8)];
        byte[] alphaOut = alpha >= 0 ? new byte[(int) (((long) w * bits + 7) / 8)] : null;
        int[] white = white(doc, cc);
        boolean encode = doc.depth == 32 && doc.mode != PsdDocument.LAB && plan.profile == null;
        try (ImageStream colorStream = new ImageStream(pdf, w, h, components, bits, plan.colorSpace(pdf, doc),
                level(doc));
                ImageStream alphaStream = alpha >= 0 ? new ImageStream(pdf, w, h, 1, bits, Spaces.device(1),
                        level(doc)) : null) {
            for (int y = 0; y < h; y++) {
                if ((y & 63) == 0) {
                    checkpoint();
                }
                for (int i = 0; i < rows.length; i++) {
                    rows[i].next(in[i]);
                }
                if (alpha >= 0) {
                    byte[] a = in[cc];
                    switch (doc.depth) {
                        case 8 -> Samples.unmatte8(color, cc, a, w, white);
                        case 16 -> Samples.unmatte16(color, cc, a, w, white);
                        default -> Samples.unmatte32(color, cc, a, w, white);
                    }
                }
                switch (doc.depth) {
                    case 1 -> {
                        for (int i = 0; i < out.length; i++) {
                            out[i] = (byte) ~in[0][i];
                        }
                    }
                    case 8 -> Samples.interleave8(color, components, w, plan.invert, out);
                    case 16 -> Samples.interleave16(color, components, w, plan.invert, out);
                    default -> Samples.interleave32(color, components, w, encode, out);
                }
                colorStream.row(out);
                if (alphaStream != null) {
                    byte[][] a = {in[cc]};
                    switch (doc.depth) {
                        case 8 -> System.arraycopy(in[cc], 0, alphaOut, 0, w);
                        case 16 -> System.arraycopy(in[cc], 0, alphaOut, 0, 2 * w);
                        default -> Samples.interleave32(a, 1, w, false, alphaOut);
                    }
                    alphaStream.row(alphaOut);
                }
            }
            colorStream.finish();
            COSStream image = colorStream.stream();
            if (alphaStream != null) {
                alphaStream.finish();
                image.setItem(COSName.SMASK, alphaStream.stream());
            }
            if (doc.mode == PsdDocument.INDEXED && doc.transparentIndex >= 0 && doc.transparentIndex < doc.paletteCount) {
                COSArray mask = new COSArray();
                mask.add(COSInteger.get(doc.transparentIndex));
                mask.add(COSInteger.get(doc.transparentIndex));
                image.setItem(COSName.MASK, mask);
            }
            boolean damaged = false;
            for (Rows r : rows) {
                damaged |= r.damaged;
            }
            if (damaged) {
                warnings.add("Part of the PSD document's picture data is damaged or missing and was left blank");
            }
            return image;
        } finally {
            for (Rows r : rows) {
                r.close();
            }
        }
    }

    static int[] white(PsdDocument doc, int n) {
        int[] white = new int[n];
        for (int i = 0; i < n; i++) {
            white[i] = doc.mode == PsdDocument.LAB && i > 0 ? 128 : 255;
        }
        return white;
    }

    static int level(PsdDocument doc) {
        long raw = (long) doc.width * doc.height * doc.channels * Math.max(1, doc.depth / 8);
        return raw > (1L << 30) ? 1 : DEFLATE_LEVEL;
    }

    static void checkpoint() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("The conversion was stopped");
        }
        Admission.checkpoint();
    }

    private static void place(PDDocument pdf, PsdDocument doc, COSStream image) throws IOException {
        double pw = doc.width / doc.xDpi * 72;
        double ph = doc.height / doc.yDpi * 72;
        double unit = Math.max(1, Math.ceil(Math.max(pw, ph) / MAX_PAGE_SIDE));
        float w = (float) (pw / unit);
        float h = (float) (ph / unit);
        PDPage page = new PDPage(new PDRectangle(w, h));
        if (unit > 1) {
            page.getCOSObject().setFloat(COSName.getPDFName("UserUnit"), (float) unit);
        }
        PDResources res = new PDResources();
        COSDictionary xobjects = new COSDictionary();
        xobjects.setItem(COSName.getPDFName("Im0"), image);
        res.getCOSObject().setItem(COSName.XOBJECT, xobjects);
        page.setResources(res);
        PDStream content = new PDStream(pdf);
        try (OutputStream o = content.createOutputStream(COSName.FLATE_DECODE)) {
            o.write(String.format(Locale.ROOT, "q %.4f 0 0 %.4f 0 0 cm /Im0 Do Q", w, h)
                    .getBytes(StandardCharsets.US_ASCII));
        }
        page.setContents(content);
        pdf.addPage(page);
    }
}
