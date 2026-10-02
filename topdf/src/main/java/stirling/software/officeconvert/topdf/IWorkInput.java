package stirling.software.officeconvert.topdf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.OfficeToPdf.Result;
import stirling.software.officeconvert.topdf.iwork.IWorkPreview;
import stirling.software.officeconvert.topdf.pdf.PdfOutput;

/** Apple iWork documents, drawn from the preview they hold and said so plainly. */
final class IWorkInput {

    private IWorkInput() {}

    static Long estimate(Path source) throws IOException {
        return IWorkPreview.is(source) ? footprint(source) + Admission.BASE_BYTES : null;
    }

    private static long footprint(Path source) throws IOException {
        return 4 * IWorkPreview.memoryBound(source) + Admission.BASE_BYTES;
    }

    static Result render(Path source, OutputStream sink, Options options) throws IOException {
        if (!IWorkPreview.is(source)) {
            return null;
        }
        try (Admission.Ticket _ = Admission.jvm().enter(footprint(source));
                PdfOutput output = new PdfOutput(options.fontLibrary(), options.maxScratchBytes())) {
            IWorkPreview.Drawn drawn = IWorkPreview.draw(source, output, options.maxPages());
            OfficeToPdf.stopIfInterrupted();
            output.save(sink);
            List<String> warnings = new ArrayList<>();
            if (drawn.pictureOnly()) {
                warnings.add("The Apple iWork document was drawn from the preview picture it holds, of its first page"
                        + " only; export it as PDF or as an Office document for the whole document");
            } else {
                warnings.add("The Apple iWork document was drawn from the PDF preview it holds");
            }
            boolean limited = drawn.pages() < drawn.totalPages();
            if (limited) {
                warnings.add("Stopped at the page limit of " + options.maxPages() + " pages");
            }
            return new Result(drawn.pages(), drawn.pictureOnly() || limited, warnings, limited);
        }
    }
}
