package stirling.software.officeconvert.topdf.xlsb;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

/** The BIFF12 record stream of one .bin part ([MS-XLSB] 2.1.4): a variable-length type and size before each record's
 * data. A record that claims more bytes than remain ends the stream. */
final class Records {

    static final int MAX_RECORD_BYTES = 16 << 20;

    private final InputStream in;

    private int type;

    private byte[] data = new byte[256];

    private int size;

    private long count;

    Records(InputStream in) {
        this.in = in;
    }

    boolean next() throws IOException {
        if ((++count & 0xFFF) == 0 && Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Conversion interrupted");
        }
        int b = in.read();
        if (b < 0) {
            return false;
        }
        int t = b & 0x7F;
        if ((b & 0x80) != 0) {
            int b2 = in.read();
            if (b2 < 0) {
                return false;
            }
            t |= (b2 & 0x7F) << 7;
        }
        int n = 0;
        for (int i = 0; i < 4; i++) {
            int c = in.read();
            if (c < 0) {
                return false;
            }
            n |= (c & 0x7F) << (7 * i);
            if ((c & 0x80) == 0) {
                break;
            }
        }
        if (n > MAX_RECORD_BYTES) {
            return false;
        }
        if (data.length < n) {
            data = new byte[Math.max(n, data.length * 2)];
        }
        int got = in.readNBytes(data, 0, n);
        if (got < n) {
            return false;
        }
        type = t;
        size = n;
        return true;
    }

    int type() {
        return type;
    }

    Data data() {
        return new Data(data, size);
    }
}
