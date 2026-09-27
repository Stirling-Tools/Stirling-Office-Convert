package stirling.software.officeconvert.sink;

import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ZipParts {

    private ZipParts() {}

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
