package stirling.software.officeconvert.topdf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.RuntimeModules;
import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.OfficeToPdf.Result;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.font.MissingScripts;
import stirling.software.officeconvert.topdf.ofd.OfdRenderer;
import stirling.software.officeconvert.topdf.pdf.PdfOutput;

final class OfdInput {

    private OfdInput() {}

    static Long estimate(Path source) throws IOException {
        return OfdRenderer.is(source) ? OfdRenderer.memoryBound(source) + 2 * Admission.BASE_BYTES : null;
    }

    static Result render(Path source, OutputStream sink, Options options) throws IOException {
        if (!OfdRenderer.is(source)) {
            return null;
        }
        FontLibrary fonts = options.fontLibrary();
        try (Admission.Ticket _ = Admission.jvm().enter(OfdRenderer.memoryBound(source) + Admission.BASE_BYTES);
                PdfOutput output = new PdfOutput(fonts, options.maxScratchBytes())) {
            OfdRenderer.Drawn drawn = OfdRenderer.draw(source, output, fonts, options.maxPages());
            OfficeToPdf.stopIfInterrupted();
            output.save(sink);
            List<String> warnings = new ArrayList<>(RuntimeModules.warnings());
            List<String> found = new ArrayList<>(drawn.warnings());
            found.addAll(MissingScripts.warnings(output.fonts().uncoveredScripts(), output.info().language()));
            for (String w : found) {
                String c = RenderJob.clean(w);
                if (c != null && !warnings.contains(c)) {
                    warnings.add(c);
                }
            }
            for (String s : output.fonts().substitutions()) {
                String c = RenderJob.clean(s);
                if (c != null && !warnings.contains(c)) {
                    warnings.add(c);
                }
            }
            boolean limited = drawn.pages() < drawn.totalPages();
            if (limited) {
                warnings.add("Stopped at the page limit of " + options.maxPages() + " pages");
            }
            return new Result(drawn.pages(), limited || !drawn.warnings().isEmpty(), warnings, limited);
        }
    }
}
