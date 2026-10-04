package stirling.software.officeconvert.topdf.io;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.color.PDIndexed;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

// 8-bit gray, gray+alpha, RGB and RGBA PNGs go from their rows straight into Flate rows, one row in memory at a time;
// a palette PNG with transparency keeps its own compressed indices and only its mask is decoded
final class PngStream {

    static final int MAX_WIDTH = 1 << 16;

    private PngStream() {}

    // Null for a PNG this does not stream (interlaced, 16-bit, a colour key, an opaque palette); decode it instead
    static PDImageXObject encode(PDDocument doc, byte[] png) throws IOException {
        if (png.length < 33 || !chunk(png, 12, "IHDR") || be32(png, 8) != 13) {
            return null;
        }
        long width = be32(png, 16) & 0xFFFFFFFFL;
        long height = be32(png, 20) & 0xFFFFFFFFL;
        int depth = png[24] & 0xFF;
        int type = png[25] & 0xFF;
        int channels = switch (type) {
            case 0, 3 -> 1;
            case 2 -> 3;
            case 4 -> 2;
            case 6 -> 4;
            default -> 0;
        };
        boolean palette = type == 3;
        boolean depthOk = palette ? depth == 1 || depth == 2 || depth == 4 || depth == 8 : depth == 8;
        if (channels == 0 || !depthOk || png[26] != 0 || png[27] != 0 || png[28] != 0 || width < 1 || height < 1
                || width > MAX_WIDTH || width * height > PictureDecoder.DECODE_PIXELS) {
            return null;
        }
        byte[][] tables = tables(png);
        byte[] plte = tables[0];
        byte[] trns = tables[1];
        int w = (int) width;
        int h = (int) height;
        if (palette) {
            boolean usable = plte != null && plte.length > 0 && plte.length % 3 == 0 && plte.length <= 768;
            return usable && trns != null ? palette(doc, png, w, h, depth, plte, trns) : null;
        }
        if (trns != null) {
            return null;
        }
        try (FlateImages.Writer out = new FlateImages.Writer(doc, w, h, channels < 3 ? 1 : 3, type == 4 || type == 6);
                Rows rows = new Rows(png, w * channels, channels)) {
            for (int y = 0; y < h; y++) {
                boolean translucent = split(rows.next(y), channels, w, out.color(), out.alpha());
                out.row(FlateImages.ADAPTIVE, FlateImages.NONE, translucent);
            }
            return out.finish(doc);
        }
    }

    // The indices go into the PDF as the PNG stores them, a Flate stream with PNG predictors, as PDFBox would copy them
    private static PDImageXObject palette(PDDocument doc, byte[] png, int w, int h, int depth, byte[] plte, byte[] trns)
            throws IOException {
        PDImageXObject mask = mask(doc, png, w, h, depth, opacity(trns));
        COSStream stream = doc.getDocument().createCOSStream();
        try (OutputStream out = stream.createRawOutputStream()) {
            long at = 8;
            for (int n = 0; at + 8 <= png.length && n < 100_000; n++) {
                int i = (int) at;
                long length = be32(png, i) & 0xFFFFFFFFL;
                if (chunk(png, i + 4, "IEND")) {
                    break;
                }
                if (chunk(png, i + 4, "IDAT")) {
                    out.write(png, i + 8, (int) Math.max(0, Math.min(length, png.length - (long) (i + 8))));
                }
                at += 12 + length;
            }
        }
        stream.setItem(COSName.FILTER, COSName.FLATE_DECODE);
        PDImageXObject x = new PDImageXObject(new PDStream(stream), null);
        x.setWidth(w);
        x.setHeight(h);
        x.setBitsPerComponent(depth);
        COSArray indexed = new COSArray();
        indexed.add(COSName.INDEXED);
        indexed.add(COSName.DEVICERGB);
        indexed.add(COSInteger.get(plte.length / 3 - 1));
        indexed.add(new COSString(plte));
        x.setColorSpace(new PDIndexed(indexed));
        COSDictionary parms = new COSDictionary();
        parms.setInt(COSName.PREDICTOR, 15);
        parms.setInt(COSName.COLORS, 1);
        parms.setInt(COSName.BITS_PER_COMPONENT, depth);
        parms.setInt(COSName.COLUMNS, w);
        x.getCOSObject().setItem(COSName.DECODE_PARMS, parms);
        if (mask != null) {
            x.getCOSObject().setItem(COSName.SMASK, mask);
        }
        return x;
    }

    // Each pixel's alpha from the transparency table, or null when every pixel is opaque
    private static PDImageXObject mask(PDDocument doc, byte[] png, int w, int h, int depth, byte[] opacity)
            throws IOException {
        int bits = (1 << depth) - 1;
        boolean translucent = false;
        try (FlateImages.Writer out = new FlateImages.Writer(doc, w, h, 1, false);
                Rows rows = new Rows(png, (w * depth + 7) / 8, 1)) {
            for (int y = 0; y < h; y++) {
                byte[] row = rows.next(y);
                byte[] alpha = out.color();
                for (int x = 0; x < w; x++) {
                    int bit = x * depth;
                    byte a = opacity[(row[bit >> 3] & 0xFF) >> (8 - depth - (bit & 7)) & bits];
                    alpha[x] = a;
                    translucent |= a != (byte) 0xFF;
                }
                out.row(FlateImages.NONE, FlateImages.NONE, false);
            }
            return translucent ? out.finish(doc) : null;
        }
    }

    private static boolean split(byte[] row, int channels, int w, byte[] color, byte[] alpha) {
        switch (channels) {
            case 1, 3 -> {
                System.arraycopy(row, 0, color, 0, row.length);
                return false;
            }
            case 2 -> {
                boolean translucent = false;
                for (int x = 0, j = 0; x < w; x++, j += 2) {
                    color[x] = row[j];
                    alpha[x] = row[j + 1];
                    translucent |= row[j + 1] != (byte) 0xFF;
                }
                return translucent;
            }
            default -> {
                boolean translucent = false;
                for (int x = 0, j = 0, k = 0; x < w; x++, j += 4, k += 3) {
                    color[k] = row[j];
                    color[k + 1] = row[j + 1];
                    color[k + 2] = row[j + 2];
                    alpha[x] = row[j + 3];
                    translucent |= row[j + 3] != (byte) 0xFF;
                }
                return translucent;
            }
        }
    }

    // Entries past the end of the transparency table are opaque
    private static byte[] opacity(byte[] trns) {
        byte[] all = new byte[256];
        Arrays.fill(all, (byte) 0xFF);
        System.arraycopy(trns, 0, all, 0, Math.min(trns.length, 256));
        return all;
    }

    // PLTE and tRNS, which come before the first IDAT
    private static byte[][] tables(byte[] png) {
        byte[][] found = new byte[2][];
        long at = 8;
        for (int n = 0; at + 8 <= png.length && n < 100_000; n++) {
            int i = (int) at;
            long length = be32(png, i) & 0xFFFFFFFFL;
            if (chunk(png, i + 4, "IDAT") || chunk(png, i + 4, "IEND")) {
                break;
            }
            int k = chunk(png, i + 4, "PLTE") ? 0 : chunk(png, i + 4, "tRNS") ? 1 : -1;
            if (k >= 0 && i + 8 + length <= png.length) {
                found[k] = Arrays.copyOfRange(png, i + 8, i + 8 + (int) length);
            }
            at += 12 + length;
        }
        return found;
    }

    // The image rows, inflated from the IDAT chunks and unfiltered one at a time
    private static final class Rows implements AutoCloseable {

        private final byte[] png;

        private final int bpp;

        private final Inflater inflater = new Inflater();

        private final byte[] line;

        private byte[] above;

        private byte[] row;

        private long at = 8;

        Rows(byte[] png, int stride, int bpp) {
            this.png = png;
            this.bpp = bpp;
            this.line = new byte[stride + 1];
            this.above = new byte[stride];
            this.row = new byte[stride];
        }

        byte[] next(int y) throws IOException {
            if ((y & 127) == 0 && Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Conversion interrupted");
            }
            fill();
            byte[] swap = above;
            above = row;
            row = swap;
            unfilter(line[0]);
            return row;
        }

        private void unfilter(int filter) throws IOException {
            int n = row.length;
            switch (filter) {
                case 0 -> System.arraycopy(line, 1, row, 0, n);
                case 1 -> {
                    for (int i = 0; i < n; i++) {
                        row[i] = (byte) (line[i + 1] + (i >= bpp ? row[i - bpp] : 0));
                    }
                }
                case 2 -> {
                    for (int i = 0; i < n; i++) {
                        row[i] = (byte) (line[i + 1] + above[i]);
                    }
                }
                case 3 -> {
                    for (int i = 0; i < n; i++) {
                        int a = i >= bpp ? row[i - bpp] & 0xFF : 0;
                        row[i] = (byte) (line[i + 1] + ((a + (above[i] & 0xFF)) >> 1));
                    }
                }
                case 4 -> {
                    for (int i = 0; i < n; i++) {
                        int a = i >= bpp ? row[i - bpp] & 0xFF : 0;
                        int b = above[i] & 0xFF;
                        int c = i >= bpp ? above[i - bpp] & 0xFF : 0;
                        int p = a + b - c;
                        int pa = Math.abs(p - a);
                        int pb = Math.abs(p - b);
                        int pc = Math.abs(p - c);
                        row[i] = (byte) (line[i + 1] + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c));
                    }
                }
                default -> throw new IOException("The PNG picture has a row with an unknown filter " + filter);
            }
        }

        private void fill() throws IOException {
            int filled = 0;
            while (filled < line.length) {
                int n;
                try {
                    n = inflater.inflate(line, filled, line.length - filled);
                } catch (DataFormatException e) {
                    throw new IOException("The PNG picture's data is damaged: " + e.getMessage(), e);
                }
                filled += n;
                if (n > 0) {
                    continue;
                }
                if (inflater.finished() || inflater.needsDictionary() || !nextIdat()) {
                    throw new IOException("The PNG picture's data ends early");
                }
            }
        }

        private boolean nextIdat() {
            while (at + 8 <= png.length) {
                int i = (int) at;
                long length = be32(png, i) & 0xFFFFFFFFL;
                at += 12 + length;
                if (chunk(png, i + 4, "IEND")) {
                    return false;
                }
                if (chunk(png, i + 4, "IDAT")) {
                    int len = (int) Math.min(length, png.length - (long) (i + 8));
                    if (len > 0) {
                        inflater.setInput(png, i + 8, len);
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public void close() {
            inflater.end();
        }
    }

    private static boolean chunk(byte[] d, int at, String type) {
        return at >= 0 && at + 4 <= d.length && d[at] == type.charAt(0) && d[at + 1] == type.charAt(1)
                && d[at + 2] == type.charAt(2) && d[at + 3] == type.charAt(3);
    }

    private static int be32(byte[] d, int i) {
        return (d[i] & 0xFF) << 24 | (d[i + 1] & 0xFF) << 16 | (d[i + 2] & 0xFF) << 8 | (d[i + 3] & 0xFF);
    }
}
