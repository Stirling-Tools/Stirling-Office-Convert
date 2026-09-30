package stirling.software.officeconvert.extract;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import stirling.software.officeconvert.extract.PageGraphics.Fill;
import stirling.software.officeconvert.extract.PageGraphics.ImageDraw;
import stirling.software.officeconvert.extract.PageGraphics.Rule;
import stirling.software.officeconvert.extract.PageGraphics.VectorMark;

final class BlankPaint {

    private static final float MIN_SHARE = 0.01f;
    private static final float PAPER = 0.9f;
    private static final double PLAIN_BYTES = 0.1;
    private static final int ALLOWANCE = 4096;
    private static final long MAX_PIXELS = 4_000_000;
    private static final int WHITE = 250;
    private static final int QUICK = 4;
    private static final int BAND = 16;

    private static final Map<COSStream, Boolean> WHITE_IMAGES = Collections.synchronizedMap(new WeakHashMap<>());

    private BlankPaint() {}

    static void remove(List<Fill> fills, List<VectorMark> marks, List<ImageDraw> images, List<Rule> rules,
            Map<Object, Integer> order, float pageArea) {
        Set<Object> gone = Collections.newSetFromMap(new IdentityHashMap<>());
        Paint all = new Paint(fills, marks, images, rules, order, gone);
        List<Object> papers = new ArrayList<>();
        fills.stream().filter(f -> white(f.rgb()) && (f.right() - f.x()) * (f.bottom() - f.top()) >= PAPER * pageArea)
                .forEach(papers::add);
        marks.stream().filter(m -> m.filled() && !m.stroked() && !m.shading() && white(m.rgb())
                && (m.right() - m.x()) * (m.bottom() - m.top()) >= PAPER * pageArea).forEach(papers::add);
        papers.sort(Comparator.comparingInt(p -> order.getOrDefault(p, -1)));
        for (Object p : papers) {
            boolean bare = p instanceof Fill f ? all.bareUnder(f, f.x(), f.top(), f.right(), f.bottom())
                    : p instanceof VectorMark m && all.bareUnder(m, m.x(), m.top(), m.right(), m.bottom());
            if (bare) {
                gone.add(p);
            }
        }
        fills.removeIf(gone::contains);
        marks.removeIf(gone::contains);
        images.removeIf(i -> (i.clipRight() - i.clipX()) * (i.clipBottom() - i.clipTop()) >= MIN_SHARE * pageArea
                && all.bareUnder(i, i.clipX(), i.clipTop(), i.clipRight(), i.clipBottom()) && plainWhite(i));
    }

    private static boolean white(int rgb) {
        return ((rgb >> 16) & 0xFF) >= WHITE && ((rgb >> 8) & 0xFF) >= WHITE && (rgb & 0xFF) >= WHITE;
    }

    private record Paint(List<Fill> fills, List<VectorMark> marks, List<ImageDraw> images, List<Rule> rules,
            Map<Object, Integer> order, Set<Object> gone) {

        boolean bareUnder(Object self, float x, float top, float right, float bottom) {
            int at = order.getOrDefault(self, -1);
            for (Fill f : fills) {
                if (f != self && !gone.contains(f) && order.getOrDefault(f, -1) < at
                        && overlaps(x, top, right, bottom, f.x(), f.top(), f.right(), f.bottom())) {
                    return false;
                }
            }
            for (Rule r : rules) {
                float t = r.thickness() / 2f;
                boolean over = r.horizontal()
                        ? overlaps(x, top, right, bottom, r.start(), r.pos() - t, r.end(), r.pos() + t)
                        : overlaps(x, top, right, bottom, r.pos() - t, r.start(), r.pos() + t, r.end());
                if (order.getOrDefault(r, -1) < at && over) {
                    return false;
                }
            }
            for (VectorMark m : marks) {
                if (m != self && !gone.contains(m) && order.getOrDefault(m, -1) < at && overlaps(x, top, right, bottom, m.x(), m.top(), m.right(), m.bottom())) {
                    return false;
                }
            }
            for (ImageDraw o : images) {
                if (o != self && order.getOrDefault(o, -1) < at
                        && overlaps(x, top, right, bottom, o.clipX(), o.clipTop(), o.clipRight(), o.clipBottom())) {
                    return false;
                }
            }
            return true;
        }

        private static boolean overlaps(float x, float top, float right, float bottom, float x2, float top2, float right2,
                float bottom2) {
            return x2 < right && x < right2 && top2 < bottom && top < bottom2;
        }
    }

    private static boolean plainWhite(ImageDraw i) {
        if (i.stencilRgb() >= 0 || !(i.image() instanceof PDImageXObject x)) {
            return false;
        }
        Boolean known = WHITE_IMAGES.get(x.getCOSObject());
        if (known == null) {
            known = plainWhite(x);
            WHITE_IMAGES.put(x.getCOSObject(), known);
        }
        return known;
    }

    private static boolean plainWhite(PDImageXObject x) {
        long pixels = (long) x.getWidth() * x.getHeight();
        if (pixels <= 0 || pixels > MAX_PIXELS || x.getCOSObject().getLength() > PLAIN_BYTES * pixels + ALLOWANCE
                || !ImageBudget.affordable(x)) {
            return false;
        }
        try {
            Boolean raw = rawWhite(x);
            if (raw != null) {
                return raw;
            }
            if (JpegDc.dark(x)) {
                return false;
            }
            if (Math.min(x.getWidth(), x.getHeight()) >= QUICK * 16 && (dark(x) || !allWhite(x.getImage(null, QUICK)))) {
                return false;
            }
            return allWhite(x.getImage());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // The top rows of the quick image, decoded alone: a photo shows colour there without decoding the rest
    private static boolean dark(PDImageXObject x) throws IOException {
        COSStream s = x.getCOSObject();
        boolean plain = s.getFilters() instanceof COSName f && (COSName.DCT_DECODE.equals(f)
                || COSName.FLATE_DECODE.equals(f));
        if (!plain || x.getHeight() <= BAND || s.containsKey(COSName.SMASK) || s.containsKey(COSName.MASK)) {
            return false;
        }
        return !allWhite(x.getImage(new Rectangle(0, 0, x.getWidth(), BAND), QUICK));
    }

    private static boolean allWhite(BufferedImage img) {
        int[] row = new int[img.getWidth()];
        for (int y = 0; y < img.getHeight(); y++) {
            img.getRGB(0, y, row.length, 1, row, 0, row.length);
            for (int p : row) {
                if (p >>> 24 != 0 && (((p >> 16) & 0xFF) < WHITE || ((p >> 8) & 0xFF) < WHITE || (p & 0xFF) < WHITE)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Boolean rawWhite(PDImageXObject x) throws IOException {
        COSStream s = x.getCOSObject();
        boolean flate = s.getFilters() instanceof COSName f
                && (COSName.FLATE_DECODE.equals(f) || COSName.FLATE_DECODE_ABBREVIATION.equals(f));
        if (!flate || x.getBitsPerComponent() != 8 || x.isStencil() || x.getDecode() != null && x.getDecode().size() > 0
                || s.containsKey(COSName.SMASK) || s.containsKey(COSName.MASK)) {
            return null;
        }
        PDColorSpace cs = x.getColorSpace();
        if (!(cs instanceof PDDeviceRGB || cs instanceof PDDeviceGray)) {
            return null;
        }
        Samples out = new Samples((long) x.getWidth() * x.getHeight() * cs.getNumberOfComponents());
        try (InputStream raw = s.createRawInputStream()) {
            FilterFactory.INSTANCE.getFilter(COSName.FLATE_DECODE).decode(raw, out, s, 0);
        } catch (Dark dark) {
            return false;
        }
        return out.count >= out.wanted;
    }

    private static final class Samples extends OutputStream {

        final long wanted;
        long count;

        Samples(long wanted) {
            this.wanted = wanted;
        }

        @Override
        public void write(int b) throws IOException {
            if (count < wanted) {
                if ((b & 0xFF) < WHITE) {
                    throw new Dark();
                }
                count++;
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            for (int k = off; k < off + len && count < wanted; k++) {
                if ((b[k] & 0xFF) < WHITE) {
                    throw new Dark();
                }
                count++;
            }
        }
    }

    private static final class Dark extends IOException {
        Dark() {
            super("A sample is not white");
        }
    }
}
