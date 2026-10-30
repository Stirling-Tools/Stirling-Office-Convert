package stirling.software.officeconvert.topdf.io;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

public final class XmlElements extends FilterInputStream {

    public static final long MAX_POI_PART = 250_000;

    public static final long MAX_POI_CHART = 2_000_000;

    public static final long MAX_STYLES = 500_000;

    private final String part;

    private final long max;

    private long count;

    private boolean open;

    private XmlElements(InputStream in, String part, long max) {
        super(in);
        this.part = part;
        this.max = max;
    }

    public static InputStream limit(InputStream in, String part, long max) {
        return new XmlElements(in, part, max);
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
            if (c != '/' && c != '?' && c != '!' && ++count > max) {
                String m = tooMany(part, max);
                throw new OfficeZip.DamagedPart(part, m, new OfficeZip.Oversized(m));
            }
        }
        if (c == '<') {
            open = true;
        }
    }
}
