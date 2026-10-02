package stirling.software.officeconvert.topdf.xls;

import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

// The zip package the XLSX renderer reads, with each part and the whole package kept inside OfficeZip's limits
public final class Parts {

    static final long MAX_PART_BYTES = 480L << 20;

    static final long MAX_TOTAL_BYTES = 960L << 20;

    private final ZipOutputStream zip;

    private long total;

    public Parts(OutputStream out) {
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

    public Part open(String name) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        return new Part();
    }

    public void put(String name, String xml) throws IOException {
        try (Part p = open(name)) {
            p.write(xml);
        }
    }

    public void put(String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
        total += data.length;
    }

    public void put(String name, java.io.InputStream in) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        byte[] buffer = new byte[1 << 16];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            zip.write(buffer, 0, n);
            total += n;
        }
        zip.closeEntry();
    }

    public boolean full(long more) {
        return total + more > MAX_TOTAL_BYTES;
    }

    public void finish() throws IOException {
        zip.finish();
        zip.flush();
    }

    public final class Part implements AutoCloseable {

        private long count;

        private final Writer writer = new BufferedWriter(new OutputStreamWriter(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                zip.write(b);
                count++;
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                zip.write(b, off, len);
                count += len;
            }
        }, StandardCharsets.UTF_8), 1 << 16);

        public void write(String s) throws IOException {
            writer.write(s);
        }

        public Part append(String s) throws IOException {
            writer.write(s);
            return this;
        }

        public boolean full() {
            return count > MAX_PART_BYTES || total + count > MAX_TOTAL_BYTES;
        }

        @Override
        public void close() throws IOException {
            writer.flush();
            zip.closeEntry();
            total += count;
        }
    }
}
