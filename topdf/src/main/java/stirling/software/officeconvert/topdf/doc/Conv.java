package stirling.software.officeconvert.topdf.doc;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;

final class Conv {

    static final long MAX_PART_CHARS = 160L << 20;

    final Source src;

    final Zip zip;

    final Media media;

    final List<String> warnings = new ArrayList<>();

    final Styles styles;

    final Lists lists;

    final Drawings drawings;

    Notes notes;

    boolean lost;

    boolean truncated;

    private int ids = 1;

    private int ticks;

    Conv(Source src, Zip zip) {
        this.src = src;
        this.zip = zip;
        this.media = new Media(zip);
        this.styles = new Styles(src);
        this.lists = new Lists(src);
        this.drawings = new Drawings(this);
    }

    private Textboxes textboxes;

    Textboxes textboxes() {
        if (textboxes == null) {
            textboxes = new Textboxes(src);
        }
        return textboxes;
    }

    int nextId() {
        return ids++;
    }

    void warn(String w) {
        if (!warnings.contains(w)) {
            warnings.add(w);
        }
    }

    void checkpoint() throws InterruptedIOException {
        if ((++ticks & 63) == 0 && Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Conversion interrupted");
        }
    }

    boolean full(StringBuilder part) {
        if (part.length() > MAX_PART_CHARS) {
            if (!truncated) {
                truncated = true;
                lost = true;
                warn("The document is too large; only its first part was converted");
            }
            return true;
        }
        return false;
    }
}
