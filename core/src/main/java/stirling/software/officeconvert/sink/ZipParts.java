package stirling.software.officeconvert.sink;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ZipParts {

    private ZipParts() {}

    public static ZipOutputStream open(OutputStream target) {
        return new ZipOutputStream(new BufferedOutputStream(new KeepOpen(target), 1 << 16), StandardCharsets.UTF_8);
    }

    public static void stored(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(bytes.length);
        e.setCompressedSize(bytes.length);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        e.setCrc(crc.getValue());
        zip.putNextEntry(e);
        zip.write(bytes);
        zip.closeEntry();
    }

    public static void picture(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        if (name.endsWith(".png")) {
            stored(zip, name, bytes);
            return;
        }
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }
}
