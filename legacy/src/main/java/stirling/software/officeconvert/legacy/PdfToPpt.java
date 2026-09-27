package stirling.software.officeconvert.legacy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.PdfToPptx;
import stirling.software.officeconvert.slides.SlideConversion;

public final class PdfToPpt {

    private PdfToPpt() {}

    public static void convert(Path pdf, Path ppt, PdfToPptx.Options options) throws IOException {
        SlideConversion.convert(pdf, ppt, options, PptWriter::new);
    }

    public static void convert(PDDocument doc, OutputStream out, PdfToPptx.Options options) throws IOException {
        SlideConversion.convert(doc, out, options, PptWriter::new);
    }
}
