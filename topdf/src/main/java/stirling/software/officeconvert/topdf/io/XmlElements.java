package stirling.software.officeconvert.topdf.io;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import stirling.software.officeconvert.memory.Admission;

public final class XmlElements extends FilterInputStream {

    public static final long MAX_POI_PART = 250_000;

    public static final long MAX_POI_CHART = 2_000_000;

    public static final long MAX_STYLES = 500_000;

    public static final long MAX_PART = 500_000;

    public static final long MAX_DOM_PART = 4_000_000;

    static final long DOM_ELEMENT_BYTES = 512;

    private final String part;

    private final long max;

    private final boolean checked;

    private long count;

    private boolean open;

    private XmlElements(InputStream in, String part, long max, boolean checked) {
        super(in);
        this.part = part;
        this.max = max;
        this.checked = checked;
    }

    public static InputStream limit(InputStream in, String part, long max) {
        return new XmlElements(in, part, max, false);
    }

    static InputStream dom(InputStream in, String part) {
        return new XmlElements(in, part, domElements(), true);
    }

    public static long domElements() {
        return Math.max(MAX_PART, Math.min(MAX_DOM_PART, Admission.jvm().budget() / DOM_ELEMENT_BYTES));
    }

    public static long share(long elementBytes, long min, long max) {
        Admission.Status status = Admission.jvm().status();
        return Math.max(min, Math.min(max, status.budgetBytes() / Math.max(1, status.slots()) / elementBytes));
    }

    String part() {
        return part;
    }

    public static String tooMany(String part, long max) {
        return "The document is too large: the part " + part + " has more than " + max + " XML elements";
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            see(b);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        for (int i = off; i < off + n; i++) {
            see(b[i]);
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = 0;
        while (skipped < n && read() >= 0) {
            skipped++;
        }
        return skipped;
    }

    private void see(int c) throws IOException {
        if (open) {
            if (c == 0) {
                return;
            }
            open = false;
            if (c != '/' && c != '?' && c != '!') {
                if (++count > max) {
                    String m = tooMany(part, max);
                    throw new OfficeZip.DamagedPart(part, m, new OfficeZip.Oversized(m));
                }
                if (checked && (count & 0xFFF) == 0) {
                    Admission.checkpoint();
                }
            }
        }
        if (c == '<') {
            open = true;
        }
    }
}
