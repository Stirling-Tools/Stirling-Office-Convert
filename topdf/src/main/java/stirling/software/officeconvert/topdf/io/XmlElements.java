package stirling.software.officeconvert.topdf.io;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // The first bytes, until the XML declaration (if any) has closed and its encoding has been checked
    private byte[] prolog = new byte[PROLOG];

    private int seen;

    private static final int PROLOG = 256;

    private static final byte[] EBCDIC_DECLARATION = {0x4C, 0x6F, (byte) 0xA7, (byte) 0x94};

    private static final Pattern ENCODING =
            Pattern.compile("^<\\?xml\\s[^>]*?encoding\\s*=\\s*[\"']([A-Za-z0-9._:-]{1,64})");

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
        if (prolog != null) {
            prolog(c);
        }
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

    // The count keys on the byte 0x3C, so an encoding that writes '<' as another byte would go uncounted
    private void prolog(int c) throws IOException {
        prolog[seen++] = (byte) c;
        if (seen == EBCDIC_DECLARATION.length && Arrays.equals(prolog, 0, seen, EBCDIC_DECLARATION, 0, seen)) {
            throw unreadable("EBCDIC");
        }
        if (c == '>' || seen == prolog.length) {
            StringBuilder head = new StringBuilder();
            for (int i = 0; i < seen; i++) {
                int b = prolog[i] & 0xFF;
                if (b != 0 && (head.length() > 0 || b < 0x80)) {
                    head.append((char) b);
                }
            }
            prolog = null;
            Matcher m = ENCODING.matcher(head);
            if (m.find() && !countable(m.group(1))) {
                throw unreadable(m.group(1));
            }
        }
    }

    private static boolean countable(String encoding) {
        try {
            byte[] marks = "<>/?!".getBytes(Charset.forName(encoding));
            StringBuilder ascii = new StringBuilder();
            for (byte b : marks) {
                if (b != 0 && b != (byte) 0xFE && b != (byte) 0xFF) {
                    ascii.append((char) (b & 0xFF));
                }
            }
            return ascii.toString().equals("<>/?!");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private OfficeZip.DamagedPart unreadable(String encoding) {
        String m = "The part " + part + " is in the " + encoding + " encoding, which is not supported";
        return new OfficeZip.DamagedPart(part, m, null);
    }
}
