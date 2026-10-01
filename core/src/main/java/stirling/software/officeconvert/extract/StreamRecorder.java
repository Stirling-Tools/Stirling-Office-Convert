package stirling.software.officeconvert.extract;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.COSBase;

final class StreamRecorder {

    private static final class Recording {
        final COSBase key;
        final int depth;
        final boolean reusable;
        List<Object> tokens = new ArrayList<>();
        boolean inline;

        Recording(COSBase key, int depth, boolean reusable) {
            this.key = key;
            this.depth = depth;
            this.reusable = reusable;
        }
    }

    private final ParsedStreams store;
    private final Deque<Recording> open = new ArrayDeque<>();
    private int depth;

    StreamRecorder(ParsedStreams store) {
        this.store = store;
    }

    void begin(COSBase key, boolean reusable) {
        open.push(new Recording(key, depth, reusable));
    }

    void end(boolean completed) {
        Recording r = open.poll();
        if (store != null && r != null && completed && r.tokens != null && !r.tokens.isEmpty() && r.key != null) {
            store.put(r.key, r.tokens.toArray(), r.reusable && !r.inline);
        }
    }

    void close() {
        open.clear();
    }

    void operator(Operator operator, List<COSBase> operands) {
        Recording r = open.peek();
        if (store == null || r == null || r.depth != depth || r.tokens == null) {
            return;
        }
        if (r.tokens.size() + operands.size() >= ParsedStreams.MAX_TOKENS) {
            r.tokens = null;
            return;
        }
        r.tokens.addAll(operands);
        r.tokens.add(operator);
        r.inline |= OperatorName.BEGIN_INLINE_IMAGE.equals(operator.getName());
    }

    void enter() {
        depth++;
    }

    void leave() {
        depth--;
    }

    void abort() {
        for (Recording r : open) {
            r.tokens = null;
        }
    }
}
