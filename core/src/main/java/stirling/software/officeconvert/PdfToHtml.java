package stirling.software.officeconvert;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.html.HtmlWriter;

public final class PdfToHtml {

    private PdfToHtml() {}

    public static void convert(Path pdf, Path html, PdfToDocx.Options options) throws IOException {
        PdfToDocx.convert(pdf, html, options, out -> new HtmlWriter(out, options.pictures()));
    }

    public static void convert(PDDocument doc, OutputStream out, PdfToDocx.Options options) throws IOException {
        PdfToDocx.convert(doc, out, options, o -> new HtmlWriter(o, options.pictures()));
    }
}
