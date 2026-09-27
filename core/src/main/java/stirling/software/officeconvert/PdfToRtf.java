package stirling.software.officeconvert;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.rtf.RtfWriter;

public final class PdfToRtf {

    private PdfToRtf() {}

    public static void convert(Path pdf, Path rtf, PdfToDocx.Options options) throws IOException {
        PdfToDocx.convert(pdf, rtf, options, out -> new RtfWriter(out, options.pictures()));
    }

    public static void convert(PDDocument doc, OutputStream out, PdfToDocx.Options options) throws IOException {
        PdfToDocx.convert(doc, out, options, o -> new RtfWriter(o, options.pictures()));
    }
}
