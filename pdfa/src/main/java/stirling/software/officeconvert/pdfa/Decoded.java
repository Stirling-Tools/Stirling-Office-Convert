package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.FilterFactory;

import stirling.software.officeconvert.extract.PdfFiles;

final class Decoded {

    static final long MAX_STREAM_BYTES = 256L << 20;

    private static final int CHECK_BYTES = 1 << 20;

    static final class TooLarge extends IOException {
        TooLarge(String message) {
            super(message);
        }

        TooLarge(String what, long limit) {
            this(what + " is larger than " + (limit >> 20) + " MB when decompressed");
        }
    }

    private Decoded() {}

    static byte[] bytes(COSStream s, long limit, String what) throws IOException {
        Capped out = new Capped(limit, what, null);
        decode(s, out, limit, what);
        return out.toByteArray();
    }

    static long copy(COSStream s, OutputStream to, long limit, String what) throws IOException {
        Capped out = new Capped(limit, what, to);
        decode(s, out, limit, what);
        return out.written;
    }

    static void rethrowFatal(IOException e) throws IOException {
        if (e instanceof TooLarge || e instanceof java.io.InterruptedIOException) {
            throw e;
        }
    }

    private static void decode(COSStream s, Capped out, long limit, String what) throws IOException {
        List<COSName> filters = filters(s);
        try (InputStream raw = s.createRawInputStream()) {
            InputStream in = raw;
            for (int i = 0; i < filters.size(); i++) {
                Capped stage = i == filters.size() - 1 ? out : new Capped(limit, what, null);
                FilterFactory.INSTANCE.getFilter(filters.get(i)).decode(in, stage, s, i);
                if (stage != out) {
                    in = new ByteArrayInputStream(stage.buffer(), 0, stage.size());
                }
            }
            if (filters.isEmpty()) {
                in.transferTo(out);
            }
        } catch (Stop e) {
            throw (IOException) e.getCause();
        } catch (RuntimeException e) {
            PdfFiles.stopIfInterrupted();
            throw new IOException(e);
        }
    }

    private static List<COSName> filters(COSStream s) throws IOException {
        COSBase f = s.getDictionaryObject(COSName.FILTER);
        List<COSName> out = new ArrayList<>();
        if (f instanceof COSName n) {
            out.add(n);
        } else if (f instanceof COSArray a) {
            for (int i = 0; i < a.size(); i++) {
                if (a.getObject(i) instanceof COSName n) {
                    out.add(n);
                } else {
                    throw new IOException("A stream filter is not a name");
                }
            }
        } else if (f != null) {
            throw new IOException("A stream filter is not a name");
        }
        return out;
    }

    private static final class Capped extends ByteArrayOutputStream {

        private final long limit;

        private final String what;

        private final OutputStream forward;

        private long written;

        private long checked;

        Capped(long limit, String what, OutputStream forward) {
            this.limit = limit;
            this.what = what;
            this.forward = forward;
        }

        @Override
        public void write(int b) {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            try {
                admit(len);
                if (forward != null) {
                    forward.write(b, off, len);
                } else {
                    super.write(b, off, len);
                }
            } catch (IOException e) {
                throw new Stop(e);
            }
        }

        private void admit(int len) throws IOException {
            written += len;
            if (written > limit) {
                throw new TooLarge(what, limit);
            }
            if (written - checked >= CHECK_BYTES) {
                checked = written;
                PdfFiles.stopIfInterrupted();
            }
        }

        byte[] buffer() {
            return buf;
        }
    }

    private static final class Stop extends RuntimeException {
        Stop(IOException cause) {
            super(cause);
        }
    }
}
