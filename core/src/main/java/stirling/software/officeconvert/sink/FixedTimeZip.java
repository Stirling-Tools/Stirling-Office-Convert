package stirling.software.officeconvert.sink;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** A zip writer that dates every entry 1980-02-01, so the package bytes do not depend on the server clock or zone. */
public final class FixedTimeZip extends ZipOutputStream {

    static final LocalDateTime ENTRY_TIME = LocalDateTime.of(1980, 2, 1, 0, 0);

    public FixedTimeZip(OutputStream out, Charset charset) {
        super(out, charset);
    }

    @Override
    public void putNextEntry(ZipEntry e) throws IOException {
        if (e.getTime() == -1) {
            e.setTimeLocal(ENTRY_TIME);
        }
        super.putNextEntry(e);
    }
}
