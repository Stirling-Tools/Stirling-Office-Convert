package stirling.software.officeconvert.topdf.doc;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class Zip {

    static final long MAX_TOTAL_BYTES = 960L << 20;

    private final ZipOutputStream zip;

    private long total;

    Zip(OutputStream out) {
        this.zip = new ZipOutputStream(new FilterOutputStream(out) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                out.flush();
            }
        });
        zip.setLevel(Deflater.BEST_SPEED);
    }

    void put(String name, CharSequence xml) throws IOException {
        put(name, xml.toString().getBytes(StandardCharsets.UTF_8));
    }

    void put(String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
        total += data.length;
    }

    boolean fits(long more) {
        return total + more <= MAX_TOTAL_BYTES;
    }

    void finish() throws IOException {
        zip.finish();
        zip.flush();
    }
}
