package stirling.software.officeconvert.pdfa;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.cos.COSStream;

final class ContentCache implements AutoCloseable {

    private static final long MAX_BYTES = 64L << 20;

    private static final int MAX_ENTRIES = 2048;

    private static final ThreadLocal<ContentCache> CURRENT = new ThreadLocal<>();

    private final ContentCache previous;

    private final Map<COSStream, byte[]> bytes = new IdentityHashMap<>();

    private final Map<List<COSStream>, List<Object>> tokens = new LinkedHashMap<>();

    private long byteSize;

    private long tokenSize;

    private ContentCache() {
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    static ContentCache open() {
        return new ContentCache();
    }

    static <T> T run(Deadline.Work<T> work) throws java.io.IOException {
        ContentCache cache = open();
        try {
            return work.call();
        } finally {
            cache.close();
        }
    }

    static ContentCache current() {
        return CURRENT.get();
    }

    byte[] bytes(COSStream stream) {
        return bytes.get(stream);
    }

    void bytes(COSStream stream, byte[] value) {
        if (value.length > MAX_BYTES) {
            return;
        }
        if (byteSize + value.length > MAX_BYTES || bytes.size() >= MAX_ENTRIES) {
            bytes.clear();
            byteSize = 0;
        }
        byte[] old = bytes.put(stream, value);
        byteSize += value.length - (old == null ? 0 : old.length);
    }

    List<Object> tokens(List<COSStream> streams) {
        return tokens.get(streams);
    }

    void tokens(List<COSStream> streams, List<Object> value) {
        long size = value.size() * ContentBudget.BYTES_PER_TOKEN;
        if (size > MAX_BYTES) {
            return;
        }
        if (tokenSize + size > MAX_BYTES || tokens.size() >= MAX_ENTRIES) {
            tokens.clear();
            tokenSize = 0;
        }
        List<Object> old = tokens.put(List.copyOf(streams), List.copyOf(value));
        tokenSize += size - (old == null ? 0 : old.size() * ContentBudget.BYTES_PER_TOKEN);
    }

    void changed(COSStream stream) {
        byte[] old = bytes.remove(stream);
        byteSize -= old == null ? 0 : old.length;
        var iterator = tokens.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<List<COSStream>, List<Object>> entry = iterator.next();
            if (entry.getKey().contains(stream)) {
                tokenSize -= entry.getValue().size() * ContentBudget.BYTES_PER_TOKEN;
                iterator.remove();
            }
        }
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
