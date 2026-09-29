package stirling.software.officeconvert.topdf.io;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DirectColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.zip.Deflater;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

// Lossless pictures as Flate with PNG predictors None or Up; PDFBox's encoder tries five per row, 2 to 4 times slower
final class FlateImages {

    // Level 5 deflates image rows 1.6 to 2 times faster than the default 6 for about 4% more bytes
    static final int LEVEL = 5;

    static final int NONE = 0;

    static final int UP = 2;

    static final int ADAPTIVE = -1;

    private static final int SAMPLE_ROWS = 64;

    private static final int STRIP_ROWS = 64;

    private FlateImages() {}

    static PDImageXObject encode(PDDocument doc, BufferedImage image) throws IOException {
        Rows rows = new Rows(image);
        int w = image.getWidth();
        int h = image.getHeight();
        int[] predictors = rows.converted() ? new int[] {UP, UP} : predictors(rows, h);
        try (Writer out = new Writer(doc, w, h, rows.bands, rows.alpha)) {
            for (int y = 0; y < h; y++) {
                if ((y & 127) == 0) {
                    stopIfInterrupted();
                }
                boolean translucent = rows.read(y, out.color(), out.alpha());
                out.row(predictors[0], predictors[1], translucent);
            }
            return out.finish(doc);
        }
    }

    // PNG filter type for every row: None suits flat graphics, Up suits smooth pictures; chosen on a sample of rows
    private static int[] predictors(Rows rows, int h) {
        int w = rows.image.getWidth();
        byte[] above = new byte[w * rows.bands];
        byte[] row = new byte[w * rows.bands];
        byte[] aboveAlpha = rows.alpha ? new byte[w] : null;
        byte[] rowAlpha = rows.alpha ? new byte[w] : null;
        long[] sums = new long[4];
        int step = Math.max(1, h / SAMPLE_ROWS);
        for (int y = Math.min(1, h - 1); y < h; y += step) {
            rows.read(Math.max(0, y - 1), above, aboveAlpha);
            rows.read(y, row, rowAlpha);
            add(sums, 0, row, y > 0 ? above : null);
            if (rowAlpha != null) {
                add(sums, 2, rowAlpha, y > 0 ? aboveAlpha : null);
            }
        }
        return new int[] {sums[1] < sums[0] ? UP : NONE, sums[3] < sums[2] ? UP : NONE};
    }

    private static void add(long[] sums, int at, byte[] row, byte[] above) {
        for (int i = 0; i < row.length; i++) {
            sums[at] += Math.abs((int) row[i]);
            sums[at + 1] += Math.abs((byte) (row[i] - (above == null ? 0 : above[i])));
        }
    }

    private static void stopIfInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Conversion interrupted");
        }
    }

    // Writes an image row by row: fill color() and alpha(), then row(); the alpha becomes a soft mask only if used
    static final class Writer implements AutoCloseable {

        private final int width;

        private final int height;

        private final int bands;

        private final Channel color;

        private final Channel alpha;

        private int y;

        private boolean translucent;

        Writer(PDDocument doc, int width, int height, int bands, boolean alpha) throws IOException {
            this.width = width;
            this.height = height;
            this.bands = bands;
            this.color = new Channel(doc, width * bands, bands);
            this.alpha = alpha ? new Channel(null, width, 1) : null;
        }

        byte[] color() {
            return color.current;
        }

        byte[] alpha() {
            return alpha == null ? null : alpha.current;
        }

        void row(int colorPredictor, int alphaPredictor, boolean translucentRow) throws IOException {
            color.write(y, colorPredictor);
            if (alpha != null) {
                alpha.write(y, alphaPredictor);
                translucent |= translucentRow;
            }
            y++;
        }

        PDImageXObject finish(PDDocument doc) throws IOException {
            if (y != height) {
                throw new IOException("The picture has " + y + " of its " + height + " rows");
            }
            PDColorSpace cs = bands == 1 ? PDDeviceGray.INSTANCE : PDDeviceRGB.INSTANCE;
            PDImageXObject x = color.finish(doc, width, height, cs);
            if (alpha != null && translucent) {
                x.getCOSObject().setItem(COSName.SMASK, alpha.finish(doc, width, height, PDDeviceGray.INSTANCE));
            }
            return x;
        }

        @Override
        public void close() {
            color.end();
            if (alpha != null) {
                alpha.end();
            }
        }
    }

    // One image's rows as 8-bit samples: gray, or RGB with its alpha apart
    private static final class Rows {

        private enum Access {
            GRAY,
            PACKED,
            SAMPLES,
            INDEXED,
            RGB,
            CONVERT
        }

        final BufferedImage image;

        final int bands;

        final boolean alpha;

        private final Access access;

        private final byte[] packed;

        private int[] ints;

        private final byte[] grayRow;

        private final int[] palette;

        private final int shift;

        private BufferedImage strip;

        private ColorConvertOp op;

        private int stripStart = -1;

        Rows(BufferedImage image) {
            this.image = image;
            ColorModel cm = image.getColorModel();
            Raster raster = image.getRaster();
            int type = image.getType();
            int transfer = raster.getTransferType();
            boolean samples = cm instanceof ComponentColorModel && !cm.isAlphaPremultiplied()
                    && (transfer == DataBuffer.TYPE_BYTE || transfer == DataBuffer.TYPE_USHORT)
                    && raster.getNumBands() == cm.getNumComponents()
                    && (cm.getNumColorComponents() == 1 && cm.getColorSpace().getType() == ColorSpace.TYPE_GRAY
                            || cm.getNumColorComponents() == 3 && cm.getColorSpace().isCS_sRGB());
            if (type == BufferedImage.TYPE_BYTE_GRAY) {
                access = Access.GRAY;
            } else if ((type == BufferedImage.TYPE_4BYTE_ABGR || type == BufferedImage.TYPE_3BYTE_BGR)
                    && packed(image)) {
                access = Access.PACKED;
            } else if (samples) {
                access = Access.SAMPLES;
            } else if (cm instanceof IndexColorModel && raster.getNumBands() == 1) {
                access = Access.INDEXED;
            } else if (cm instanceof DirectColorModel || cm.getColorSpace().isCS_sRGB()) {
                access = Access.RGB;
            } else {
                access = Access.CONVERT;
            }
            boolean gray = access == Access.GRAY || access == Access.SAMPLES && cm.getNumColorComponents() == 1;
            this.bands = gray ? 1 : 3;
            this.alpha = access != Access.GRAY && cm.hasAlpha();
            this.packed = access == Access.PACKED ? ((DataBufferByte) raster.getDataBuffer()).getData() : null;
            this.grayRow = access == Access.GRAY ? new byte[image.getWidth()] : null;
            this.shift = transfer == DataBuffer.TYPE_USHORT ? 8 : 0;
            if (access == Access.INDEXED) {
                IndexColorModel icm = (IndexColorModel) cm;
                int[] rgbs = new int[icm.getMapSize()];
                icm.getRGBs(rgbs);
                this.palette = rgbs;
            } else {
                this.palette = null;
            }
        }

        boolean converted() {
            return access == Access.CONVERT;
        }

        // Fills one row and says whether any of its pixels is less than fully opaque
        boolean read(int y, byte[] color, byte[] alphaOut) {
            int w = image.getWidth();
            return switch (access) {
                case GRAY -> {
                    image.getRaster().getDataElements(0, y, w, 1, grayRow);
                    System.arraycopy(grayRow, 0, color, 0, w);
                    yield false;
                }
                case PACKED -> packedRow(y, w, color, alphaOut);
                case SAMPLES -> samplesRow(y, w, color, alphaOut);
                case INDEXED -> indexedRow(y, w, color, alphaOut);
                case RGB -> {
                    image.getRGB(0, y, w, 1, ints(w), 0, w);
                    yield split(w, color, alphaOut);
                }
                case CONVERT -> convertedRow(y, w, color, alphaOut);
            };
        }

        private int[] ints(int n) {
            if (ints == null || ints.length < n) {
                ints = new int[n];
            }
            return ints;
        }

        private boolean packedRow(int y, int w, byte[] color, byte[] alphaOut) {
            boolean abgr = image.getType() == BufferedImage.TYPE_4BYTE_ABGR;
            int n = abgr ? 4 : 3;
            int a = abgr ? 1 : 0;
            boolean translucent = false;
            for (int x = 0, j = y * w * n, k = 0; x < w; x++, j += n, k += 3) {
                color[k] = packed[j + 2 + a];
                color[k + 1] = packed[j + 1 + a];
                color[k + 2] = packed[j + a];
                if (abgr) {
                    alphaOut[x] = packed[j];
                    translucent |= packed[j] != (byte) 0xFF;
                }
            }
            return translucent;
        }

        // Gray and sRGB samples as stored, 16-bit ones cut to their high byte; no color conversion per pixel
        private boolean samplesRow(int y, int w, byte[] color, byte[] alphaOut) {
            int n = image.getRaster().getNumBands();
            int[] s = image.getRaster().getPixels(0, y, w, 1, ints(w * n));
            boolean translucent = false;
            for (int x = 0, j = 0, k = 0; x < w; x++, j += n) {
                for (int b = 0; b < bands; b++) {
                    color[k++] = (byte) (s[j + b] >> shift);
                }
                if (alphaOut != null) {
                    byte v = (byte) (s[j + bands] >> shift);
                    alphaOut[x] = v;
                    translucent |= v != (byte) 0xFF;
                }
            }
            return translucent;
        }

        private boolean indexedRow(int y, int w, byte[] color, byte[] alphaOut) {
            int[] s = image.getRaster().getSamples(0, y, w, 1, 0, ints(w));
            for (int x = 0; x < w; x++) {
                int i = s[x];
                s[x] = i >= 0 && i < palette.length ? palette[i] : 0xFF000000;
            }
            return split(w, color, alphaOut);
        }

        // Other color spaces (ICC profiles, CMYK) are converted to sRGB a strip at a time, never pixel by pixel
        private boolean convertedRow(int y, int w, byte[] color, byte[] alphaOut) {
            int h = image.getHeight();
            if (stripStart < 0 || y < stripStart || y >= stripStart + strip.getHeight()) {
                int start = y - y % STRIP_ROWS;
                int n = Math.min(STRIP_ROWS, h - start);
                if (strip == null || strip.getHeight() != n) {
                    strip = new BufferedImage(w, n, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
                }
                BufferedImage source = image.getSubimage(0, start, w, n);
                try {
                    if (op == null) {
                        op = new ColorConvertOp(null);
                    }
                    op.filter(source, strip);
                } catch (RuntimeException e) {
                    for (int r = 0; r < n; r++) {
                        source.getRGB(0, r, w, 1, ints(w), 0, w);
                        strip.setRGB(0, r, w, 1, ints, 0, w);
                    }
                }
                stripStart = start;
            }
            strip.getRGB(0, y - stripStart, w, 1, ints(w), 0, w);
            return split(w, color, alphaOut);
        }

        private boolean split(int w, byte[] color, byte[] alphaOut) {
            boolean translucent = false;
            for (int x = 0, k = 0; x < w; x++, k += 3) {
                int p = ints[x];
                color[k] = (byte) (p >> 16);
                color[k + 1] = (byte) (p >> 8);
                color[k + 2] = (byte) p;
                if (alphaOut != null) {
                    alphaOut[x] = (byte) (p >>> 24);
                    translucent |= p >>> 24 != 0xFF;
                }
            }
            return translucent;
        }

        private static boolean packed(BufferedImage image) {
            return image.getRaster().getSampleModel() instanceof PixelInterleavedSampleModel m
                    && m.getScanlineStride() == image.getWidth() * m.getPixelStride()
                    && image.getRaster().getSampleModelTranslateX() == 0
                    && image.getRaster().getSampleModelTranslateY() == 0
                    && image.getRaster().getDataBuffer().getNumBanks() == 1
                    && ((DataBufferByte) image.getRaster().getDataBuffer()).getData().length
                            == image.getWidth() * image.getHeight() * m.getPixelStride();
        }
    }

    // One Flate stream: each row is filtered against the row above and deflated as it comes, straight into the
    // document's stream storage when a document is given, else into memory
    private static final class Channel {

        byte[] current;

        private byte[] above;

        private final byte[] line;

        private final int bands;

        private final Deflater deflater = new Deflater(LEVEL);

        private final byte[] buffer = new byte[1 << 16];

        private final COSStream stream;

        private final OutputStream out;

        private boolean closed;

        Channel(PDDocument doc, int stride, int bands) throws IOException {
            this.current = new byte[stride];
            this.above = new byte[stride];
            this.line = new byte[stride + 1];
            this.bands = bands;
            this.stream = doc == null ? null : doc.getDocument().createCOSStream();
            this.out = stream == null ? new ByteArrayOutputStream(1 << 12) : stream.createRawOutputStream();
        }

        void write(int y, int predictor) throws IOException {
            if (predictor == ADAPTIVE) {
                predictor = y > 0 && upIsSmaller() ? UP : NONE;
            }
            line[0] = (byte) predictor;
            if (predictor == NONE || y == 0) {
                System.arraycopy(current, 0, line, 1, current.length);
            } else {
                for (int i = 0; i < current.length; i++) {
                    line[i + 1] = (byte) (current[i] - above[i]);
                }
            }
            deflater.setInput(line);
            while (!deflater.needsInput()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            byte[] swap = above;
            above = current;
            current = swap;
        }

        // The smaller sum of absolute differences, as PNG encoders choose a row's filter
        private boolean upIsSmaller() {
            long none = 0;
            long up = 0;
            for (int i = 0; i < current.length; i++) {
                none += Math.abs((int) current[i]);
                up += Math.abs((byte) (current[i] - above[i]));
            }
            return up < none;
        }

        PDImageXObject finish(PDDocument doc, int w, int h, PDColorSpace cs) throws IOException {
            deflater.finish();
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            closed = true;
            out.close();
            PDImageXObject x;
            if (stream == null) {
                x = new PDImageXObject(doc, new ByteArrayInputStream(((ByteArrayOutputStream) out).toByteArray()),
                        COSName.FLATE_DECODE, w, h, 8, cs);
            } else {
                stream.setItem(COSName.FILTER, COSName.FLATE_DECODE);
                x = new PDImageXObject(new PDStream(stream), null);
                x.setWidth(w);
                x.setHeight(h);
                x.setBitsPerComponent(8);
                x.setColorSpace(cs);
            }
            COSDictionary parms = new COSDictionary();
            parms.setInt(COSName.PREDICTOR, 15);
            parms.setInt(COSName.COLORS, bands);
            parms.setInt(COSName.BITS_PER_COMPONENT, 8);
            parms.setInt(COSName.COLUMNS, w);
            x.getCOSObject().setItem(COSName.DECODE_PARMS, parms);
            return x;
        }

        void end() {
            deflater.end();
            if (!closed) {
                closed = true;
                try {
                    out.close();
                } catch (IOException ignored) {
                    // the unfinished stream is never referenced, so it is never written
                }
            }
        }
    }
}
