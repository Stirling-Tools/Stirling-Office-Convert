package stirling.software.officeconvert;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.text.TextWriter;

public final class PdfToText {

    private static final float FIGURE_DPI = 36f;

    private PdfToText() {}

    public static void convert(Path pdf, Path txt, PdfToDocx.Options options) throws IOException {
        PdfToDocx.convert(pdf, txt, textOptions(options), TextWriter::new);
    }

    public static void convert(PDDocument doc, OutputStream out, PdfToDocx.Options options) throws IOException {
        PdfToDocx.convert(doc, out, textOptions(options), TextWriter::new);
    }

    public static String text(PDDocument doc, PdfToDocx.Options options) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        convert(doc, out, options);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static PdfToDocx.Options textOptions(PdfToDocx.Options o) {
        return new PdfToDocx.Options(o.firstPage(), o.lastPage(), o.tables(), FIGURE_DPI, o.password(), o.pictureFallback());
    }
}
