package stirling.software.officeconvert.extract;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.Filter;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.pdmodel.PDDocument;

public final class StreamGuard {

    private static final Log LOG = LogFactory.getLog(StreamGuard.class);

    public static final long MAX_DECODED = 256L << 20;

    private static final Map<COSStream, Boolean> FITS = Collections.synchronizedMap(new WeakHashMap<>());

    private StreamGuard() {}

    public static int check(PDDocument doc) throws InterruptedIOException {
        COSDocument cos = doc.getDocument();
        int emptied = 0;
        for (Map.Entry<COSObjectKey, Long> e : new ArrayList<>(cos.getXrefTable().entrySet())) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Conversion interrupted");
            }
            if (e.getValue() == null || e.getValue() <= 0) {
                continue;
            }
            try {
                if (cos.getObjectFromPool(e.getKey()).getObject() instanceof COSStream s
                        && !COSName.IMAGE.equals(s.getCOSName(COSName.SUBTYPE)) && overflows(s)) {
                    empty(s);
                    emptied++;
                }
            } catch (InterruptedIOException stop) {
                throw stop;
            } catch (IOException | RuntimeException broken) {
            }
        }
        if (emptied > 0) {
            LOG.warn(emptied + " stream(s) inflate past " + (MAX_DECODED >> 20) + " MB and were left out");
        }
        return emptied;
    }

    public static boolean fits(COSStream s) {
        Boolean known = FITS.get(s);
        if (known != null) {
            return known;
        }
        boolean fits;
        try {
            fits = !overflows(s);
        } catch (IOException e) {
            fits = false;
        }
        if (!fits) {
            LOG.warn("A picture inflates past " + (MAX_DECODED >> 20) + " MB and was left out");
        }
        FITS.put(s, fits);
        return fits;
    }

    static boolean inlineFits(Operator operator) throws InterruptedIOException {
        if (!"BI".equals(operator.getName()) || operator.getImageData() == null || operator.getImageParameters() == null) {
            return true;
        }
        COSDictionary params = operator.getImageParameters();
        byte[] data = operator.getImageData();
        List<COSName> filters = filters(params.getDictionaryObject(COSName.F, COSName.FILTER));
        try {
            if (overflows(filters, new ByteArrayInputStream(data), data.length, params)) {
                return false;
            }
            if (filters.size() == 1 && ("DCT".equals(filters.get(0).getName()) || COSName.DCT_DECODE.equals(filters.get(0)))) {
                Jpeg.Frame f = Jpeg.frame(data);
                return f != null && (long) f.width() * f.height() * Math.max(1, f.components()) <= MAX_DECODED;
            }
            return true;
        } catch (InterruptedIOException stop) {
            throw stop;
        } catch (IOException e) {
            return true;
        }
    }

    static boolean overflows(COSStream s) throws IOException {
        List<COSName> filters = filters(s.getFilters());
        if (potential(filters, s.getLength()) <= MAX_DECODED) {
            return false;
        }
        try (InputStream raw = s.createRawInputStream()) {
            return overflows(filters, raw, s.getLength(), s);
        }
    }

    private static double potential(List<COSName> filters, long length) {
        double most = Math.max(0, length);
        int stages = 0;
        while (stages < filters.size() && ratio(filters.get(stages)) > 0) {
            most *= ratio(filters.get(stages++));
        }
        return stages == 0 ? 0 : most;
    }

    private static boolean overflows(List<COSName> filters, InputStream raw, long length, COSDictionary params)
            throws IOException {
        if (potential(filters, length) <= MAX_DECODED) {
            return false;
        }
        int stages = 0;
        while (stages < filters.size() && ratio(filters.get(stages)) > 0) {
            stages++;
        }
        InputStream data = raw;
        try {
            for (int i = 0; i < stages; i++) {
                Filter filter = FilterFactory.INSTANCE.getFilter(filters.get(i));
                boolean last = i == stages - 1;
                Capped out = new Capped(!last);
                filter.decode(data, out, params, i);
                data = last ? InputStream.nullInputStream() : new ByteArrayInputStream(out.bytes());
            }
            return false;
        } catch (Full full) {
            return true;
        } catch (InterruptedIOException stop) {
            throw stop;
        } catch (IOException broken) {
            return false;
        }
    }

    private static double ratio(COSName filter) {
        return switch (filter.getName()) {
            case "FlateDecode", "Fl" -> 1032;
            case "LZWDecode", "LZW" -> 4096;
            case "RunLengthDecode", "RL" -> 128;
            case "ASCIIHexDecode", "AHx", "ASCII85Decode", "A85" -> 1;
            default -> 0;
        };
    }

    private static List<COSName> filters(COSBase f) {
        List<COSName> out = new ArrayList<>();
        if (f instanceof COSName n) {
            out.add(n);
        } else if (f instanceof COSArray a) {
            for (COSBase b : a) {
                if (b instanceof COSName n) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    private static void empty(COSStream s) throws IOException {
        s.removeItem(COSName.FILTER);
        s.removeItem(COSName.DECODE_PARMS);
        s.createRawOutputStream().close();
    }

    private static final class Capped extends OutputStream {

        private final ByteArrayOutputStream kept;
        private long written;

        Capped(boolean keep) {
            this.kept = keep ? new ByteArrayOutputStream() : null;
        }

        @Override
        public void write(int b) throws IOException {
            add(1);
            if (kept != null) {
                kept.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            add(len);
            if (kept != null) {
                kept.write(b, off, len);
            }
        }

        private void add(long n) throws Full {
            written += n;
            if (written > MAX_DECODED) {
                throw new Full();
            }
        }

        byte[] bytes() {
            return kept.toByteArray();
        }
    }

    private static final class Full extends IOException {
        Full() {
            super("Stream inflates past the limit");
        }
    }
}
