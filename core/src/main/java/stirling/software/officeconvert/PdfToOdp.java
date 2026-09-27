package stirling.software.officeconvert;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.odp.OdpWriter;
import stirling.software.officeconvert.slides.SlideConversion;

public final class PdfToOdp {

    private PdfToOdp() {}

    public static void convert(Path pdf, Path odp, PdfToPptx.Options options) throws IOException {
        SlideConversion.convert(pdf, odp, options, out -> new OdpWriter(out, options.pictures()));
    }

    public static void convert(PDDocument doc, OutputStream out, PdfToPptx.Options options) throws IOException {
        SlideConversion.convert(doc, out, options, o -> new OdpWriter(o, options.pictures()));
    }
}
