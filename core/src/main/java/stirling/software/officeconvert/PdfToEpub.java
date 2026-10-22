package stirling.software.officeconvert;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.html.EpubWriter;

public final class PdfToEpub {

    private PdfToEpub() {}

    public static void convert(Path pdf, Path epub, PdfToDocx.Options options) throws IOException {
        PdfToDocx.convert(pdf, epub, options, out -> new EpubWriter(out, options.pictures()));
    }

    public static void convert(PDDocument doc, OutputStream out, PdfToDocx.Options options) throws IOException {
        PdfToDocx.convert(doc, out, options, o -> new EpubWriter(o, options.pictures()));
    }
}
