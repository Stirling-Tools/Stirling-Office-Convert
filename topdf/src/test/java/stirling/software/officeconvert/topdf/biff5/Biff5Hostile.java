package stirling.software.officeconvert.topdf.biff5;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import stirling.software.officeconvert.topdf.testing.NoNetwork;

public final class Biff5Hostile {

    private Biff5Hostile() {}

    public static byte[] build(NoNetwork net) {
        byte[] stream = Biff5Test.workbook(false, "&C" + net.url("header"));
        try (POIFSFileSystem fs = new POIFSFileSystem(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fs.createDocument(new ByteArrayInputStream(stream), "Book");
            fs.writeFilesystem(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
