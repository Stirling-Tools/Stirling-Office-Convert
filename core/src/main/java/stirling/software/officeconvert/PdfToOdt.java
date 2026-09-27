package stirling.software.officeconvert;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.odt.OdtWriter;

public final class PdfToOdt {

    private PdfToOdt() {}

    public static void convert(Path pdf, Path odt, PdfToDocx.Options options) throws IOException {
        PdfToDocx.convert(pdf, odt, options, out -> new OdtWriter(out, false, options.pictures()));
    }

    public static void convert(PDDocument doc, OutputStream out, PdfToDocx.Options options) throws IOException {
        PdfToDocx.convert(doc, out, options, o -> new OdtWriter(o, false, options.pictures()));
    }

    public static void convertFlat(Path pdf, Path fodt, PdfToDocx.Options options) throws IOException {
        PdfToDocx.convert(pdf, fodt, options, out -> new OdtWriter(out, true, options.pictures()));
    }

    public static void convertFlat(PDDocument doc, OutputStream out, PdfToDocx.Options options) throws IOException {
        PdfToDocx.convert(doc, out, options, o -> new OdtWriter(o, true, options.pictures()));
    }
}
