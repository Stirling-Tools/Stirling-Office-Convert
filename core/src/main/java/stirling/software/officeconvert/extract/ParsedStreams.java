package stirling.software.officeconvert.extract;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.pdfbox.cos.COSBase;

final class ParsedStreams {

    static final int MAX_TOKENS = 500_000;

    private record Parsed(Object[] tokens, boolean reusable) {}

    private final Map<COSBase, Parsed> parsed = new LinkedHashMap<>(16, 0.75f, true);
    private long held;

    void put(COSBase key, Object[] tokens, boolean reusable) {
        Parsed old = parsed.put(key, new Parsed(tokens, reusable));
        if (old != null) {
            held -= old.tokens().length;
        }
        held += tokens.length;
        Iterator<Parsed> eldest = parsed.values().iterator();
        while (held > MAX_TOKENS && eldest.hasNext()) {
            held -= eldest.next().tokens().length;
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
            held -= found.tokens().length;
        }
        return found.tokens();
    }
}
