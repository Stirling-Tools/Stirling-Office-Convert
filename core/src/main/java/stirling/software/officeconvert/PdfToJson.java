package stirling.software.officeconvert;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.json.JsonWriter;

public final class PdfToJson {

    private PdfToJson() {}

    public static void convert(Path pdf, Path json, PdfToDocx.Options options) throws IOException {
        PdfToDocx.convert(pdf, json, options, out -> new JsonWriter(out, options.pictures()));
    }

    public static void convert(PDDocument doc, OutputStream out, PdfToDocx.Options options) throws IOException {
        PdfToDocx.convert(doc, out, options, o -> new JsonWriter(o, options.pictures()));
    }
}
