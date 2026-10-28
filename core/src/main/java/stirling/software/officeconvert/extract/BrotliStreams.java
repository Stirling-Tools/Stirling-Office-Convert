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
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.FilterFactory;
import org.brotli.dec.BrotliInputStream;

final class BrotliStreams {

    private static final Log LOG = LogFactory.getLog(BrotliStreams.class);

    static final COSName BROTLI_DECODE = COSName.getPDFName("BrotliDecode");

    private static final COSName OBJ_STM = COSName.getPDFName("ObjStm");

    private static final int MAX_STAGES = 8;

    private static final Map<COSDocument, Boolean> DECODED = Collections.synchronizedMap(new WeakHashMap<>());

    private BrotliStreams() {}

    static boolean structural(COSStream s) {
        COSBase type = resolve(s.getItem(COSName.TYPE));
        return OBJ_STM.equals(type) || COSName.XREF.equals(type);
    }

    static boolean uses(COSStream s) {
        COSBase f = resolve(s.getItem(COSName.FILTER));
        if (f instanceof COSArray a) {
            for (COSBase b : a) {
                if (BROTLI_DECODE.equals(resolve(b))) {
                    return true;
                }
            }
            return false;
        }
        return BROTLI_DECODE.equals(f);
    }

    static boolean decoded(COSDocument doc) {
        return DECODED.containsKey(doc);
    }

    static void decode(COSStream s, COSDocument doc) throws IOException {
        if (s.containsKey(COSName.F)) {
            return;
        }
        boolean structural = structural(s);
        BrotliBudget budget = BrotliBudget.of(doc);
        for (int i = 0; i < MAX_STAGES && uses(s); i++) {
            if (!replaceFirst(s, structural, budget)) {
                return;
            }
            DECODED.put(doc, Boolean.TRUE);
        }
    }

    private static boolean replaceFirst(COSStream s, boolean structural, BrotliBudget budget) throws IOException {
        List<COSName> filters = new ArrayList<>();
        List<COSBase> parms = new ArrayList<>();
        if (!chain(s, filters, parms)) {
            return false;
        }
        int at = filters.indexOf(BROTLI_DECODE);
        byte[] before = null;
        if (at > 0) {
            before = stages(s, filters, at);
            if (before == null) {
                return false;
            }
            if (!budget.take(before.length)) {
                return overBudget(s, structural, budget);
            }
        }
        ByteArrayOutputStream flate = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        Outcome outcome;
        try (InputStream raw = before == null ? s.createRawInputStream() : new ByteArrayInputStream(before);
                OutputStream z = new DeflaterOutputStream(flate, deflater, 1 << 16)) {
            outcome = transcode(raw, z, budget);
        } finally {
            deflater.end();
        }
        if (outcome == Outcome.SPENT) {
            return overBudget(s, structural, budget);
        }
        if (outcome == Outcome.TRUNCATED && structural) {
            throw new BrotliBudget.Spent("a BrotliDecode " + s.getCOSName(COSName.TYPE).getName()
                    + " stream decodes past " + (StreamGuard.MAX_DECODED >> 20) + " MB");
        }
        if (outcome == Outcome.DAMAGED) {
            LOG.warn("A BrotliDecode stream is damaged; only the data before the damage is read");
        }
        try (OutputStream out = s.createRawOutputStream()) {
            flate.writeTo(out);
        }
        List<COSName> nextFilters = new ArrayList<>();
        List<COSBase> nextParms = new ArrayList<>();
        nextFilters.add(COSName.FLATE_DECODE);
        nextParms.add(parms.get(at));
        nextFilters.addAll(filters.subList(at + 1, filters.size()));
        nextParms.addAll(parms.subList(at + 1, parms.size()));
        if (nextFilters.size() == 1) {
            s.setItem(COSName.FILTER, COSName.FLATE_DECODE);
        } else {
            COSArray a = new COSArray();
            nextFilters.forEach(a::add);
            s.setItem(COSName.FILTER, a);
        }
        s.removeItem(COSName.DP);
        if (nextParms.stream().allMatch(p -> p == null)) {
            s.removeItem(COSName.DECODE_PARMS);
        } else if (nextParms.size() == 1) {
            s.setItem(COSName.DECODE_PARMS, nextParms.get(0));
        } else {
            COSArray a = new COSArray();
            nextParms.forEach(p -> a.add(p == null ? COSNull.NULL : p));
            s.setItem(COSName.DECODE_PARMS, a);
        }
        return true;
    }

    private enum Outcome {
        COMPLETE, DAMAGED, TRUNCATED, SPENT
    }

    private static boolean overBudget(COSStream s, boolean structural, BrotliBudget budget) throws IOException {
        if (structural) {
            throw new BrotliBudget.Spent(budget.exhausted());
        }
        LOG.warn("A BrotliDecode stream was left out: " + budget.exhausted());
        s.removeItem(COSName.FILTER);
        s.removeItem(COSName.DECODE_PARMS);
        s.removeItem(COSName.DP);
        s.createRawOutputStream().close();
        return false;
    }

    private static Outcome transcode(InputStream raw, OutputStream z, BrotliBudget budget) throws IOException {
        long written = 0;
        try (InputStream brotli = new BrotliInputStream(raw)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = brotli.read(buf, 0, buf.length)) > 0) {
                PdfFiles.stopIfInterrupted();
                int keep = (int) Math.min(n, StreamGuard.MAX_DECODED + 1 - written);
                if (!budget.take(keep)) {
                    return Outcome.SPENT;
                }
                z.write(buf, 0, keep);
                written += keep;
                if (written > StreamGuard.MAX_DECODED) {
                    return Outcome.TRUNCATED;
                }
            }
            return Outcome.COMPLETE;
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            return Outcome.DAMAGED;
        }
    }

    private static byte[] stages(COSStream s, List<COSName> filters, int count) throws IOException {
        byte[] data;
        try (InputStream raw = s.createRawInputStream()) {
            data = raw.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, StreamGuard.MAX_DECODED + 1));
        }
        try {
            for (int i = 0; i < count; i++) {
                Capped out = new Capped();
                FilterFactory.INSTANCE.getFilter(filters.get(i)).decode(new ByteArrayInputStream(data), out, s, i);
                data = out.toByteArray();
            }
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return data.length > StreamGuard.MAX_DECODED ? null : data;
    }

    private static boolean chain(COSStream s, List<COSName> filters, List<COSBase> parms) {
        COSBase f = resolve(s.getItem(COSName.FILTER));
        COSBase p = resolve(s.getItem(COSName.DECODE_PARMS, COSName.DP));
        if (f instanceof COSName n) {
            filters.add(n);
            parms.add(p instanceof COSDictionary ? p : null);
            return true;
        }
        if (!(f instanceof COSArray a)) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!(resolve(a.get(i)) instanceof COSName n)) {
                return false;
            }
            filters.add(n);
            COSBase item = p instanceof COSArray pa && i < pa.size() ? resolve(pa.get(i)) : null;
            parms.add(item instanceof COSDictionary ? item : null);
        }
        return true;
    }

    private static COSBase resolve(COSBase b) {
        return b instanceof COSObject o ? o.getObject() : b;
    }

    private static final class Capped extends ByteArrayOutputStream {

        @Override
        public void write(int b) {
            room(1);
            super.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            room(len);
            super.write(b, off, len);
        }

        private void room(int more) {
            if (size() + (long) more > StreamGuard.MAX_DECODED) {
                throw new IllegalStateException("past the decoded size limit");
            }
        }
    }
}
