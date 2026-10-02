package stirling.software.officeconvert.topdf.ooo1;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.testing.Allocation;
import stirling.software.officeconvert.topdf.testing.ZipBytes;

class Ooo1BoundsTest {

    @TempDir
    Path dir;

    private static final String CONTENT = "<?xml version=\"1.0\"?><office:document-content"
            + " xmlns:office=\"http://openoffice.org/2000/office\" xmlns:text=\"http://openoffice.org/2000/text\">"
            + "<office:body><text:p>x</text:p></office:body></office:document-content>";

    private static ZipBytes writer() {
        return new ZipBytes().add("mimetype", "application/vnd.sun.xml.writer").add("content.xml", CONTENT);
    }

    private Allocation.Measured convert(byte[] sxw) throws IOException {
        Path in = Files.write(dir.resolve("doc.sxw"), sxw);
        return Allocation.measure(() -> Ooo1Package.write(in, Ooo1Package.Kind.TEXT, OutputStream.nullOutputStream()));
    }

    @Test
    void contentThatInflatesPastItsDeclaredSizeIsRefused() throws IOException {
        byte[] sxw = new ZipBytes().add("mimetype", "application/vnd.sun.xml.writer")
                .repeat("content.xml", "<?xml version=\"1.0\"?>", new byte[1 << 20], 400, "").bytes();
        Allocation.Measured m = convert(ZipBytes.declareSize(sxw, "content.xml", 1000));
        assertInstanceOf(OfficeZip.Oversized.class, m.failure());
        assertTrue(m.bytes() < 400L << 20, "allocated " + m.megabytes() + " MB");
    }

    @Test
    void picturesThatInflatePastTheirDeclaredSizeAreRefused() throws IOException {
        ZipBytes z = writer();
        for (int i = 0; i < 4; i++) {
            z.repeat("Pictures/" + i + ".png", "", new byte[1 << 20], 100, "");
        }
        byte[] sxw = z.bytes();
        for (int i = 0; i < 4; i++) {
            sxw = ZipBytes.declareSize(sxw, "Pictures/" + i + ".png", 1000);
        }
        assertInstanceOf(OfficeZip.Oversized.class, convert(sxw).failure());
    }

    @Test
    void picturesShareOneInflateBudget() throws IOException {
        ZipBytes z = writer();
        for (int i = 0; i < 18; i++) {
            z.repeat("Pictures/" + i + ".png", "", new byte[1 << 20], 60, "");
        }
        assertInstanceOf(OfficeZip.Oversized.class, convert(z.bytes()).failure());
    }

    @Test
    void anInterruptedConversionStops() throws IOException {
        Thread.currentThread().interrupt();
        try {
            assertInstanceOf(InterruptedIOException.class, convert(writer().bytes()).failure());
        } finally {
            Thread.interrupted();
        }
    }
}
