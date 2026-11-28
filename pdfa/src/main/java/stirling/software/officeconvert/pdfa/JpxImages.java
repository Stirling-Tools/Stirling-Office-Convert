package stirling.software.officeconvert.pdfa;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import stirling.software.officeconvert.jpx.JpxDecoder;
import stirling.software.officeconvert.jpx.JpxEncoder;
import stirling.software.officeconvert.jpx.JpxImage;
import stirling.software.officeconvert.jpx.JpxSplit;

final class JpxImages {

    static final long MAX_PIXELS = 100_000_000L;

    private static final COSName SMASK_IN_DATA = COSName.getPDFName("SMaskInData");

    private static final List<COSName> REPLACED = List.of(COSName.FILTER, COSName.DECODE_PARMS, COSName.COLORSPACE,
            COSName.BITS_PER_COMPONENT, COSName.DECODE, SMASK_IN_DATA, COSName.WIDTH, COSName.HEIGHT,
            COSName.LENGTH);

    private final PDDocument doc;

    private final PdfALevel level;

    private final Report report;

    private JpxImages(PDDocument doc, PdfALevel level, Report report) {
        this.doc = doc;
        this.level = level;
        this.report = report;
    }

    static CosWalk.Visitor visitor(PDDocument doc, PdfALevel level, Report report) {
        JpxImages j = new JpxImages(doc, level, report);
        return b -> {
            if (b instanceof COSStream s && COSName.IMAGE.equals(s.getCOSName(COSName.SUBTYPE)) && jpx(s)) {
                j.fix(s);
            }
        };
    }

    private static boolean jpx(COSStream s) {
        COSBase f = s.getDictionaryObject(COSName.FILTER);
        if (f instanceof COSArray a) {
            return a.size() > 0 && COSName.JPX_DECODE.equals(a.getObject(a.size() - 1));
        }
        return COSName.JPX_DECODE.equals(f);
    }

    private void fix(COSStream s) throws IOException {
        byte[] encoded = encoded(s);
        int[] size = JpxDecoder.size(encoded);
        long pixels = (long) size[0] * size[1];
        if (size[0] <= 0 || size[1] <= 0 || pixels > MAX_PIXELS || pixels * size[2] > MAX_PIXELS * 4) {
            throw new IOException("A JPEG 2000 codestream is too large to convert for " + level.label());
        }
        s.setInt(COSName.WIDTH, size[0]);
        s.setInt(COSName.HEIGHT, size[1]);
        COSBase colourSpace = s.getDictionaryObject(COSName.COLORSPACE);
        int smaskInData = s.getInt(SMASK_IN_DATA, 0);
        if (level.part() > 1) {
            JpxHeader h = JpxHeader.parse(encoded);
            if (h != null && h.allowedInPdfA(colourSpace != null)
                    && !(level.part() < 4 && smaskInData != 0 && h.channels() == 4 && components(colourSpace) < 4)) {
                return;
            }
            if (h != null && h.wrappable() && !s.getBoolean(COSName.IMAGE_MASK, false) && s.getInt(SMASK_IN_DATA, 0) == 0) {
                byte[] wrapped = JpxHeader.wrap(encoded, size[0], size[1]);
                s.removeItem(COSName.DECODE_PARMS);
                s.setItem(COSName.FILTER, COSName.JPX_DECODE);
                try (OutputStream out = s.createRawOutputStream()) {
                    out.write(wrapped);
                }
                return;
            }
        }
        if (s.getBoolean(COSName.IMAGE_MASK, false)) {
            throw new IOException("The PDF has a JPEG 2000 image mask, which " + level.label() + " does not allow");
        }
        JpxImage jpx = jpx(encoded);
        Candidate copy = level.part() > 1 ? new Candidate(s, encoded, jpx, colourSpace, smaskInData) : null;
        BufferedImage decoded = composed(jpx);
        COSStream from;
        PDImageXObject mask;
        JpxEncoder.Planes maskPlanes = null;
        RawJpx raw = colourSpace == null ? null : RawJpx.of(jpx, colourSpace, smaskInData, decoded);
        if (raw != null) {
            from = raw.colours(doc);
            mask = raw.alpha(doc);
            if (copied(s, copy, from, mask, smaskInData)) {
                return;
            }
            JpxEncoder.Planes planes = level.part() > 1 ? raw.planes() : null;
            if (planes != null) {
                from = JpxRecode.smaller(doc, planes, from, from.getDictionaryObject(COSName.COLORSPACE));
            }
            maskPlanes = level.part() > 1 ? raw.alphaPlanes() : null;
        } else {
            BufferedImage alpha = alpha(decoded);
            BufferedImage opaque = alpha != null ? colours(decoded)
                    : new PDImageXObject(new PDStream(s), null).getOpaqueImage();
            BufferedImage img = plain(opaque);
            boolean photographic = Transparency.photographic(img);
            PDImageXObject x = photographic ? JPEGFactory.createFromImage(doc, img, 0.92f)
                    : GreyImages.lossless(doc, img);
            mask = alpha == null ? null : GreyImages.lossless(doc, alpha);
            if (copied(s, copy, x.getCOSObject(), mask, smaskInData)) {
                return;
            }
            from = (level.part() > 1 ? JpxRecode.smaller(doc, img, x, photographic) : x).getCOSObject();
            boolean small = alpha != null && (long) alpha.getWidth() * alpha.getHeight() <= JpxRecode.MAX_SAMPLES;
            maskPlanes = small && level.part() > 1 ? JpxEncoder.planes(alpha) : null;
        }
        if (mask != null && maskPlanes != null && maskPlanes.depths().length == 1) {
            COSStream smaller = JpxRecode.smaller(doc, maskPlanes, mask.getCOSObject(), COSName.DEVICEGRAY);
            if (smaller != mask.getCOSObject()) {
                mask = new PDImageXObject(new PDStream(smaller), null);
            }
        }
        if (mask != null && smaskInData == 2) {
            COSBase matte = s.getDictionaryObject(COSName.getPDFName("Matte"));
            if (matte instanceof COSArray m && m.size() == components(from.getDictionaryObject(COSName.COLORSPACE))) {
                mask.getCOSObject().setItem(COSName.getPDFName("Matte"), m);
            }
        }
        byte[] data;
        try (InputStream in = from.createRawInputStream()) {
            data = in.readAllBytes();
        }
        for (COSName k : REPLACED) {
            s.removeItem(k);
        }
        for (Map.Entry<COSName, COSBase> e : from.entrySet()) {
            if (!COSName.LENGTH.equals(e.getKey())) {
                s.setItem(e.getKey(), e.getValue());
            }
        }
        try (OutputStream out = s.createRawOutputStream()) {
            out.write(data);
        }
        if (mask != null) {
            s.setItem(COSName.SMASK, mask.getCOSObject());
        }
        report.warn("Converted JPEG 2000 images, which " + level.label() + " does not allow as they were");
    }

    private record Copy(byte[][] parts, int[][] sizes, int colours) {

        long bytes() {
            long n = 0;
            for (byte[] p : parts) {
                n += p.length;
            }
            return n;
        }
    }

    private static Copy exactCopy(COSStream s, byte[] encoded, JpxImage jpx, COSBase colourSpace, int smaskInData,
            long budget) throws IOException {
        JpxHeader h = JpxHeader.parse(encoded);
        if (h == null || h.palette() || jpx.palette() || !plainColour(h)
                || colourSpace == null && h.colours().isEmpty() || colourSpace != null && !plainSpace(colourSpace)) {
            return null;
        }
        int n = colourSpace != null ? components(colourSpace) : switch (h.colours().get(0)[2]) {
            case 17 -> 1;
            case 16 -> 3;
            default -> 4;
        };
        int total = jpx.raster().components();
        if (n < 1 || total < n) {
            return null;
        }
        int alpha = s.containsKey(COSName.SMASK) ? -1 : RawJpx.alphaComponent(jpx, n, smaskInData);
        int[] colours = new int[n];
        for (int c = 0, i = 0; i < n; c++) {
            if (c != alpha) {
                colours[i++] = c;
            }
        }
        if (!uniform(jpx, colours) || !inOrder(jpx, colours)) {
            return null;
        }
        int[][] groups = alpha >= 0 ? new int[][] {colours, {alpha}} : new int[][] {colours};
        byte[][] parts;
        try {
            parts = JpxSplit.split(encoded, groups, jpx, budget);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (parts == null) {
            return null;
        }
        byte[][] wrapped = new byte[parts.length][];
        int[][] sizes = new int[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            sizes[i] = JpxDecoder.size(parts[i]);
            wrapped[i] = JpxHeader.wrap(parts[i], sizes[i][0], sizes[i][1]);
            JpxHeader w = JpxHeader.parse(wrapped[i]);
            if (w == null || !w.allowedInPdfA(colourSpace != null || i > 0)) {
                return null;
            }
        }
        return new Copy(wrapped, sizes, n);
    }

    private static boolean uniform(JpxImage jpx, int[] colours) {
        for (int c : colours) {
            if (jpx.raster().depth(c) != jpx.raster().depth(colours[0])
                    || jpx.raster().signed(c) != jpx.raster().signed(colours[0])) {
                return false;
            }
        }
        return true;
    }

    private static boolean inOrder(JpxImage jpx, int[] colours) {
        int[][] defs = jpx.channelDefinitions();
        if (defs == null) {
            return true;
        }
        for (int[] d : defs) {
            for (int i = 0; i < colours.length; i++) {
                if (d[0] == colours[i] && d[1] == 0 && d[2] != i + 1) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean plainSpace(COSBase colourSpace) {
        if (COSName.DEVICEGRAY.equals(colourSpace) || COSName.DEVICERGB.equals(colourSpace)
                || COSName.DEVICECMYK.equals(colourSpace)) {
            return true;
        }
        return colourSpace instanceof COSArray a && a.size() == 2 && COSName.ICCBASED.equals(a.getObject(0));
    }

    private void apply(COSStream s, Copy copy, int smaskInData) throws IOException {
        if (copy.parts().length > 1) {
            COSStream mask = doc.getDocument().createCOSStream();
            try (OutputStream out = mask.createRawOutputStream()) {
                out.write(copy.parts()[1]);
            }
            mask.setItem(COSName.TYPE, COSName.XOBJECT);
            mask.setItem(COSName.SUBTYPE, COSName.IMAGE);
            mask.setItem(COSName.FILTER, COSName.JPX_DECODE);
            mask.setInt(COSName.WIDTH, copy.sizes()[1][0]);
            mask.setInt(COSName.HEIGHT, copy.sizes()[1][1]);
            mask.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
            COSBase matte = s.getDictionaryObject(COSName.getPDFName("Matte"));
            if (smaskInData == 2 && matte instanceof COSArray m && m.size() == copy.colours()) {
                mask.setItem(COSName.getPDFName("Matte"), m);
            }
            s.setItem(COSName.SMASK, mask);
        }
        s.removeItem(COSName.DECODE_PARMS);
        s.removeItem(COSName.BITS_PER_COMPONENT);
        s.removeItem(SMASK_IN_DATA);
        s.setItem(COSName.FILTER, COSName.JPX_DECODE);
        s.setInt(COSName.WIDTH, copy.sizes()[0][0]);
        s.setInt(COSName.HEIGHT, copy.sizes()[0][1]);
        try (OutputStream out = s.createRawOutputStream()) {
            out.write(copy.parts()[0]);
        }
    }

    private static boolean plainColour(JpxHeader h) {
        if (h.colours().isEmpty()) {
            return true;
        }
        int[] first = h.colours().get(0);
        return first[0] == 1 && (first[2] == 16 || first[2] == 17 || first[2] == 12);
    }

    private static final class Candidate {

        private static final int WRAPPING = 512;

        private final COSStream s;
        private final byte[] encoded;
        private final JpxImage jpx;
        private final COSBase colourSpace;
        private final int smaskInData;
        private long tried = -1;
        private Copy copy;

        Candidate(COSStream s, byte[] encoded, JpxImage jpx, COSBase colourSpace, int smaskInData) {
            this.s = s;
            this.encoded = encoded;
            this.jpx = jpx;
            this.colourSpace = colourSpace;
            this.smaskInData = smaskInData;
        }

        Copy within(long budget) throws IOException {
            if (jpx.raster().components() == used() && codestreamLength(encoded) * 0.9 > budget + WRAPPING) {
                return null;
            }
            if (tried < 0 || copy == null && budget > tried) {
                tried = budget;
                copy = exactCopy(s, encoded, jpx, colourSpace, smaskInData, budget);
            }
            return copy != null && copy.bytes() <= budget ? copy : null;
        }

        private int used() {
            int n = colourSpace != null ? components(colourSpace) : jpx.raster().components();
            boolean alpha = !s.containsKey(COSName.SMASK) && RawJpx.alphaComponent(jpx, n, smaskInData) >= 0;
            return n + (alpha ? 1 : 0);
        }
    }

    private static long codestreamLength(byte[] data) {
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0x4F) {
            return data.length;
        }
        long at = 0;
        while (at + 8 <= data.length) {
            long size = (data[(int) at] & 0xFFL) << 24 | (data[(int) at + 1] & 0xFF) << 16
                    | (data[(int) at + 2] & 0xFF) << 8 | data[(int) at + 3] & 0xFF;
            boolean codestream = data[(int) at + 4] == 'j' && data[(int) at + 5] == 'p' && data[(int) at + 6] == '2'
                    && data[(int) at + 7] == 'c';
            if (size == 0) {
                size = data.length - at;
            }
            if (codestream) {
                return size - 8;
            }
            if (size < 8) {
                return 0;
            }
            at += size;
        }
        return 0;
    }

    private boolean copied(COSStream s, Candidate candidate, COSStream from, PDImageXObject mask, int smaskInData)
            throws IOException {
        long converted = from.getLength() + (mask == null ? 0 : mask.getCOSObject().getLength());
        Copy copy = candidate == null ? null : candidate.within(converted);
        if (copy == null) {
            return false;
        }
        apply(s, copy, smaskInData);
        report.warn("Converted JPEG 2000 images, which " + level.label() + " does not allow as they were");
        return true;
    }

    private static BufferedImage plain(BufferedImage image) {
        BufferedImage img = image;
        if (img.getType() != BufferedImage.TYPE_BYTE_GRAY && img.getType() != BufferedImage.TYPE_INT_RGB) {
            BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(),
                    img.getColorModel().getNumColorComponents() == 1 ? BufferedImage.TYPE_BYTE_GRAY
                            : BufferedImage.TYPE_INT_RGB);
            rgb.createGraphics().drawImage(img, 0, 0, null);
            img = rgb;
        }
        return img;
    }

    private static JpxImage jpx(byte[] encoded) throws IOException {
        try {
            return JpxDecoder.decode(encoded);
        } catch (RuntimeException e) {
            throw new IOException("A JPEG 2000 image could not be read: " + e.getMessage(), e);
        }
    }

    private static BufferedImage composed(JpxImage jpx) throws IOException {
        try {
            return jpx.toBufferedImage();
        } catch (RuntimeException e) {
            throw new IOException("A JPEG 2000 image could not be read: " + e.getMessage(), e);
        }
    }

    static int components(COSBase colourSpace) {
        if (colourSpace == null) {
            return -1;
        }
        try {
            return PDColorSpace.create(colourSpace).getNumberOfComponents();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private static BufferedImage alpha(BufferedImage img) {
        Raster a = img.getAlphaRaster();
        if (a == null) {
            return null;
        }
        BufferedImage mask = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        mask.setData(a.createTranslatedChild(0, 0));
        return mask;
    }

    private static BufferedImage colours(BufferedImage img) {
        ColorModel cm = img.getColorModel();
        int[] bands = new int[cm.getNumColorComponents()];
        for (int i = 0; i < bands.length; i++) {
            bands[i] = i;
        }
        WritableRaster r = img.getRaster().createWritableChild(0, 0, img.getWidth(), img.getHeight(), 0, 0, bands);
        if (bands.length == 1 && r.getTransferType() == DataBuffer.TYPE_BYTE) {
            BufferedImage grey = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
            grey.setData(r);
            return grey;
        }
        ColorModel opaque = new ComponentColorModel(cm.getColorSpace(), false, false, ColorModel.OPAQUE,
                r.getTransferType());
        return new BufferedImage(opaque, r, false, null);
    }

    private static byte[] encoded(COSStream s) throws IOException {
        return Decoded.before(s, COSName.JPX_DECODE, 64L << 20, "A JPEG 2000 codestream");
    }
}
