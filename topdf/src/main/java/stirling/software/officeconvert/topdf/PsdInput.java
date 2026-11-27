package stirling.software.officeconvert.topdf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import stirling.software.officeconvert.UnsupportedFormatException;
import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.OfficeToPdf.Result;
import stirling.software.officeconvert.topdf.pdf.PdfOutput;
import stirling.software.officeconvert.topdf.psd.PsdPage;

final class PsdInput {

    private PsdInput() {}

    static Long estimate(Path source) throws IOException {
        return isPsd(source) ? PsdPage.estimate(source) : null;
    }

    private static boolean isPsd(Path source) throws IOException {
        if (PsdPage.is(source)) {
            return true;
        }
        Path name = source.getFileName();
        String n = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".psd") || n.endsWith(".psb")) {
            throw new UnsupportedFormatException("The file is not a PSD document: it does not start with 8BPS");
        }
        return false;
    }

    static Result render(Path source, OutputStream sink, Options options) throws IOException {
        if (!isPsd(source)) {
            return null;
        }
        try (Admission.Ticket _ = Admission.jvm().enter(PsdPage.estimate(source));
                PdfOutput output = new PdfOutput(options.fontLibrary(), options.maxScratchBytes())) {
            List<String> warnings = PsdPage.draw(source, output.document());
            OfficeToPdf.stopIfInterrupted();
            output.save(sink);
            return new Result(1, false, warnings, false);
        }
    }
}
