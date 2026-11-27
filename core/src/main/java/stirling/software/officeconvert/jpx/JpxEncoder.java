package stirling.software.officeconvert.jpx;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

public final class JpxEncoder {

    public enum Order {
        LRCP, RLCP, RPCL, PCRL, CPRL
    }

    public record Colour(int enumerated, byte[] icc) {

        public static final Colour SRGB = new Colour(16, null);

        public static final Colour GREY = new Colour(17, null);

        public static final Colour SYCC = new Colour(18, null);

        public static final Colour CMYK = new Colour(12, null);

        public Colour {
            if (icc != null) {
                if (icc.length < 128) {
                    throw new IllegalArgumentException("An ICC profile has at least 128 bytes, was " + icc.length);
                }
                icc = icc.clone();
                enumerated = 0;
            } else if (enumerated <= 0) {
                throw new IllegalArgumentException("A colour needs an enumerated space or an ICC profile");
            }
        }

        public static Colour icc(byte[] profile) {
            return new Colour(0, Objects.requireNonNull(profile, "profile"));
        }

        @Override
        public byte[] icc() {
            return icc == null ? null : icc.clone();
        }
    }

    public record Planes(int width, int height, int[][] samples, int[] depths, boolean[] signed, Colour colour,
            int alpha, int[] dx, int[] dy) {

        public Planes(int width, int height, int[][] samples, int[] depths, boolean[] signed, Colour colour,
                int alpha) {
            this(width, height, samples, depths, signed, colour, alpha, null, null);
        }

        public Planes {
            Objects.requireNonNull(samples, "samples");
            Objects.requireNonNull(depths, "depths");
            Objects.requireNonNull(colour, "colour");
            int n = samples.length;
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException("The image must be at least 1 by 1, was " + width + " by "
                        + height);
            }
            if (n < 1 || n > 16384 || depths.length != n || signed != null && signed.length != n) {
                throw new IllegalArgumentException("Every component needs samples and a depth");
            }
            if (dx != null && dx.length != n || dy != null && dy.length != n) {
                throw new IllegalArgumentException("Every component needs a subsampling factor");
            }
            dx = dx == null ? ones(n) : dx.clone();
            dy = dy == null ? ones(n) : dy.clone();
            for (int c = 0; c < n; c++) {
                if (dx[c] < 1 || dx[c] > 255 || dy[c] < 1 || dy[c] > 255) {
                    throw new IllegalArgumentException("Subsampling factors run from 1 to 255, was " + dx[c] + " by "
                            + dy[c]);
                }
                long pixels = (long) Math.ceilDiv(width, dx[c]) * Math.ceilDiv(height, dy[c]);
                if (samples[c] == null || samples[c].length < pixels) {
                    throw new IllegalArgumentException("Component " + c + " has fewer than " + pixels + " samples");
                }
                if (depths[c] < 1 || depths[c] > 16) {
                    throw new IllegalArgumentException("Component depths run from 1 to 16 bits, was " + depths[c]);
                }
                boolean sign = signed != null && signed[c];
                int lo = sign ? -(1 << (depths[c] - 1)) : 0;
                int hi = sign ? (1 << (depths[c] - 1)) - 1 : (1 << depths[c]) - 1;
                int[] plane = samples[c];
                for (int i = 0; i < pixels; i++) {
                    if (plane[i] < lo || plane[i] > hi) {
                        throw new IllegalArgumentException("Component " + c + " has a sample of " + plane[i]
                                + ", outside " + lo + " to " + hi);
                    }
                }
            }
            if (alpha < -1 || alpha >= n) {
                throw new IllegalArgumentException("The alpha component must be a component or -1, was " + alpha);
            }
            samples = samples.clone();
            depths = depths.clone();
            signed = signed == null ? new boolean[n] : signed.clone();
        }

        @Override
        public int[][] samples() {
            return samples.clone();
        }

        @Override
        public int[] depths() {
            return depths.clone();
        }

        @Override
        public boolean[] signed() {
            return signed.clone();
        }

        @Override
        public int[] dx() {
            return dx.clone();
        }

        @Override
        public int[] dy() {
            return dy.clone();
        }

        public int width(int c) {
            return Math.ceilDiv(width, dx[c]);
        }

        public int height(int c) {
            return Math.ceilDiv(height, dy[c]);
        }

        private static int[] ones(int n) {
            int[] a = new int[n];
            Arrays.fill(a, 1);
            return a;
        }

        private boolean sameShape(Planes other) {
            return other.samples.length == samples.length && other.width == width && other.height == height
                    && Arrays.equals(other.dx, dx) && Arrays.equals(other.dy, dy);
        }

        private boolean uniform(int comps) {
            for (int c = 1; c < comps; c++) {
                if (dx[c] != dx[0] || dy[c] != dy[0]) {
                    return false;
                }
            }
            return true;
        }

        public double psnr(Planes other) {
            if (!sameShape(other)) {
                throw new IllegalArgumentException("Planes of different shapes have no PSNR");
            }
            double se = 0;
            long total = 0;
            for (int c = 0; c < samples.length; c++) {
                int n = width(c) * height(c);
                int[] a = samples[c];
                int[] b = other.samples[c];
                for (int i = 0; i < n; i++) {
                    double d = a[i] - b[i];
                    se += d * d;
                }
                total += n;
            }
            if (se == 0) {
                return Double.POSITIVE_INFINITY;
            }
            double peak = (1 << depths[0]) - 1;
            return 10 * Math.log10(peak * peak * total / se);
        }

        public double ssim(Planes other) {
            if (!sameShape(other)) {
                throw new IllegalArgumentException("Planes of different shapes have no SSIM");
            }
            int luma = samples.length >= 3 ? 3 : 1;
            if (uniform(luma)) {
                return Similarity.ssim(samples, other.samples, width(0), height(0), depths[0]);
            }
            double sum = 0;
            for (int c = 0; c < luma; c++) {
                sum += Similarity.ssim(new int[][] {samples[c]}, new int[][] {other.samples[c]}, width(c), height(c),
                        depths[0]);
            }
            return sum / luma;
        }

        int sample(int c, int i) {
            return samples[c][i];
        }

        int depth(int c) {
            return depths[c];
        }

        boolean isSigned(int c) {
            return signed[c];
        }

        int count() {
            return samples.length;
        }
    }

    public record Options(boolean lossless, double bitsPerPixel, double psnr, int levels, int layers, int tileSize,
            int blockSize, Order order, boolean codestream, int threads) {

        public Options {
            Objects.requireNonNull(order, "order");
            if (!(bitsPerPixel >= 0) || Double.isInfinite(bitsPerPixel)) {
                throw new IllegalArgumentException("The rate must be zero (none) or more bits per pixel, was "
                        + bitsPerPixel);
            }
            if (!(psnr >= 0) || Double.isInfinite(psnr)) {
                throw new IllegalArgumentException("The PSNR must be zero (none) or more decibels, was " + psnr);
            }
            if (levels < 0 || levels > 12) {
                throw new IllegalArgumentException("Decomposition levels run from 0 to 12, was " + levels);
            }
            if (layers < 1 || layers > 64) {
                throw new IllegalArgumentException("Quality layers run from 1 to 64, was " + layers);
            }
            if (tileSize < 0 || tileSize > 0 && tileSize < 16) {
                throw new IllegalArgumentException("Tiles are zero (automatic) or at least 16 pixels, was "
                        + tileSize);
            }
            if (blockSize < 4 || blockSize > 64 || Integer.bitCount(blockSize) != 1) {
                throw new IllegalArgumentException("Code-blocks are a power of two from 4 to 64, was " + blockSize);
            }
            if (threads < 0) {
                throw new IllegalArgumentException("Threads are zero (automatic) or more, was " + threads);
            }
        }

        public static Options exact() {
            return new Options(true, 0, 0, 5, 1, 0, 64, Order.LRCP, false, 0);
        }

        public static Options rate(double bitsPerPixel) {
            return new Options(false, bitsPerPixel, 0, 5, 1, 0, 64, Order.LRCP, false, 0);
        }

        public static Options quality(double psnr) {
            return new Options(false, 0, psnr, 5, 1, 0, 64, Order.LRCP, false, 0);
        }

        public Options withRate(double bpp) {
            return new Options(lossless, bpp, psnr, levels, layers, tileSize, blockSize, order, codestream, threads);
        }

        public Options withQuality(double decibels) {
            return new Options(lossless, bitsPerPixel, decibels, levels, layers, tileSize, blockSize, order,
                    codestream, threads);
        }

        public Options withLevels(int n) {
            return new Options(lossless, bitsPerPixel, psnr, n, layers, tileSize, blockSize, order, codestream,
                    threads);
        }

        public Options withLayers(int n) {
            return new Options(lossless, bitsPerPixel, psnr, levels, n, tileSize, blockSize, order, codestream,
                    threads);
        }

        public Options withTileSize(int size) {
            return new Options(lossless, bitsPerPixel, psnr, levels, layers, size, blockSize, order, codestream,
                    threads);
        }

        public Options withBlockSize(int size) {
            return new Options(lossless, bitsPerPixel, psnr, levels, layers, tileSize, size, order, codestream,
                    threads);
        }

        public Options withOrder(Order progression) {
            return new Options(lossless, bitsPerPixel, psnr, levels, layers, tileSize, blockSize, progression,
                    codestream, threads);
        }

        public Options withCodestream(boolean bare) {
            return new Options(lossless, bitsPerPixel, psnr, levels, layers, tileSize, blockSize, order, bare, threads);
        }

        public Options withThreads(int n) {
            return new Options(lossless, bitsPerPixel, psnr, levels, layers, tileSize, blockSize, order, codestream,
                    n);
        }
    }

    private static final double EXACT_PSNR = 60;

    private static final double FIRST_MARGIN = 0.05;

    private static final int ATTEMPTS = 8;

    private static final double MIN_STEP = 0.25;

    private static final double SSIM_TO_DB = 40;

    private JpxEncoder() {}

    public static byte[] encode(BufferedImage image, Options options) throws IOException {
        return encode(planes(image), options);
    }

    public static byte[] encode(Planes planes, Options options) throws IOException {
        Objects.requireNonNull(planes, "planes");
        Objects.requireNonNull(options, "options");
        return new EncodeJob(planes, options).run();
    }

    public static byte[] encodeAsClose(Planes source, Planes reference, long maxBytes) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(reference, "reference");
        double psnr = source.psnr(reference);
        if (psnr > EXACT_PSNR) {
            byte[] data = encode(source, Options.exact());
            return data.length < maxBytes ? data : null;
        }
        double ssim = source.ssim(reference);
        EncodeJob job = new EncodeJob(source, Options.quality(psnr));
        job.prepare();
        double target = psnr + FIRST_MARGIN;
        for (int i = 0; i < ATTEMPTS; i++) {
            byte[] data = job.emit(target, 0);
            if (data.length >= maxBytes) {
                return null;
            }
            Planes back = decoded(data, source);
            double p = source.psnr(back);
            double s = source.ssim(back);
            if (p >= psnr && s >= ssim) {
                return data;
            }
            target += Math.max(MIN_STEP, Math.max(psnr - p, 0) + Math.max(ssim - s, 0) * SSIM_TO_DB);
        }
        return null;
    }

    private static Planes decoded(byte[] data, Planes like) throws IOException {
        JpxRaster r = JpxDecoder.decode(data).raster();
        int n = r.components();
        int w = r.width();
        int h = r.height();
        int[][] s = new int[n][w * h];
        for (int c = 0; c < n; c++) {
            boolean signed = r.signed(c);
            int shift = signed ? 1 << (r.depth(c) - 1) : 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    s[c][y * w + x] = r.imageSample(c, x, y) - shift;
                }
            }
        }
        return new Planes(w, h, s, like.depths, like.signed, like.colour, like.alpha);
    }

    public static Planes planes(BufferedImage image) {
        BufferedImage img = image;
        ColorModel cm = img.getColorModel();
        if (cm instanceof IndexColorModel) {
            BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(),
                    cm.hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            rgb.createGraphics().drawImage(img, 0, 0, null);
            img = rgb;
            cm = img.getColorModel();
        }
        if (wide(img)) {
            BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(),
                    cm.hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            rgb.createGraphics().drawImage(img, 0, 0, null);
            img = rgb;
            cm = img.getColorModel();
        }
                if (cm.isAlphaPremultiplied()) {
            img = copy(img);
            img.coerceData(false);
            cm = img.getColorModel();
        }
        Raster raster = img.getRaster();
        int w = img.getWidth();
        int h = img.getHeight();
        int bands = raster.getNumBands();
        int[][] samples = new int[bands][w * h];
        int[] depths = new int[bands];
        int[] row = new int[w];
        for (int b = 0; b < bands; b++) {
            depths[b] = raster.getSampleModel().getSampleSize(b);
            for (int y = 0; y < h; y++) {
                raster.getSamples(raster.getMinX(), raster.getMinY() + y, w, 1, b, row);
                System.arraycopy(row, 0, samples[b], y * w, w);
            }
        }
        ColorSpace cs = cm.getColorSpace();
        Colour colour;
        if (cs.isCS_sRGB()) {
            colour = Colour.SRGB;
        } else if (cs.getType() == ColorSpace.TYPE_GRAY) {
            colour = Colour.GREY;
        } else if (cs instanceof ICC_ColorSpace icc) {
            colour = Colour.icc(icc.getProfile().getData());
        } else if (cs.getType() == ColorSpace.TYPE_CMYK) {
            colour = Colour.CMYK;
        } else {
            colour = cm.getNumColorComponents() == 1 ? Colour.GREY : Colour.SRGB;
        }
        int alpha = cm.hasAlpha() ? bands - 1 : -1;
        return new Planes(w, h, samples, depths, null, colour, alpha);
    }

    private static boolean wide(BufferedImage img) {
        Raster r = img.getRaster();
        int type = r.getDataBuffer().getDataType();
        if (type == DataBuffer.TYPE_FLOAT || type == DataBuffer.TYPE_DOUBLE) {
            return true;
        }
        for (int size : r.getSampleModel().getSampleSize()) {
            if (size > 16) {
                return true;
            }
        }
        return false;
    }

    private static BufferedImage copy(BufferedImage img) {
        ColorModel cm = img.getColorModel();
        return new BufferedImage(cm, img.copyData(null), cm.isAlphaPremultiplied(), null);
    }

    static boolean restrictedIcc(byte[] icc) {
        if (icc.length < 20) {
            return false;
        }
        int space = (icc[16] & 0xFF) << 24 | (icc[17] & 0xFF) << 16 | (icc[18] & 0xFF) << 8 | icc[19] & 0xFF;
        if (space != ICC_Profile.icSigGrayData && space != ICC_Profile.icSigRgbData) {
            return false;
        }
        try {
            ICC_Profile p = ICC_Profile.getInstance(icc);
            if (p.getData(ICC_Profile.icSigAToB0Tag) != null) {
                return false;
            }
            if (space == ICC_Profile.icSigGrayData) {
                return p.getData(ICC_Profile.icSigGrayTRCTag) != null;
            }
            return p.getData(ICC_Profile.icSigRedColorantTag) != null && p.getData(ICC_Profile.icSigRedTRCTag) != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
