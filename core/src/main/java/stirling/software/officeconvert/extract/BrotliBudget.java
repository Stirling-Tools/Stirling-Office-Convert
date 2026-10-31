package stirling.software.officeconvert.extract;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.pdmodel.PDDocument;

public final class BrotliBudget {

    public static final String PROPERTY = "stirling.officeconvert.brotliBudgetMB";

    public static final long DEFAULT_BYTES = StreamGuard.MAX_DECODED * 4;

    private static final Map<COSDocument, BrotliBudget> DOCUMENTS = Collections.synchronizedMap(new WeakHashMap<>());

    private final long total;

    private final AtomicLong left;

    private BrotliBudget(long total) {
        this.total = total;
        this.left = new AtomicLong(total);
    }

    public static BrotliBudget create() {
        return new BrotliBudget(configured());
    }

    public static BrotliBudget of(PDDocument doc) {
        return of(doc.getDocument());
    }

    static BrotliBudget of(COSDocument doc) {
        return DOCUMENTS.computeIfAbsent(doc, d -> create());
    }

    public boolean take(long bytes) {
        return left.addAndGet(-bytes) >= 0;
    }

    public boolean spent() {
        return left.get() < 0;
    }

    public String exhausted() {
        return "the PDF's BrotliDecode streams decode past the " + (total >> 20) + " MB budget for one document";
    }

    private static long configured() {
        String v = System.getProperty(PROPERTY);
        try {
            return v == null ? DEFAULT_BYTES : Math.min(1L << 30, Math.max(1, Long.parseLong(v.strip()))) << 20;
        } catch (NumberFormatException e) {
            return DEFAULT_BYTES;
        }
    }

    public static final class Spent extends RuntimeException {

        public Spent(String message) {
            super(message, null, false, false);
        }
    }
}
