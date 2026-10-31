package stirling.software.officeconvert.sink;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public final class KeepOpen extends FilterOutputStream {

    public KeepOpen(OutputStream target) {
        super(target);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
    }

    @Override
    public void close() throws IOException {
        out.flush();
    }
}
