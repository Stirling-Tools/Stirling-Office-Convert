package stirling.software.officeconvert.optimize;

import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.zip.Deflater;

final class Deflate {

    static final Squeeze.Settings DEFAULT_SQUEEZE = new Squeeze.Settings(2, 16, 258, 15);

    static final Squeeze.Settings MAX_SQUEEZE = new Squeeze.Settings(15, 1024, 258, 15);

    private Deflate() {}

    static byte[] quick(byte[] data) {
        return zlib(data, repetitive(data) ? 6 : 9, Deflater.DEFAULT_STRATEGY);
    }

    static byte[] baseline(byte[] data, PdfOptimizer.Effort effort) {
        return effort == PdfOptimizer.Effort.FAST ? quick(data) : zlib(data, 6, Deflater.DEFAULT_STRATEGY);
    }

    static boolean repetitive(byte[] data) {
        if (data.length < 1 << 16) {
            return false;
        }
        int sample = Math.min(data.length, 1 << 16);
        Deflater d = new Deflater(1);
        try {
            d.setInput(data, 0, sample);
            d.finish();
            byte[] buf = new byte[sample];
            int n = 0;
            while (!d.finished() && n < buf.length) {
                n += d.deflate(buf, n, buf.length - n);
            }
            return d.finished() && n * 16 < sample;
        } finally {
            d.end();
        }
    }

    static byte[] best(byte[] data, PdfOptimizer.Effort effort) throws InterruptedIOException {
        return best(data, baseline(data, effort), effort);
    }

    static byte[] best(byte[] data, byte[] quick, PdfOptimizer.Effort effort) throws InterruptedIOException {
        byte[] best = quick;
        if (effort == PdfOptimizer.Effort.FAST || data.length < 32) {
            return best;
        }
        if (effort == PdfOptimizer.Effort.MAX) {
            best = smaller(best, zlib(data, 9, Deflater.FILTERED));
        }
        byte[] squeezed = Squeeze.zlib(data, effort == PdfOptimizer.Effort.MAX ? MAX_SQUEEZE : DEFAULT_SQUEEZE);
        if (squeezed.length < best.length && inflatesTo(squeezed, data)) {
            best = squeezed;
        }
        return best;
    }

    static byte[] smaller(byte[] a, byte[] b) {
        return b != null && b.length < a.length ? b : a;
    }

    private static boolean inflatesTo(byte[] z, byte[] data) {
        try {
            return Arrays.equals(Filters.inflate(z, data.length), data);
        } catch (Filters.Undecodable e) {
            return false;
        }
    }

    static byte[] zlib(byte[] data, int level, int strategy) {
        Deflater d = new Deflater(level);
        try {
            d.setStrategy(strategy);
            d.setInput(data);
            d.finish();
            byte[] out = new byte[Math.max(64, data.length / 3)];
            int size = 0;
            while (!d.finished()) {
                if (size == out.length) {
                    out = Arrays.copyOf(out, (int) Math.min(Integer.MAX_VALUE - 16, out.length * 2L));
                }
                size += d.deflate(out, size, out.length - size);
            }
            return size == out.length ? out : Arrays.copyOf(out, size);
        } finally {
            d.end();
        }
    }
}
