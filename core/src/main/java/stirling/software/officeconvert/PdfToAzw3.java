package stirling.software.officeconvert;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.html.Azw3Writer;

public final class PdfToAzw3 {

    private PdfToAzw3() {}

    public static void convert(Path pdf, Path azw3, PdfToDocx.Options options) throws IOException {
        PdfToDocx.convert(pdf, azw3, options, out -> new Azw3Writer(out, options.pictures()));
    }

    public static void convert(PDDocument doc, OutputStream out, PdfToDocx.Options options) throws IOException {
        PdfToDocx.convert(doc, out, options, o -> new Azw3Writer(o, options.pictures()));
    }
}
