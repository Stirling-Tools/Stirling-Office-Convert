package stirling.software.officeconvert.topdf.io;

import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

public final class PdfBytes {

    private PdfBytes() {}

    public static PDDocument load(byte[] data) throws IOException {
        OfficeZip.checkNotInterrupted();
        return Loader.loadPDF(data);
    }
}
