package stirling.software.officeconvert.extract;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSString;

final class ParsedStreams {

    static final int MAX_TOKENS = 500_000;

    static final long MAX_BYTES = 32L * 1024 * 1024;

    private static final int TOKEN_BYTES = 32;

    private record Parsed(Object[] tokens, long bytes, boolean reusable) {}

    private final Map<COSBase, Parsed> parsed = new LinkedHashMap<>(16, 0.75f, true);
    private long heldTokens;
    private long heldBytes;

    void put(COSBase key, Object[] tokens, boolean reusable) {
        long bytes = 0;
        for (Object token : tokens) {
            bytes += weight(token);
        }
        put(key, tokens, bytes, reusable);
    }

    void put(COSBase key, Object[] tokens, long bytes, boolean reusable) {
        Parsed old = parsed.remove(key);
        if (old != null) {
            release(old);
        }
        if (tokens.length > MAX_TOKENS || bytes > MAX_BYTES) {
            return;
        }
        parsed.put(key, new Parsed(tokens, bytes, reusable));
        heldTokens += tokens.length;
        heldBytes += bytes;
        Iterator<Parsed> eldest = parsed.values().iterator();
        while ((heldTokens > MAX_TOKENS || heldBytes > MAX_BYTES) && eldest.hasNext()) {
            release(eldest.next());
            eldest.remove();
        }
    }

    Object[] take(COSBase key) {
        if (key == null) {
            return null;
        }
        Parsed found = parsed.get(key);
        if (found == null) {
            return null;
        }
        if (!found.reusable()) {
            parsed.remove(key);
            release(found);
        }
        return found.tokens();
    }

    long heldBytes() {
        return heldBytes;
    }

    static long weight(Object token) {
        if (token instanceof COSString s) {
            return TOKEN_BYTES + s.getBytes().length;
        }
        if (token instanceof Operator op && op.getImageData() != null) {
            return TOKEN_BYTES + op.getImageData().length;
        }
        if (token instanceof COSArray array) {
            long sum = TOKEN_BYTES;
            for (COSBase item : array) {
                sum += weight(item);
            }
            return sum;
        }
        return TOKEN_BYTES;
    }

    private void release(Parsed old) {
        heldTokens -= old.tokens().length;
        heldBytes -= old.bytes();
    }
}
